package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 画像を表示できる主なモデル: 画像はアクション後に自動的に添付されます。履歴内の写真は、カットラインの前に表示できるプレースホルダーに置き換えられます。 */
class AgentLoopFrameTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("f${++seq}", name, args)
    private fun tools(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
    private fun finish() = com.wangzan101.mika.core.testing.answerReply("done")
    private fun images(msgs: List<Message>) = msgs.flatMap { it.parts }.filterIsInstance<Part.Image>()

    @Test fun actionResultFollowedByFrameAndSentFramesAreKeptInOrder() = runTest {
        val tap = call("phone_tap", """{"x":500,"y":500}""")
        val m = FakeModel(tools(call("phone_observe")), tools(tap), *(1..5).map { tools(call("phone_tap", """{"x":${it * 100},"y":300}""")) }.toTypedArray(), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE").run("t")
        val msgs = m.requests[2]
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == tap.id } }
        assertEquals(Role.Tool, msgs[at].role)
        // グラフは、観察と同じユーザー メッセージを使用して、ツールの結果に従います (各インターフェイスの呼び出しと結果のペアは影響を受けません)。
        assertTrue(images(msgs).isEmpty())
        val tool = msgs[at].parts.filterIsInstance<Part.ToolResult>().single()
        assertFalse(tool.content.contains("搜索"))
        assertTrue(m.requests.all { images(it).isEmpty() })
    }

    @Test fun textOnlyMainGetsNoFrames() = runTest {
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"x":500,"y":500}""")), finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("t")
        assertTrue("images in requests: ${m.requests.map { images(it).map(Part.Image::screenshotId) }}", m.requests.all { images(it).isEmpty() })
        assertEquals(5, m.requests[2].withoutRuntime().size)
    }

    @Test fun adaptiveVisionDoesNotSendReadableScreenImagesToModel() = runTest {
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_back")), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true, adaptiveVision = true, inlineScreen = true), "BASE").run("t")
        assertTrue(m.requests.all { images(it).isEmpty() })
        assertTrue(m.requests[2].any { message -> message.parts.any { part ->
            part is Part.ToolResult && "\"screen\"" in part.content
        } })
    }

    @Test fun batchEndAttachesOneFrame() = runTest {
        val batch = call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(tools(batch), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        val msgs = m.requests[1]
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == batch.id } }
        assertTrue(at >= 0)
        assertTrue(images(msgs).isEmpty())
    }

    @Test fun adaptiveBatchExecutesTwoActionsInOneModelRoundWithoutImages() = runTest {
        val screen = FakeScreen().apply { result = com.wangzan101.mika.core.uistate.ActionResult.Ok(changed = true) }
        val batch = call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(tools(batch), finish())
        AgentLoop(m, toolDispatcher(screen, visionEnabled = true, adaptiveVision = true, inlineScreen = true), "BASE",
            flags = SpeedFlags(batchSteps = true, inlineScreen = true, adaptiveVision = true)).run("t")
        assertEquals(2, m.requests.size)
        assertEquals(listOf("back", "back"), screen.calls)
        assertTrue("images in requests: ${m.requests.map { images(it).map(Part.Image::screenshotId) }}", m.requests.all { images(it).isEmpty() })
    }
}
