package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class ContextCompressorTest {
    private var seq = 0
    private val pad = "x".repeat(400)
    private val head = listOf(
        Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika),
        Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "记忆", "1. 喜欢摄氏度"))), Author.Mika),
    )

    private fun round(name: String, args: String = "{}", result: String = """{"ok":true,"changed":true,"pad":"$pad"}"""): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, name, args)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, result, isError = false)), Author.Mika),
        )
    }

    private fun taps(n: Int) = (1..n).flatMap { round("phone_tap", """{"target":"按钮$it"}""") }

    @Test fun modelOverrideReachesTheCallerAndMovesTheFirstCompaction() = runTest {
        val history = head + (1..40).flatMap { round("phone_tap", result = """{"pad":"${"x".repeat(1_360)}"}""") }
        assertTrue(uncalibrated(history) in 16_001..18_000)
        suspend fun sent(trigger: Int?): List<Message> {
            val transport = FakeModel(answerReply("ok"), contextTokens = 32_000)
            val model = object : ChatModel by transport {
                override val capabilities = transport.capabilities.copy(compactionTriggerTokens = trigger)
            }
            val caller = ModelCaller(model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
            caller.reset("")
            caller.ask(history, emptyList(), step = 1)
            return transport.requests.single()
        }
        assertEquals(history, sent(null))
        assertTrue(uncalibrated(sent(16_000)) < uncalibrated(history))
    }

    @Test fun overflowUsesTheTargetForTheSameModelOverride() {
        // 32K ウィンドウ、自動ターゲット 12K;手動 16K トリガーのターゲットは 10K です。
        val user = Message(Role.User, listOf(Part.Text("x".repeat(26_000))), Author.User)
        val history = listOf(user) + (1..20).flatMap {
            round("phone_tap", """{"target":"ok","note":"${"x".repeat(1_600)}"}""", "{}")
        }
        fun retrySize(trigger: Int?): Int {
            val compressor = ContextCompressor(TOOLS, triggerOverride = trigger)
            assertTrue(compressor.overflow(history, 32_000))
            return uncalibrated(compressor.current(history, 32_000))
        }
        assertTrue(retrySize(null) in 10_001..12_000)
        assertTrue(retrySize(16_000) <= 10_000)
    }
    private fun summary(ms: List<Message>) = ms.firstNotNullOfOrNull { m ->
        m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) }
    }
    private fun toolRounds(ms: List<Message>) = ms.count { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall } }

    @Test fun underBudgetUntouched() {
        val h = head + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 128_000)
        // はトリガーポイントを越えていません。一行も切らずにそのまま送信します。
        assertEquals(h, c.messages)
        assertEquals(0, c.foldedSteps)
    }

    /** トリガー ポイントはリクエスト ウィンドウ全体のすぐ下にあります。1 回押すと、最も軽いレベル (最後の 12 ラウンド トリップを残す) を使用して、ターゲットの下に戻ります。 */
    private fun justOver(h: List<Message>) = windowFor(uncalibrated(h)) - 1

    @Test fun foldsOldestKeepsRecent12() {
        val h = head + taps(40)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = justOver(h))
        assertEquals(28, c.foldedSteps)
        assertEquals(12, toolRounds(c.messages))
        // ミッションの原文と成功基準が最初にリストされ、次に概要が続き、最後に最後の 12 往復の原文が続きます。
        assertEquals(head, c.messages.take(2))
        assertTrue(summary(c.messages) == c.messages[2].plainText())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(c.messages[2]))
        assertEquals(h.takeLast(24), c.messages.drop(3))
        val s = summary(c.messages)!!
        assertTrue(s, "Step 1 phone_tap(按钮1) → applied" in s && "Step 28 phone_tap(按钮28) → applied" in s && "Step 29" !in s)
    }

    @Test fun appendOnlyBetweenCompactions() {
        val all = head + taps(120)
        fun h(n: Int) = all.take(head.size + 2 * n)
        val c = ContextCompressor(TOOLS)
        val ctx = 8000
        val outs = (1..120).map { c.compress(h(it), contextTokens = ctx) }
        val rewrites = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.messages != p.second.messages.take(p.first.messages.size) }.map { it.index + 2 }
        val folds = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.foldedSteps != p.second.foldedSteps }.map { it.index + 2 }
        // 各書き換えは圧縮 (フォールド数が変更) であり、2 つの圧縮間では追加のみが行われます。圧迫回数はステップ数よりはるかに少ない
        assertEquals(folds, rewrites)
        assertTrue("$rewrites", rewrites.isNotEmpty() && rewrites.size <= 10)
        rewrites.zipWithNext().forEach { (a, b) -> assertTrue("$rewrites", b - a > 1) }
        // によって発行されたリクエスト全体は、常に利用可能なウィンドウ内にあります (出力予約を除く)。
        outs.forEach { assertTrue(uncalibrated(it.messages) <= ContextLimits.of(ctx).usable) }
    }

    @Test fun oversizedNewestResultIsShrunkToFitTheWindow() {
        val huge = round("run_command", """{"command":"cat big.log"}""",
            """{"ok":true,"exitCode":0,"output":"${"日志".repeat(40_000)}"}""")
        val h = head + taps(3) + huge
        val ctx = 16_000
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = ctx, fixedOverhead = 1000)
        assertTrue("${uncalibrated(c.messages, 1000)}", uncalibrated(c.messages, 1000) <= ContextLimits.of(ctx).usable)
        val last = c.messages.last().parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(last.content, HistoryPruner.OMITTED_READ in last.content && "\"exitCode\":0" in last.content)
        val calls = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
        val results = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
        assertEquals(calls, results)
    }

    @Test fun downwardCalibrationCannotLetOneHugeScreenBypassCompaction() = runTest {
        val first = ModelReply(
            listOf(Part.ToolCall("open_settings", "phone_launch_app", """{"package":"com.android.settings"}""")),
            StopReason.ToolUse, Usage(inputTokens = 972, outputTokens = 20),
        )
        val transport = FakeModel(first, answerReply("done"), contextTokens = 32_768)
        val caller = ModelCaller(transport, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("")
        // 文字数とトークン数は異なります。約8千文字に対して972トークンと報告する模擬サービスでも、推定係数を下限0.5へ下げません。
        val opening = listOf(Message(Role.User, listOf(Part.Text("x".repeat(8_000))), Author.User))
        caller.ask(opening, emptyList(), step = 1)
        val reply = Message(Role.Assistant, first.parts, Author.Mika)
        caller.noteReply(reply, transport, first.usage)

        val elements = (1..400).joinToString(",") { i ->
            """{"ref":"@e$i","role":"TextView","name":"Setting item $i","center":{"x":500,"y":100},"bounds":[0,0,1080,120],"resourceId":"android:id/title"}"""
        }
        val nodes = (1..400).joinToString(",") { i ->
            """{"role":"TextView","ref":"@e$i","text":"Setting item $i","resourceId":"android:id/title","bounds":[0,0,1080,120]}"""
        }
        val screen = """{"ok":true,"snapshotId":"settings","elements":[$elements],"tree":[{"id":1,"type":"application","nodes":[$nodes]}]}"""
        assertTrue("test fixture chars=${screen.length}", screen.length > 90_000)
        assertTrue(
            "test fixture must reproduce the old trigger bypass",
            972 + 20 + UsageMeter.estimate(TokenEstimate.of(screen), 0.5) < ContextLimits.of(32_768).trigger,
        )
        val history = opening + reply + Message(Role.Tool, listOf(Part.ToolResult("open_settings", screen, false)), Author.Mika)
        caller.ask(history, emptyList(), step = 2)

        val sent = transport.requests[1]
        val result = sent.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single()
        assertTrue("a single new screen must be capped before sending", HistoryPruner.OMITTED_READ in result.content)
        assertTrue("raw JSON must not be resent at ${screen.length} chars", "android:id/title" !in result.content)
        assertTrue(uncalibrated(sent) <= ContextLimits.of(32_768).usable)
    }

    @Test fun overheadHeavySmallWindowNeverExceedsTheWindow() {
        val all = head + taps(40)
        val ctx = 4000
        val overhead = 1800
        val c = ContextCompressor(TOOLS)
        // 固定オーバーヘッドがトリガー ポイントをほぼ占めています。各圧縮後にヒステリシスがあり、すべてのステップで圧縮されるわけではありません。送信されたリクエスト全体に常に対応できます
        var rewrites = 0
        for (n in 1..40) {
            val out = c.compress(all.take(head.size + 2 * n), contextTokens = ctx, fixedOverhead = overhead)
            if (out.rewritten) rewrites++
            assertTrue("$n: ${uncalibrated(out.messages, overhead)}", uncalibrated(out.messages, overhead) <= ctx)
        }
        assertTrue("$rewrites", rewrites in 1..20)
    }

    @Test fun foldedObservationsKeepScreenshotId() {
        val obs = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","screenshotId":7,"elements":[],"pad":"$pad"}""")
        val dump = round("phone_list_elements", "{}", """{"ok":true,"snapshotId":"s2","screenshotId":8,"pad":"$pad"}""")
        val h = head + obs + dump + taps(20)
        val s = summary(ContextCompressor(TOOLS).compress(h, contextTokens = 1000).messages)!!
        assertTrue(s, "Step 1 phone_observe #7 → applied" in s && "Step 2 phone_list_elements #8 → applied" in s)
    }

    @Test fun pinnedMessagesKept() {
        val knowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.AppKnowledge, "天气 App", "…"))), Author.Mika)
        val inputRound = round("ask_user", """{"prompt":"哪个城市?"}""", """{"ok":true}""") +
            Message(Role.User, listOf(Part.Text("北京")), Author.Mika)
        // のフォローアップの質問に対する回答も、ツールの結果として直接使用される場合があります。
        val inputAsResult = round("ask_user", """{"prompt":"哪天?"}""", """{"ok":true,"answer":"明天"}""")
        val confirmRound = round("request_confirmation", """{"action":"send"}""", """{"ok":true,"approved":true}""")
        val takeoverRound = round("hand_to_user", """{"reason":"login","message":"请登录"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(2) + knowledge + inputRound + taps(2) + takeoverRound + inputAsResult + confirmRound + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        for (m in head + knowledge + inputRound + takeoverRound + inputAsResult + confirmRound) assertTrue("$m", m in c.messages)
        assertTrue(c.foldedSteps > 0)
        val summaries = c.messages.mapNotNull { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }
        assertTrue(summaries.flatMap { it.lines() }.none { "request_" in it || "hand_to_user" in it })
        // 保存された順序は変更されません。
        val kept = c.messages.filter { it in h }
        assertEquals(h.filter { it in kept }, kept)
    }

    @Test fun domainKnowledgeAfterWebRoundStaysPinned() {
        val nav = round("web_navigate", """{"url":"https://shop.example.com/"}""",
            """{"ok":true,"surface":"web","tabId":"t1","url":"https://shop.example.com/","pad":"$pad"}""")
        val siteKnowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.SiteKnowledge, "好店", "网页做法…"))), Author.Mika)
        val h = head + taps(2) + nav + siteKnowledge + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(siteKnowledge in c.messages)
        assertTrue("該当の往復が圧縮されています", nav.first() !in c.messages)
    }

    @Test fun pairingIntactAfterFold() {
        val skipped = Part.ToolCall("p2", "phone_tap", "{}")
        val parallel = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("p1", "phone_observe", "{}"), skipped), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("p1", """{"ok":true,"elements":[]}""", false),
                Part.ToolResult("p2", """{"ok":false,"error":"parallel_tool_call_skipped"}""", true)), Author.Mika),
            Message(Role.User, listOf(Part.Text("Current phone screenshot"), Part.Image("aaa")), Author.Mika),
        )
        val h = head + taps(3) + parallel + taps(3) + parallel.map { m ->
            Message(m.role, m.parts.map { p -> when (p) {
                is Part.ToolCall -> p.copy(id = p.id + "b"); is Part.ToolResult -> p.copy(callId = p.callId + "b"); else -> p } }, Author.Mika)
        } + taps(12)
        for (aggressive in listOf(false, true)) {
            val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1000, aggressive = aggressive).messages
            val calls = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
            val results = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
            assertEquals(calls.sorted(), results.sorted())
            out.forEachIndexed { i, m ->
                m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
                    val prev = out.subList(0, i).last { it.role == Role.Assistant }
                    assertTrue(prev.parts.any { it is Part.ToolCall && it.id == r.callId })
                }
            }
        }
    }

    @Test fun aggressiveKeeps6() {
        val h = head + taps(20)
        // モデルは長すぎると言われています。どれだけ見積もっても、プレスされるでしょう。
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true)
        // 14 回割引、最新 6 回割引
        assertEquals(14, c.foldedSteps)
        assertEquals(6, toolRounds(c.messages))
        assertEquals(h.takeLast(12), c.messages.takeLast(12))
    }

    @Test fun summaryLineFormat() {
        val h = head +
            round("phone_tap", """{"ref":"@e3","snapshotId":"s1","target":"设置"}""", """{"ok":true,"changed":true}""") +
            round("phone_scroll", """{"direction":"down"}""", """{"ok":true,"changed":false}""") +
            round("phone_launch_app", """{"package":"com.x"}""", """{"ok":false,"error":"app_not_found","message":"…"}""") +
            round("phone_back") +
            round("phone_type_text", """{"text":"${"天气".repeat(30)}"}""", """{"ok":true}""") +
            listOf(Message(Role.Assistant, listOf(Part.Text("我想想")), Author.Mika), Message(Role.User, listOf(Part.Text("nudge")), Author.Mika)) +
            round("phone_observe", "{}", """{"ok":true,"elements":[]}""") +
            taps(12)
        val s = ContextCompressor(TOOLS).compress(h, contextTokens = 100).let { summary(it.messages)!! }
        assertEquals(
            listOf(
                ContextCompressor.HEADER,
                ContextCompressor.SUMMARY_REMINDER,
                "Step 1 phone_tap(设置) → applied",
                "Step 2 phone_scroll(down) → no change",
                "Step 3 phone_launch_app(com.x) → app_not_found",
                "Step 4 phone_back → applied",
                "Step 5 phone_type_text(${"天气".repeat(10)}…) → applied",
                "Step 7 phone_observe → applied",
            ),
            s.lines().take(8),
        )
    }

    @Test fun oneSummaryPerContiguousRun() {
        val takeover = round("hand_to_user", """{"reason":"login"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(3) + takeover + taps(2) + taps(12)
        // モデルは過度に長い旅行を報告します: 最新の 6 往復を維持します
        val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true).messages
        val texts = out.map { it.plainText() }
        // 2 つのエントリで始まり、最初の段落の概要、予約されたテイクオーバーのラウンドトリップ、2 番目の段落の概要、最後の 6 つのラウンドトリップ いつものように
        assertTrue(texts[2]!!.startsWith(ContextCompressor.HEADER) && "Step 3" in texts[2]!! && "Step 5" !in texts[2]!!)
        assertEquals(takeover, out.subList(3, 5))
        assertTrue(texts[5]!!.startsWith(ContextCompressor.HEADER))
        assertEquals(listOf(ContextCompressor.HEADER, ContextCompressor.SUMMARY_REMINDER) + listOf("Step 5 phone_tap(按钮1) → applied", "Step 6 phone_tap(按钮2) → applied") +
            (7..12).map { "Step $it phone_tap(按钮${it - 6}) → applied" },
            texts[5]!!.lines())
        assertEquals(2 + 1 + 2 + 1 + 12, out.size)
    }

    @Test fun resultOutcomes() {
        fun one(result: Part.ToolResult?): String {
            val id = "r${++seq}"
            val r = listOf(Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika)) +
                listOfNotNull(result?.let { Message(Role.Tool, listOf(it.copy(callId = id)), Author.Mika) })
            val h = head + r + taps(12)
            return summary(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages)!!.lines()[2].substringAfter(" → ")
        }
        assertEquals("no result", one(null))
        assertEquals("failed", one(Part.ToolResult("", """{"message":"boom"}""", isError = true)))
        assertEquals("ref_expired", one(Part.ToolResult("", """{"error":"ref_expired"}""", isError = true)))
        assertEquals("timeout", one(Part.ToolResult("", "timeout", isError = true)))
        assertEquals("failed", one(Part.ToolResult("", "出了点问题", isError = true)))
        assertEquals("applied", one(Part.ToolResult("", "done", isError = false)))
    }

    @Test fun fixedOverheadCountsAgainstBudget() {
        val h = head + taps(20)
        val context = windowFor(uncalibrated(h))
        assertEquals(0, ContextCompressor(TOOLS).compress(h, context).foldedSteps)
        assertTrue(ContextCompressor(TOOLS).compress(h, context, fixedOverhead = 100).foldedSteps > 0)
    }

    @Test fun keepsLatestFullReadAndDiffChain() {
        val full = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","elements":[{"ref":"@e1"}],"pad":"$pad"}""")
        val diffs = (2..3).flatMap { round("phone_back", "{}", """{"ok":true,"screen":{"snapshotId":"s$it","full":false,"added":[]},"pad":"$pad"}""") }
        val h = head + taps(3) + full + diffs + taps(20)
        val out = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000).messages
        val kept = out.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(kept.any { "\"s1\"" in it })
        // が折りたたまれる前の通常の往復 強力に圧縮すると
        assertTrue(summary(out)!!.contains("tap(按钮1)"))
        // が保持されない
        val hard = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000, aggressive = true).messages
        assertTrue(hard.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().none { "\"s1\"" in it.content })
    }

    // 12往復を残す圧縮では、残した画面だけで読める場合に古い全量画面を省略します。差分の基準が境界の前にある場合は、従来どおり保持します。
    @Test fun dropsEarlierFullReadWhenKeptScreensAreSelfContained() {
        val (folded, kept) = compressWithLatest("""{"snapshotId":"s9","full":true,"elements":[{"ref":"@e1"}]}""")
        assertTrue(kept.any { "\"s9\"" in it } && kept.none { "\"s1\"" in it })
        val (foldedWithBase, keptWithBase) = compressWithLatest("""{"snapshotId":"s9","base":"s3","full":false,"added":[]}""")
        assertTrue(keptWithBase.any { "\"s9\"" in it } && keptWithBase.any { "\"s1\"" in it })
        // 12往復を残す段階で止まり、境界前の30タップと差分、および不要になった全量画面を省略します。
        assertEquals(listOf(38, 37), listOf(folded, foldedWithBase))
    }

    // スマホとWebで別々の差分列を確認し、Webの全量画面だけではスマホの基準画面を省略しません。
    @Test fun keepsPhoneBaseWhenKeptRoundsStartWithAWebFullScreen() {
        val web = round("web_interact", "{}", """{"ok":true,"screen":{"snapshotId":"w1","full":true,"elements":[{"ref":"@w1"}]},"pad":"$pad"}""")
        val (folded, kept) = compressWithLatest("""{"snapshotId":"p2","base":"s3","full":false,"added":[]}""", before = web)
        assertTrue(kept.any { "\"w1\"" in it } && kept.any { "\"s1\"" in it })
        assertEquals(37, folded)
    }

    /** 境界前に30タップ・全量画面・差分2回・タップ5回を置き、12往復を残した圧縮結果を調べます。 */
    private fun compressWithLatest(screen: String, before: List<Message> = emptyList()): Pair<Int, List<String>> {
        val full = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","elements":[{"ref":"@e1"}],"pad":"$pad"}""")
        val diffs = (2..3).flatMap { round("phone_back", "{}", """{"ok":true,"screen":{"snapshotId":"s$it","base":"s${it - 1}","full":false,"added":[]},"pad":"$pad"}""") }
        val latest = round("phone_back", "{}", """{"ok":true,"screen":$screen,"pad":"$pad"}""")
        val h = head + taps(30) + full + diffs + taps(5) + before + latest + taps(11 - before.size / 2)
        val c = ContextCompressor(TOOLS, diffScreens = true).compress(h, contextTokens = 10_000)
        return c.foldedSteps to c.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
    }
}
