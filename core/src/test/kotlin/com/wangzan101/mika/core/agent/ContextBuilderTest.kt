package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class ContextBuilderTest {
    private fun app(name: String, body: String = "正文$name", pkg: String = "p.$name", links: List<Link> = emptyList()) =
        KnowledgeEntry(name, "显示$name", Surface.Android, listOf(pkg), emptyList(), listOf(name), null, "", body, links = links)
    private fun site(name: String, domain: String) =
        KnowledgeEntry(name, "站$name", Surface.Web, emptyList(), listOf(domain), listOf(name), null, "", "正文$name")
    private fun text(m: Message) = (m.parts.single() as Part.Text).text
    private val utc = ZoneId.of("UTC")

    @Test fun envelopeCarriesKindSourceTrustAndRoundTrips() {
        val m = ContextBuilder().notice("步数自查", "正文里有 </context> 也不会提前合上")
        val t = text(m)
        assertEquals(Role.User, m.role)
        assertTrue(t, t.startsWith("<context kind=\"notice\" source=\"步数自查\" trust=\"${Trust.Mika.label}\">\n"))
        assertTrue(t.endsWith("\n</context>"))
        assertEquals(1, Regex("</context>").findAll(t).count())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(m))
        assertEquals("步数自查", ContextBuilder.sourceOf(m))
        assertEquals("正文里有 <\\/context> 也不会提前合上", ContextBuilder.bodyOf(m))
        // ユーザーの元の言葉、ツールの結果はコンテキストではありません
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.Tool, listOf(Part.ToolResult("c", ContextBuilder.envelope(ContextKind.Notice, "x", "y"), false)), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("<context kind=\"unknown\" source=\"x\">\n\n</context>")), Author.Mika)))
    }

    @Test fun startOrdersMessagesAndOnlyTaskIsPlain() {
        val s = ContextBuilder().start(
            "帮我查天气", priorTurns = "之前", memory = "记忆", channel = "可用渠道:…",
            knowledge = listOf(app("a"), site("s", "s.example.com")), knowledgeNote = "(注)", skillsIndex = "索引",
        )
        assertEquals(
            listOf(ContextKind.PriorTurns, ContextKind.Memory, null, ContextKind.Channel,
                ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex),
            s.messages.map { ContextBuilder.kindOf(it) },
        )
        assertEquals("帮我查天气", text(s.messages[2]))
        assertTrue(s.messages.all { it.role == Role.User })
        assertEquals("(注)\n正文a", ContextBuilder.bodyOf(s.messages[4]))
        assertEquals("显示a · p.a", ContextBuilder.sourceOf(s.messages[4]))
        assertEquals("站s · s.example.com", ContextBuilder.sourceOf(s.messages[5]))
        assertEquals("索引", ContextBuilder.bodyOf(s.messages[6]))
        assertEquals(listOf("a", "s"), s.knowledge.map { it.name })
        // 参照マークは期限切れである可能性があります。記憶マークはユーザーが以前に言ったことです
        assertTrue(Trust.Reference.label in text(s.messages[4]) && Trust.UserMemory.label in text(s.messages[1]))
    }

    @Test fun knowledgeItemCappedWithHint() {
        // の上限はトークンに応じて異なります。漢字は約0.75トークンで、単語数は上限の2倍を超える必要があります。
        val long = "字".repeat(KNOWLEDGE_BODY_LIMIT * 2)
        val s = ContextBuilder().start("t", knowledge = listOf(app("a", body = long)))
        val body = ContextBuilder.bodyOf(s.messages[1])!!
        assertEquals(clipKnowledge(long), body)
        assertTrue(body.endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertTrue(TokenEstimate.of(body) <= KNOWLEDGE_BODY_LIMIT)
    }

    @Test fun layerBudgetClipsThenDrops() {
        // L2 上限 5000 トークン: 最初のトークンは完全に 3000 (漢字 4000 文字)、2 番目のトークンは残り 2000 に切り捨てられ、制限がない場合は 3 番目のトークンは与えられません
        val b = ContextBuilder(ContextBudget(layers = mapOf(ContextLayer.Knowledge to 5_000), knowledgeItem = 10_000))
        val s = b.start("t", knowledge = (1..3).map { app("k$it", body = "字".repeat(4_000)) })
        val bodies = s.messages.mapNotNull { m -> ContextBuilder.bodyOf(m)?.takeIf { ContextBuilder.kindOf(m) == ContextKind.AppKnowledge } }
        assertEquals(2, bodies.size)
        assertEquals(4_000, bodies[0].length)
        assertTrue("${TokenEstimate.of(bodies[1])}", TokenEstimate.of(bodies[1]) in 1_995..2_000)
        assertTrue(bodies[1].endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertEquals(listOf("k1", "k2"), s.knowledge.map { it.name })
        // もう 1 つ途中です: L2 がいっぱいなので与えられません
        assertNull(b.knowledge(app("k4"), "", withLinks = false))
    }

    @Test fun totalBudgetTrimsLowPriorityLayersFirst() {
        // 合計 4000 トークン: L5 が最初に 2250 個を取り除き (3000 文字、切り取られない)、L2 が残りの 1750 個を切り取ります。スキルインデックスはスキルを見つけるための唯一の入り口であり、割り当てが使い果たされると割り当て全体が与えられます
        val b = ContextBuilder(ContextBudget(total = 4_000))
        val s = b.start("t", memory = "忆".repeat(3_000), knowledge = listOf(app("a", body = "知".repeat(3_000))),
            skillsIndex = "索".repeat(800))
        val kinds = s.messages.map { ContextBuilder.kindOf(it) }
        assertEquals(listOf(ContextKind.Memory, null, ContextKind.AppKnowledge, ContextKind.SkillsIndex), kinds)
        val clipped = TokenEstimate.of(ContextBuilder.bodyOf(s.messages[2])!!)
        assertTrue("$clipped", clipped in 1_745..1_750)
        assertEquals(800, ContextBuilder.bodyOf(s.messages[3])!!.length)
        // L5 総額を超えても損なし
        val big = ContextBuilder(ContextBudget(total = 100)).start("t", priorTurns = "前".repeat(500), memory = "忆".repeat(500))
        assertEquals(listOf(ContextKind.PriorTurns, ContextKind.Memory, null), big.messages.map { ContextBuilder.kindOf(it) })
    }

    @Test fun knowledgeOncePerEntryAndAtMostThreePerTask() {
        val b = ContextBuilder()
        val s = b.start("t", knowledge = listOf(app("a"), app("b")))
        assertEquals(2, s.knowledge.size)
        assertNull(b.knowledge(app("a"), "", false))
        assertNotNull(b.knowledge(app("c"), "", false))
        assertNull(b.knowledge(app("d"), "", false))
        assertNull(b.knowledge(app("e", body = "x").copy(body = null), "", false))
    }

    @Test fun linksOnlyWhenAskedAndNeverClipped() {
        val e = app("a", body = "字".repeat(9_000), links = listOf(Link("扫一扫", "someapp://scan", "p.a")))
        val on = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = true)!!)!!
        assertTrue(on.endsWith("- 扫一扫: someapp://scan (package p.a)"))
        assertTrue(ContextBuilder.LINKS_HEADER in on && KNOWLEDGE_TRUNCATED_HINT in on)
        val off = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = false)!!)!!
        assertFalse("someapp://" in off)
    }

    @Test fun runtimeStateFormats() {
        val b = ContextBuilder(zone = utc)
        val t0 = 1_793_000_000_000L // 2026-10-26 07:33 UTC、月曜日
        fun body(place: Place?, label: String? = null, mode: String? = null, step: Int = 3) =
            ContextBuilder.bodyOf(Message(Role.User, listOf(b.runtimeState(place, label, mode, step, 60, t0)), Author.Mika))
        assertEquals("Current place: no screen or page read yet\nStep: 3 of at most 60\nLocal time: 2026-10-26 07:33 Monday\nWrite to the user in Japanese.", body(null))
        assertEquals("Current place: app 铁路12306 (com.MobileTicket)", body(Place.app("com.MobileTicket"), "铁路12306")!!.lines()[0])
        assertEquals("Current place: app com.x", body(Place.app("com.x"))!!.lines()[0])
        assertEquals("Current place: website www.12306.cn (mobile version)", body(Place.site("www.12306.cn"), mode = "mobile")!!.lines()[0])
        assertEquals("Current place: website a.com", body(Place.site("a.com"))!!.lines()[0])
        assertEquals("Step: used up (limit 60 steps)", body(null, step = 61)!!.lines()[1])
        assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(b.runtimeState(null, null, null, 1, 60, t0).text))
    }

    // ユーザーの最新の文の先頭が生成ポイントに配置されます。モデルはこの文の言語でユーザーに書き込みます。他の言語の場合でも、読み取られた内容は変更されません。
    @Test fun runtimeStateQuotesUserWordsForReplyLanguage() {
        val b = ContextBuilder(zone = utc)
        fun last(words: String?) = ContextBuilder.bodyOf(Message(Role.User, listOf(b.runtimeState(null, null, null, 1, 60, 0, words)), Author.Mika))!!.lines().last()
        assertEquals("Write to the user in Japanese. Their latest message: \"Open Settings and\ntell me the version\"".replace("\n", " "),
            last("  Open Settings and\n tell me the version "))
        val long = last("Open the Settings app and tell me which Android version this phone is running and how much storage is used.")
        assertTrue(long, long.endsWith("Open the Settings app and tell me which Android version this…\""))
        assertEquals("Write to the user in Japanese.", last(null))
        assertEquals("Write to the user in Japanese.", last("   "))
    }
}

/**
 * 実行時ステータス: ステップごとに 1 つの段落。このステップの最後のメッセージの末尾に接続され、追加されるだけです。送信されたメッセージは変更されません。
 * したがって、ステップ N のリクエストは、ステップ N+1 のリクエストの先頭になります (クロップとフォールディングのギアの移動を除く)。
 */
class RuntimeStateHistoryTest {
    private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    /** リクエストはメッセージごとにシリアル化されます。接頭辞の関係は文字列によって判断されます。 */
    private fun wire(req: List<Message>) = req.joinToString("\n") { it.toString() }

    private fun run(flags: SpeedFlags, vararg script: ModelReply, web: com.wangzan101.mika.core.cap.web.FakeWebPort? = null): FakeModel {
        val m = FakeModel(*script, finish)
        runBlocking {
            AgentLoop(m, toolDispatcher(FakeScreen(), web = web), "BASE", maxSteps = 20, clock = { 0L },
                zone = ZoneId.of("UTC"), flags = flags).run("t")
        }
        return m
    }

    private fun assertAppendOnly(m: FakeModel) {
        m.requests.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("request ${i + 1} is not a prefix of request ${i + 2}", wire(b).startsWith(wire(a)))
        }
        m.requests.forEachIndexed { i, req ->
            // 各ステップの段落はまだ存在します。最新の段落は最後のメッセージの最後にあります
            assertEquals(i + 1, req.runtimeStates().size)
            assertTrue(req.runtimeStates().last().contains("Step: ${i + 1} of at most 20"))
            val tail = req.last().parts.last() as Part.Text
            assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(tail.text))
        }
    }

    private val waits = (1..8).map { tools(Part.ToolCall("w$it", "wait", """{"seconds":${it / 10.0}}""")) }.toTypedArray()

    @Test fun requestsAreAppendOnlyInPlainMode() = assertAppendOnly(run(SpeedFlags.OFF, *waits))

    // は、ラダー位置内で画面を読み取るときにプレフィックスを変更しません (5 回以内)
    @Test fun observationsWithinBandAreAppendOnly() =
        assertAppendOnly(run(SpeedFlags.OFF, *(1..5).map { tools(Part.ToolCall("o$it", "phone_observe", """{"n":$it}""")) }.toTypedArray()))

    @Test fun stateRidesOnTheStepsOwnMessage() {
        val m = run(SpeedFlags.OFF, tools(Part.ToolCall("o1", "phone_observe", "{}")))
        // ステップ 1 はタスクの元の文に接続されています (他のコンテキストはありません)。ステップ 2 は、新しいツールを開始するのではなく、観察のツール結果に接続されます。
        val first = m.requests[0]
        assertEquals(1, first.size)
        assertEquals("t", (first[0].parts[0] as Part.Text).text)
        val second = m.requests[1]
        assertEquals(3, second.size)
        assertEquals(Role.Tool, second.last().role)
        assertTrue(second.last().parts.first() is Part.ToolResult)
        assertTrue(second.runtimeStates().last().startsWith("Current place: app "))
    }

    @Test fun webSurfaceAlwaysShowsCurrentMode() {
        val web = com.wangzan101.mika.core.cap.web.FakeWebPort()
        val go = tools(Part.ToolCall("n1", "web_navigate", """{"url":"https://shop.example.com/a"}"""))
        val desktop = run(SpeedFlags.OFF, go, web = web)
        assertTrue(desktop.requests[1].runtimeStates().last(), desktop.requests[1].runtimeStates().last().startsWith("Current place: website shop.example.com (desktop version)"))
        // のバージョンは、現在実際に使用されているブラウザに依存します。このタスクでは web_site_mode が調整されているかどうかは関係ありません。
        web.mode = "mobile"
        val mobile = run(SpeedFlags.OFF, go, web = web)
        assertTrue(mobile.requests[1].runtimeStates().last().startsWith("Current place: website shop.example.com (mobile version)"))
    }

    @Test fun foldKeepsPinnedContextWithoutItsStaleState() {
        val b = ContextBuilder(zone = ZoneId.of("UTC"))
        fun round(i: Int) = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_tap", """{"target":"按钮$i"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$i", """{"ok":true,"changed":true,"pad":"${"x".repeat(400)}"}""", false),
                b.runtimeState(Place.app("com.x"), null, null, i + 1, 60, 0)), Author.Mika),
        )
        val knowledge = b.knowledge(KnowledgeEntry("a", "甲", Surface.Android, listOf("p.a"), emptyList(), emptyList(), null, "", "正文"), "", false)!!
        val withState = knowledge.copy(parts = knowledge.parts + b.runtimeState(Place.app("p.a"), null, null, 4, 60, 0))
        val h = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) + (1..3).flatMap(::round) + withState + (4..30).flatMap(::round)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(knowledge in c.messages)
        // 残りの状態は折りたたまれていない状態です。
        assertEquals(30 - c.foldedSteps, c.messages.runtimeStates().size)
    }

    @Test fun systemPromptIsByteIdenticalWithinTask() = runBlocking {
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("w1", "wait", """{"seconds":0.1}""")), finish)
        val systems = mutableListOf<String>()
        val model = object : com.wangzan101.mika.core.model.ChatModel by m {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<com.wangzan101.mika.core.model.ToolSpec>): ModelReply {
                systems += system
                return m.complete(system, messages, tools)
            }
        }
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", maxSteps = 10, clock = { 0L }, zone = ZoneId.of("UTC")).run("t")
        assertEquals(3, systems.size)
        // 初めて画面を読んで、携帯電話のグループを広げたとき、システム プロンプトの単語はその後 1 回だけ変更され、その後は一字一句同じでした。
        assertEquals(2, systems.distinct().size)
        assertEquals(systems[1], systems[2])
        assertEquals("Current place: no screen or page read yet", m.requests[0].runtimeStates().single().lines()[0])
    }
}

/** すべてのスキルが開始時にリストされています。関連するスキルを持つアプリがフロントデスクに到達すると、スキルインデックスは追加されなくなり、開始コピーは変更されません。 */
class ForegroundAddsNoSkillsIndexTest {
    @Test fun foregroundAppAddsNoSkillLines() = runBlocking {
        val catalog = com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog()
        val store = com.wangzan101.mika.core.knowledge.KnowledgeStore(com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.entries())
        val screen = object : com.wangzan101.mika.core.uistate.ScreenPort by FakeScreen() {
            override suspend fun snapshot() = com.wangzan101.mika.core.uistate.ScreenSnapshot("s", "com.tencent.mm", emptyList(), 1080, 2400)
        }
        fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("o2", "phone_observe", "{}")),
            com.wangzan101.mika.core.testing.answerReply("ok"))
        AgentLoop(m, toolDispatcher(screen, skills = catalog), "B", knowledge = store, skills = catalog).run("看看")
        val first = m.requests.first().filter { ContextBuilder.kindOf(it) == ContextKind.SkillsIndex }
        val last = m.requests.last().filter { ContextBuilder.kindOf(it) == ContextKind.SkillsIndex }
        assertEquals(1, first.size)
        assertEquals(first, last)
        // 入力時にWeChatのナレッジテキストが追加されたままです。
        assertTrue(m.requests.last().any { ContextBuilder.kindOf(it) == ContextKind.AppKnowledge })
        assertTrue(ContextBuilder.bodyOf(first.single())!!.contains("- skill:messaging("))
    }
}
