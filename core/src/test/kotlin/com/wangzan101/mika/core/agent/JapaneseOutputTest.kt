package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseOutputTest {
    private val japanese = "確認しました。操作はまだ実行していません。"
    private val foreign = "Bluetooth is on."

    private fun loop(model: FakeModel, maxSteps: Int = 10, sink: RunSink = RunSink.NONE, stream: Boolean = false) =
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", maxSteps = maxSteps, sink = sink, streamReplies = stream)

    @Test fun englishAndChineseInputsStillReceiveJapanese() = runTest {
        for (goal in listOf("Please answer in English", "请用中文回答")) {
            val model = FakeModel(answerReply(japanese))
            assertEquals(japanese, (loop(model).run(goal) as TaskState.Succeeded).answer)
            assertEquals(1, model.requests.size)
        }
    }

    @Test fun foreignAnswerIsRewrittenIntoJapaneseOnce() = runTest {
        val model = FakeModel(answerReply(foreign), answerReply(japanese))
        assertEquals(japanese, (loop(model).run("请回答") as TaskState.Succeeded).answer)
        assertEquals(2, model.requests.size)
        val notice = model.requests.last().flatMap { it.parts }.filterIsInstance<Part.Text>()
            .last { ContextBuilder.kindOf(it.text) == ContextKind.Notice }.text
        assertTrue(notice, "日本語" in notice)
    }

    @Test fun ignoredRewriteEndsWithAnHonestJapaneseFailure() = runTest {
        val model = FakeModel(answerReply(foreign), answerReply(foreign))
        val end = loop(model).run("What is the result?") as TaskState.Failed
        assertEquals(FailKind.Model, end.kind)
        assertTrue(end.reason, "日本語の回答が得られなかった" in end.reason)
        assertFalse(end.reason, foreign in end.reason)
        assertEquals(2, model.requests.size)
    }

    @Test fun shortChineseCompletionIsRewrittenAndFailsHonestlyIfIgnored() = runTest {
        val rewritten = FakeModel(answerReply("付好了"), answerReply(japanese))
        assertEquals(japanese, (loop(rewritten).run("請用中文回答") as TaskState.Succeeded).answer)
        assertEquals(2, rewritten.requests.size)
        val ignored = FakeModel(answerReply("付好了"), answerReply("付好了"))
        val end = loop(ignored).run("結果を教えて") as TaskState.Failed
        assertEquals(FailKind.Model, end.kind)
        assertTrue(end.reason, "日本語の回答が得られなかった" in end.reason)
        assertFalse(end.reason, "付好了" in end.reason)
        assertEquals(2, ignored.requests.size)
    }

    @Test fun finalBudgetTurnAlsoRejectsForeignProse() = runTest {
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("list", "phone_list_apps", "{}")), StopReason.ToolUse, null),
            answerReply(foreign),
        )
        val end = loop(model, maxSteps = 1).run("请看看结果") as TaskState.Failed
        assertEquals(FailKind.Model, end.kind)
        assertTrue(end.reason, "日本語" in end.reason)
        assertEquals(2, model.requests.size)
    }

    @Test fun foreignFailureExplanationIsReturnedToTheModelBeforeDisplay() = runTest {
        for (foreignExplanation in listOf("I cannot do that.", "付好了")) {
            val model = FakeModel(
                ModelReply(listOf(Part.ToolCall("bad", "end_not_done", """{"message":"$foreignExplanation"}""")), StopReason.ToolUse, null),
                ModelReply(listOf(Part.ToolCall("jp", "end_not_done", """{"message":"必要な機能がないため、タスクは未完了です。"}""")), StopReason.ToolUse, null),
            )
            val end = loop(model).run("Please do it") as TaskState.Failed
            assertEquals(FailKind.GaveUp, end.kind)
            assertEquals("必要な機能がないため、タスクは未完了です。", end.reason)
            val correction = model.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single().content
            assertTrue(correction, "日本語" in correction)
        }
    }

    @Test fun codeAndOriginalQuotesArePreservedExactly() = runTest {
        val answer = "```json\n{\"reason\":\"The exact protocol value must remain unchanged\"}\n```\n「请保持原文。」\n「付好了」"
        val model = FakeModel(answerReply(answer))
        assertEquals(answer, (loop(model).run("Return the original code") as TaskState.Succeeded).answer)
        assertEquals(1, model.requests.size)
    }

    @Test fun foreignToolNarrationIsHiddenWhileRawHistoryAndArgumentsStayIntact() = runTest {
        val narration = "I am inspecting the current screen before choosing the next step."
        val args = """{"reason":"I need to inspect this screen before proceeding."}"""
        val model = FakeModel(
            ModelReply(listOf(Part.Text(narration), Part.ToolCall("observe", "phone_observe", args)), StopReason.ToolUse, null),
            answerReply(japanese),
        )
        val replies = mutableListOf<RunEvent.ModelReply>()
        val shown = mutableListOf<TaskState.Running>()
        lateinit var agent: AgentLoop
        agent = loop(model, sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ModelReply) {
                    replies += e
                    (agent.state.value as? TaskState.Running)?.let(shown::add)
                }
            }
        })
        assertEquals(japanese, (agent.run("Please inspect it") as TaskState.Succeeded).answer)
        assertEquals("処理を続けています。", replies.first().text)
        assertEquals("処理を続けています。", shown.first().narration)
        assertEquals("処理を続けています。", shown.first().reason)
        assertTrue(model.requests.last().flatMap { it.parts }.any { it is Part.Text && it.text == narration })
        assertTrue(model.requests.last().flatMap { it.parts }.any { it is Part.ToolCall && it.argsJson == args })
    }

    @Test fun foreignFinalCandidateIsNotEmittedBeforeItsJapaneseRewrite() = runTest {
        val model = FakeModel(answerReply(foreign), answerReply(japanese))
        val shown = mutableListOf<String>()
        val previews = mutableListOf<String?>()
        lateinit var agent: AgentLoop
        agent = loop(model, stream = true, sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ModelReply) {
                    shown += e.text
                    previews += (agent.state.value as? TaskState.Running)?.preview
                }
            }
        })
        assertEquals(japanese, (agent.run("Answer in English") as TaskState.Succeeded).answer)
        assertEquals(listOf("日本語の回答を確認しています。", japanese), shown)
        assertEquals("日本語の回答を確認しています。", previews.first())
        assertTrue(model.requests.last().flatMap { it.parts }.any { it is Part.Text && it.text == foreign })
    }

    @Test fun noUnfinishedProseIsDisplayedEvenWhenItStartsInJapanese() = runTest {
        val mixed = "確認しています。\nThe result will be explained in English after this sentence."
        val previews = mutableListOf<String?>()
        var requests = 0
        lateinit var agent: AgentLoop
        val model = object : StreamingChatModel {
            override val capabilities = FakeModel().capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("ストリーミングを使用します")
            override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
                requests++
                val chunks = if (requests == 1) listOf("I", "確認しています。", mixed) else listOf(japanese)
                for (chunk in chunks) {
                    onText(chunk)
                    previews += (agent.state.value as? TaskState.Running)?.preview
                }
                return answerReply(if (requests == 1) mixed else japanese)
            }
        }
        agent = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true)
        assertEquals(japanese, (agent.run("説明して") as TaskState.Succeeded).answer)
        assertEquals(4, previews.size)
        assertTrue(previews.toString(), previews.all { it == "処理を続けています。" })
    }
}
