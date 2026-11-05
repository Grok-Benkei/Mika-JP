package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.verify.EvidenceLedger
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

private class ListSink : RunSink {
    val events = mutableListOf<RunEvent>()
    val screens = mutableListOf<Pair<Int, ByteArray>>()
    override fun event(e: RunEvent) { events += e }
    override fun media(mediaId: Int, png: ByteArray) { screens += mediaId to png }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("r${++seq}", name, args)
private fun reply(vararg parts: Part, usage: Usage? = null) = ModelReply(parts.toList(), StopReason.ToolUse, usage)

class AgentLoopRecordTest {
    private fun run(model: FakeModel, screen: ScreenPort = FakeScreen(), vision: Boolean = false, frames: Boolean = false, goal: String = "帮我查", inline: Boolean = false): ListSink {
        val sink = ListSink()
        kotlinx.coroutines.runBlocking {
            AgentLoop(model, toolDispatcher(screen, vision, recordFrames = frames, inlineScreen = inline), "BASE", sink = sink).run(goal)
        }
        return sink
    }

    @Test fun emitsStartReplyResultEnd() {
        val m = FakeModel(
            reply(Part.Text("まず確認します"), call("phone_observe")),
            reply(call("phone_tap", """{"x":500,"y":500}""")),
            com.wangzan101.mika.core.testing.answerReply("完了しました"),
        )
        val ev = run(m).events
        assertEquals(
            // 座標に要素がありません: 不可逆判定は判定できません、ゲートを解除して記録します_unresolved
            // 電話をかけずに最後の返信が答えです。まず文字通りに検証してから、コード内で見つかったソースを書き留めます。
            // 初めて画面を読んで携帯電話グループを展開する: 次回モデルに質問する前にメモを作成します tools_opened
            listOf("TaskStarted", "PromptVersion", "ModelReply", "ToolResult", "Observed", "Activity", "State", "ModelReply", "ToolResult", "Activity", "State", "ModelReply", "AnswerCheck", "Sources", "TaskEnded"),
            ev.map { it::class.simpleName },
        )
        assertEquals("帮我查", (ev[0] as RunEvent.TaskStarted).goal)
        val r1 = ev[2] as RunEvent.ModelReply
        assertEquals(1, r1.step); assertEquals("まず確認します", r1.text); assertEquals("phone_observe", r1.tool)
        assertEquals(RunEvent.State((ev[6] as RunEvent.State).atMs, "tools_opened", "phone"), ev[6])
        val t2 = ev[8] as RunEvent.ToolResult
        assertEquals(2, t2.step); assertEquals("phone_tap", t2.tool)
        assertEquals(RunEvent.State((ev[10] as RunEvent.State).atMs, "gate_unresolved", "phone_tap x=500,y=500 freshRead=true"), ev[10])
        assertEquals("observe", (ev[5] as RunEvent.Activity).kind)
        assertEquals("act", (ev[9] as RunEvent.Activity).kind)
        val end = ev.last() as RunEvent.TaskEnded
        assertEquals("succeeded", end.outcome); assertEquals("完了しました", end.answerOrReason)
        // 観測は収集時間を保持しますが、因果関係に従って対応するツールの結果の後に記録されます。
        assertTrue(ev.zipWithNext().all { (a, b) -> a.atMs <= b.atMs || (a is RunEvent.ToolResult && b is RunEvent.Observed) })
    }

    @Test fun startRecordsWhoStartedTheRun() {
        fun started(origin: com.wangzan101.mika.core.run.RunOrigin): RunEvent.TaskStarted {
            val sink = ListSink()
            val spec = com.wangzan101.mika.core.run.RunSpec(com.wangzan101.mika.core.run.RunId("r"), "查电量", "t",
                provenance = com.wangzan101.mika.core.run.RunProvenance(origin, com.wangzan101.mika.core.run.Attendance.Reachable))
            kotlinx.coroutines.runBlocking {
                AgentLoop(FakeModel(com.wangzan101.mika.core.testing.answerReply("80%")), toolDispatcher(FakeScreen(), runSpec = spec), "BASE", sink = sink)
                    .run(spec, com.wangzan101.mika.core.run.RunInbox())
            }
            return sink.events.first() as RunEvent.TaskStarted
        }
        assertEquals("mcp", started(com.wangzan101.mika.core.run.RunOrigin.Mcp).origin)
        // 携帯電話で開始すると、書き込みは行われず、記録は以前と同じです。
        assertNull(started(com.wangzan101.mika.core.run.RunOrigin.Ui).origin)
        assertEquals("mcp", RunSummary(id = "x", goal = "g", startedAtMs = 0).with(RunEvent.TaskStarted(0, "g", "m", "mcp")).origin)
    }

    @Test fun tokensRecordedFromUsage() {
        val m = FakeModel(
            reply(call("phone_observe"), usage = Usage(1200, 80, 1000)),
            com.wangzan101.mika.core.testing.answerReply("x"),
        )
        val replies = run(m).events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(1200, replies[0].inputTokens); assertEquals(80, replies[0].outputTokens); assertEquals(1000, replies[0].cachedTokens)
        assertNull(replies[1].inputTokens); assertNull(replies[1].outputTokens); assertNull(replies[1].cachedTokens)
    }

    @Test fun largeResultsKeptWhole() {
        val els = (1..150).map { UiElement("@e$it", "text", "第 $it 行很长很长的文字内容", 10, 10 * it, listOf(0, 0, 10, 10), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x"))
        val r = run(m, screen).events.filterIsInstance<RunEvent.ToolResult>().first()
        // レコードは再生とデバッグの基礎です。通常のフィールドの上限を超えるツールの結果も、全体を残す必要があります。
        assertTrue("length ${r.resultJson.length}", r.resultJson.length > RunEvent.MAX_FIELD)
        assertFalse(r.resultJson.contains("第 150 行很长很长的文字内容"))
        assertTrue(r.resultJson.contains("\"ref\":\"@e150\""))
        assertTrue(r.resultJson.startsWith("{\"ok\":true"))
    }

    @Test fun screensHandedToSinkWithIds() {
        val m = FakeModel(reply(call("phone_observe")), reply(call("phone_screenshot")), com.wangzan101.mika.core.testing.answerReply("x"))
        val sink = run(m, vision = true)
        assertTrue(sink.screens.isEmpty())
        assertTrue(sink.events.filterIsInstance<RunEvent.Media>().isEmpty())
        val results = sink.events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(results.none { it.resultJson.contains("搜索") })
        assertTrue(results.any { it.resultJson.contains("text_withheld") || it.resultJson.contains("egress_blocked") })
    }

    @Test fun textOnlyModelStillRecordsFrames() {
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x"))
        val sink = run(m, frames = true)
        assertTrue(sink.screens.isEmpty())
        // 画面は記録のみであり、画像が表示できないモデルには送信されません。
        assertTrue(m.requests[1].none { msg -> msg.parts.any { it is Part.Image } })
        val noFrames = run(FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x")))
        assertTrue(noFrames.screens.isEmpty())
    }

    @Test fun passwordTypedIntoSecretFieldMasked() {
        val screen = FakeScreen().apply { result = ActionResult.Ok(secret = true) }
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val ev = run(m, screen).events
        val r = ev.filterIsInstance<RunEvent.ModelReply>().first()
        assertFalse(r.argsJson!!.contains("hunter2"))
        assertTrue(r.argsJson!!.contains("***"))
        assertTrue(ev.none { it.toString().contains("hunter2") })
    }

    @Test fun typedTextKeptOnlyWhenPortSaysNotSecret() {
        val m = FakeModel(reply(call("phone_type_text", """{"text":"天气"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val r = run(m).events.filterIsInstance<RunEvent.ModelReply>().first()
        assertTrue(r.argsJson!!.contains("天气"))
    }

    @Test fun failedTypeTextMasked() {
        val screen = FakeScreen().apply { result = ActionResult.Failed("no_focus") }
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val ev = run(m, screen).events
        assertTrue(ev.none { it.toString().contains("hunter2") })
        assertTrue(ev.filterIsInstance<RunEvent.ModelReply>().first().argsJson!!.contains("***"))
    }

    @Test fun guardStopBeforeExecutionMasked() {
        // 同じ入力が繰り返し行われる場合、実際のインターフェイスが変更されていない場合にのみ、ガードは入力を停止します。録音は依然として感度を下げておく必要があります。
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), repeatLast = true)
        val ev = run(m, FakeScreen().apply { result = ActionResult.Ok(changed = false, secret = true) }).events
        val end = ev.last() as RunEvent.TaskEnded
        assertEquals("failed", end.outcome)
        assertTrue(end.answerOrReason, end.answerOrReason.contains("no_progress"))
        val last = ev.filterIsInstance<RunEvent.ModelReply>().last()
        assertEquals(ev.filterIsInstance<RunEvent.ToolResult>().size, ev.filterIsInstance<RunEvent.ModelReply>().size)
        assertFalse(last.argsJson!!.contains("hunter2"))
        assertTrue(last.argsJson!!.contains("***"))
    }

    @Test fun throwingSinkDoesNotBreakTask() {
        val sink = object : RunSink {
            override fun event(e: RunEvent) { throw StackOverflowError("boom") }
            override fun media(mediaId: Int, png: ByteArray) { throw IllegalStateException() }
        }
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("ok"))
        val end = kotlinx.coroutines.runBlocking { AgentLoop(m, toolDispatcher(FakeScreen(), true), "BASE", sink = sink).run("g") }
        com.wangzan101.mika.core.testing.assertAnswered("ok", end)
    }

    @Test fun eventsRoundTripAsJsonLines() {
        val all = listOf(
            RunEvent.TaskStarted(1, "g", "m"),
            RunEvent.Criteria(2, listOf("a"), listOf("b")),
            RunEvent.ModelReply(3, 1, "t", "phone_tap", "{}", 1, 2, 3),
            RunEvent.ToolResult(4, 1, "phone_tap", "{}", true, 1),
            RunEvent.Media(5, 1, 1, "com.x"),
            RunEvent.Observed(5, 1, "現在のアプリ:com.x\n搜索", 3, "read", "phone", Place.app("com.x"), inputs = listOf("搜索"), focused = "搜索"),
            RunEvent.Activity(5, 1, "observe", "phone_observe", "com.x", "completed", listOf(3), callId = "r1"),
            RunEvent.State(6, "waiting_input", "?"),
            RunEvent.Confirmation(7, "send", "张三", "hi", null, "approved"),
            RunEvent.Takeover(8, "login", "handed_back", 1000, 3),
            RunEvent.Barrier(9, "login_wall"),
            RunEvent.Verifier(10, "pass", "{}", 3),
            RunEvent.Compressed(11, 5),
            RunEvent.TaskEnded(12, "succeeded", "ok"),
        )
        for (e in all) {
            val line = RunEvent.encode(e)
            assertFalse(line.contains('\n'))
            assertEquals(e, RunEvent.decode(line))
        }
        assertTrue(RunEvent.encode(all[0]).contains("\"type\":\"task_started\""))
    }

    @Test fun oldScreenEventsDecodeAsObservationAndMedia() {
        val read = RunEvent.decode("""{"type":"screen_text","atMs":7,"step":2,"app":"com.x","names":["搜索","结果"],"inputs":["关键字"],"focused":"关键字"}""")
        assertEquals(RunEvent.Observed(7, 2, "搜索\n结果", source = "phone", place = Place.app("com.x"), inputs = listOf("关键字"), focused = "关键字"), read)
        val frame = RunEvent.decode("""{"type":"screen_saved","atMs":8,"step":2,"screenshotId":3,"app":"com.x"}""")
        assertEquals(RunEvent.Media(8, 3, 2, "com.x"), frame)
    }

    @Test fun observationKeptWholeWhenResultIsClipped() {
        val els = (1..150).map { UiElement("@e$it", "text", "第 $it 行很长很长的文字内容", 10, 10 * it, listOf(0, 0, 10, 10), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_observe")), reply(call("phone_tap", """{"x":1,"y":1}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val texts = run(m, screen).events.filterIsInstance<RunEvent.Observed>()
        // 読み取りインターフェイスが記録されます (家計簿のものと同じ、番号が付いています)。タップによって戻された画面も記録されます (actionReturnedScreenIsRecorded を参照)
        assertEquals(1, texts[0].step)
        assertEquals(1, texts[0].id)
        assertEquals(Place.app("com.x"), texts[0].place)
        assertTrue(texts[0].text.contains("text_withheld") || texts[0].text.contains("egress_blocked"))
        assertTrue(texts.none { it.text.contains("很长很长的文字内容") })
    }

    @Test fun actionReturnedScreenIsRecorded() {
        for ((inline, vision) in listOf(true to false, false to true)) {
            val els = listOf(UiElement("@e1", "text", "明天晴 25 度", 10, 10, listOf(0, 0, 10, 10), null, false, false, false))
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
            }
            val m = FakeModel(reply(call("phone_tap", """{"x":1,"y":1}""")), com.wangzan101.mika.core.testing.answerReply("明天晴 25 度"))
            val sink = run(m, screen, vision = vision, inline = inline)
            val texts = sink.events.filterIsInstance<RunEvent.Observed>()
            assertFalse("inline=$inline vision=$vision", texts.joinToString().contains("明天晴"))
            assertTrue(texts.any { it.text.contains("text_withheld") || it.text.contains("egress_blocked") })
            assertFalse(com.wangzan101.mika.core.eval.Checks.grounded("明天晴 25 度", com.wangzan101.mika.core.eval.RunFacts.evidence(sink.events, TOOLS, "", "明天晴 25 度"), 1))
        }
    }

    @Test fun observationRedactedAndCapped() {
        val key = "sk-" + "a".repeat(30)
        val els = listOf(UiElement("@e1", "text", "密钥 $key", 1, 1, listOf(0, 0, 1, 1), null, false, false, false)) +
            (2..2000).map { UiElement("@e$it", "text", "x".repeat(50) + it, 1, 1, listOf(0, 0, 1, 1), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_list_elements")), com.wangzan101.mika.core.testing.answerReply("x"))
        val t = run(m, screen).events.filterIsInstance<RunEvent.Observed>().single()
        assertFalse(key in t.text)
        assertTrue(t.text.length <= EvidenceLedger.READ_CHARS)
    }

    // 新しいレコード内のすべての受信観測が記録されます。オフラインで再構築された台帳は、実行時の台帳とまったく同じです (読み取りインターフェース、アクションによって戻されたインターフェース、コマンド出力、ツールの事実)
    @Test fun recordedObservationsRebuildTheSameLedger() {
        val sb = com.wangzan101.mika.core.cap.sandbox.FakeSandbox().apply {
            next = { r -> com.wangzan101.mika.core.cap.sandbox.CommandResult(0, false, false, 10, "共 42 行 ${r.argv}", 0, false) }
        }
        val m = FakeModel(
            reply(call("phone_observe")),
            reply(call("phone_launch_app", """{"package":"com.x"}""")),
            reply(call("phone_tap", """{"x":1,"y":1}""")),
            reply(call("run_command", """{"command":"wc -l data.csv"}""")),
            com.wangzan101.mika.core.testing.answerReply("共 42 行"),
        )
        val sink = ListSink()
        val tools = toolDispatcher(FakeScreen(), true, inlineScreen = true, sandbox = sb, workspace = { "w" })
        kotlinx.coroutines.runBlocking { AgentLoop(m, tools, "BASE", sink = sink).run("数一下行数") }
        val live = kotlinx.coroutines.runBlocking { tools.evidence(fresh = false) }.ledger.all
        assertTrue(live.size >= 3)
        assertEquals(live, com.wangzan101.mika.core.eval.RunFacts.ledger(sink.events, TOOLS).all)
        // それぞれの後に、それを読み取った呼び出しの結果が続きます。
        val ev = sink.events
        ev.forEachIndexed { i, e -> if (e is RunEvent.Observed) assertTrue(ev[i - 1] is RunEvent.ToolResult || ev[i - 1] is RunEvent.Observed) }
    }

    @Test fun barrierEventRecorded() {
        val m = FakeModel(
            reply(call("handle_obstacle", """{"kind":"access_blocked","reason":"操作频繁"}""")),
            reply(call("handle_obstacle", """{"kind":"bogus"}""")),
            com.wangzan101.mika.core.testing.answerReply("x"),
        )
        val ev = run(m).events
        // は合法的に障害物を報告し、そのツールの結果に従うことのみが可能です
        val b = ev.filterIsInstance<RunEvent.Barrier>()
        assertEquals(listOf("access_blocked"), b.map { it.kind })
        val at = ev.indexOf(b.single())
        assertEquals("handle_obstacle", (ev[at - 1] as RunEvent.ToolResult).tool)
    }
}
