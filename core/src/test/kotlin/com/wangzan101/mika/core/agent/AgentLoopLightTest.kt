package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class Named(override val name: String, private val inner: FakeModel) : ChatModel by inner {
    val requests get() = inner.requests
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = inner.complete(system, messages, tools)
}

private class Events : RunSink {
    val list = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { list += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("l${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun finish() = com.wangzan101.mika.core.testing.answerReply("done")

/** の画面には「送信」ボタンが 1 つだけあります。 */
private class SendScreen(private val label: String = "发送", private val inner: FakeScreen = FakeScreen()) : com.wangzan101.mika.core.uistate.ScreenPort by inner {
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
        listOf(UiElement("@e1", "button", label, 500, 500, listOf(400, 450, 600, 550), null, true, false, false)), 1080, 2400)
}

class AgentLoopLightTest {
    private val on = SpeedFlags(lightSteps = true)

    private fun loop(main: ChatModel, light: ChatModel, sink: RunSink = RunSink.NONE, screen: com.wangzan101.mika.core.uistate.ScreenPort = FakeScreen()) =
        AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = on, router = ModelRouter(main, light))

    @Test fun cleanStepsGoToLightAndAreRecorded() = runTest {
        // ステップ 1 のメイン モデル。ステップ 2 のフロントエンド アプリはメイン モデルで 1 回だけ確認されています。ステップ 3 からの 2 つのステップで同じアプリ、エラーなし → 軽量
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        // ステップ 4 軽量モデルの結果を送信したい場合は、代わりにメイン モデルに問い合わせてください。
        assertEquals(2, light.requests.size)
        assertEquals(3, main.requests.size)
        val replies = sink.list.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(listOf("main", "main", "light", "light_rejected", "main"), replies.map { it.role })
        assertEquals(listOf("main", "main", "light", "light", "main"), replies.map { it.model })
    }

    // 呼ばずの返答は、主力モデル：軽量モデルのみが出す最終回答です。この場合は、メインモデルに問い合わせてください。
    @Test fun lightAnswerIsAskedAgainOfMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.testing.answerReply("LIGHT_ANSWER")))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "no_tool" })
        assertEquals(listOf("main", "main", "light_rejected", "main"), sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        // 再割り当て後、メインモデルで見る履歴には未実行の応答がありません。
        val asked = main.requests.last()
        assertTrue(asked.flatMap { it.parts }.filterIsInstance<Part.Text>().none { "LIGHT_ANSWER" in it.text })
        assertEquals(asked.size, light.requests.single().size)
    }

    @Test fun lightCoordinateAndLocatedTapsGoToMain() = runTest {
        for (args in listOf("""{"x":500,"y":500}""", """{"ref":"@v1"}""", """{"x":900,"y":900}""")) {
            val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不点"}"""))))
            val light = Named("light", FakeModel(tools(call("phone_tap", args))))
            val sink = Events()
            loop(main, light, sink, SendScreen()).run("t")
            assertTrue(args, sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
            assertTrue(args, sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
        }
    }

    @Test fun confirmationBlockedStepForcesMain() = runTest {
        // 2 番目のステップでは、メイン モデルの「注文の送信」のクリックが確認レベルによってブロックされました。2 つのステップの後、それがメイン モデルになり、5 番目のステップでは軽量モデルの番になります。
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
            tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        loop(main, light, sink, SendScreen("提交订单")).run("t")
        assertEquals(listOf("main", "main", "main", "main", "light", "light_rejected", "main"),
            sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        assertTrue((sink.list.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "phone_tap" }).resultJson.contains("confirmation_required"))
    }

    @Test fun permanentLightErrorTurnsRoutingOff() = runTest {
        val main = Named("main", FakeModel(*Array(6) { tools(call("phone_observe")) }, finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Auth()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(1, sink.list.filterIsInstance<RunEvent.ModelReply>().count { it.role == "light_rejected" })
    }

    @Test fun otherModelsThinkingAndOpaqueAreNotSentAcross() = runTest {
        val mine = Part.ToolCall("m1", "phone_observe", "{}", opaque = "sigM")
        val theirs = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sigL")
        val main = Named("main", FakeModel(ModelReply(listOf(Part.Thinking("tm"), mine), StopReason.ToolUse, null),
            tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(ModelReply(listOf(Part.Thinking("tl"), theirs), StopReason.ToolUse, null), finish()))
        loop(main, light).run("t")
        // ステップ 3: 軽量モデル;ステップ 4 では、結果を渡す必要があります。代わりにメイン モデルに問い合わせます。軽量モデルの思考データとプライベート データを削除すると、メイン モデルはそのまま残ります。
        val toMain = main.requests[2].filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine in toMain && Part.Thinking("tm") in toMain)
        assertTrue(theirs.copy(opaque = null) in toMain && Part.Thinking("tl") !in toMain)
        val toLight = light.requests.first().filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine.copy(opaque = null) in toLight && toLight.none { it is Part.Thinking })
    }

    @Test fun lightIrreversibleTapIsAskedAgainOfMain() = runTest {
        val tapSend = """{"ref":"@e1","snapshotId":"s1"}"""
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不发"}"""))))
        val light = Named("light", FakeModel(tools(call("phone_tap", tapSend))))
        val sink = Events()
        loop(main, light, sink, SendScreen()).run("t")
        assertEquals(1, light.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
        assertTrue(sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
    }

    @Test fun screenshotsNeverGoToTextOnlyLight() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel())
        // メインモデルの画像: 添付のスクリーンショットを参照してください。画像を見ないと軽量モデルは接続されません。
        val seeing = object : ChatModel by main {
            override val capabilities = main.capabilities.copy(vision = true)
        }
        AgentLoop(seeing, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = on,
            router = ModelRouter(seeing, light)).run("t")
        assertEquals(0, light.requests.size)
    }

    @Test fun lightErrorFallsBackToMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Network()))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 軽量モデルでエラーが発生した場合、リトライは行われません。このステップはメイン モデルに直接変更します。
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun light503FallsBackImmediately() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Server(503)))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 503 は軽量モデルには再発行されず、この手順はメイン モデルに引き継がれます。
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }
}
