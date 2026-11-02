package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.InterruptibleStream
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ストリーミングを中断した場合、生の部分応答は内部のモデル履歴に保持し、ユーザーが実際に見た日本語の状況表示だけを中断イベントへ記録する。
 * たまたま返信が先に終わって、間投詞が後から来ただけです。完成した部分は間投詞の上にぶら下がっていました（インターフェースは失われていません）。
 */
class InterruptedReplyTest {
    private fun spec() = RunSpec(RunId("r1"), "查今天的天气", "thread-1")
    private val steerText = "改成查明天"

    private class Events : RunSink {
        val interrupted = mutableListOf<String>()
        override fun event(e: RunEvent) {
            if (e is RunEvent.State && e.name == "reply_interrupted") interrupted += e.detail
        }
    }

    /** は 1 回実行されます。最初のリクエストは [chunks] から流れ出て停止し、文が挿入され、モデルが応答します。 2 番目のリクエストで表示されたメッセージを返します。 */
    private suspend fun kotlinx.coroutines.test.TestScope.steerDuring(
        chunks: List<String>, events: Events = Events(), streamReplies: Boolean = true,
    ): List<Message> {
        val inner = fakeModel("main") { answer("明日は曇りです。") }
        val model = InterruptibleStream(inner, chunks)
        val inbox = RunInbox()
        val running = async {
            AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = streamReplies, sink = events).run(spec(), inbox)
        }
        model.entered.await()
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("m1", steerText)))
        val end = running.await()
        assertTrue(model.cancelled)
        assertTrue(end is TaskState.Succeeded)
        assertEquals(1, inner.requests.size)
        return inner.requests[0].messages
    }

    private fun List<Message>.mikaTexts() =
        filter { it.role == Role.Assistant && it.author == Author.Mika }.map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text } }

    @Test fun keepsRawPartialInternallyAndRecordsOnlyTheJapaneseStatusShown() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("まず確認します", "まず今日の天気を確認します "), events)
        val kept = messages.indexOfLast { it.role == Role.Assistant }
        assertEquals(listOf("まず今日の天気を確認します"), messages.mikaTexts())
        assertEquals(listOf(Part.Text("まず今日の天気を確認します")), messages[kept].parts)
        val next = messages[kept + 1]
        assertEquals(Author.User, next.author)
        assertEquals("${AgentLoop.STEER_MARKER}\n$steerText", (next.parts.first() as Part.Text).text)
        assertEquals(listOf("処理を続けています。"), events.interrupted)
    }

    @Test fun nothingIsKeptWhenNoTextWasShown() = runTest {
        val events = Events()
        val messages = steerDuring(emptyList(), events)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun textClearedByARetryIsNotKept() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("先看一下", ""), events)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun nothingIsKeptWhenRepliesAreNotStreamedToTheScreen() = runTest {
        val events = Events()
        val messages = steerDuring(listOf("まず今日の天気を確認します"), events, streamReplies = false)
        assertTrue(messages.mikaTexts().isEmpty())
        assertTrue(events.interrupted.isEmpty())
    }

    @Test fun aModelThatDoesNotStreamLeavesNothingBehind() = runTest {
        val entered = CompletableDeferred<Unit>()
        val requests = mutableListOf<List<Message>>()
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                requests += messages
                if (requests.size == 1) { entered.complete(Unit); awaitCancellation() }
                return answerReply("明日は曇りです。")
            }
        }
        val inbox = RunInbox()
        val running = async { AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true).run(spec(), inbox) }
        entered.await()
        inbox.send(RunInput.Steer("m1", steerText))
        running.await()
        assertTrue(requests[1].mikaTexts().isEmpty())
    }

    private class AllStates : RunSink {
        val states = mutableListOf<Pair<String, String>>()
        override fun event(e: RunEvent) { if (e is RunEvent.State) states += e.name to e.detail }
        fun detail(name: String) = states.filter { it.first == name }.map { it.second }
    }

    // たまたま応答が先に終了し、中断が後で発生しました。応答は通常どおり履歴に記録され、応答の後の部分 (切り捨てられていない全文) がインターフェイスに送信され、中断の上にハングされるため、中断されたものとしてカウントされません。
    @Test fun aReplyThatFinishedJustBeforeTheSteerIsKeptOnScreen() = runTest {
        val full = "今日は晴れで、最高気温は25度です。夕方は風があります。"
        val inbox = RunInbox()
        val all = AllStates()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                all.event(e)
                if (e is RunEvent.ModelReply && e.step == 1) runBlocking { inbox.send(RunInput.Steer("late", steerText)) }
            }
        }
        val model = FakeModel(answerReply(full), answerReply("明日は曇りです。"))
        val end = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true, sink = sink).run(spec(), inbox)
        assertEquals("明日は曇りです。", (end as TaskState.Succeeded).answer)
        assertEquals(listOf(full), model.requests[1].mikaTexts())
        assertEquals(listOf(full), all.detail("reply_finished_before_steer"))
        assertTrue(all.detail("reply_interrupted").isEmpty())
        assertEquals(listOf("reply_finished_before_steer", "steer_received"),
            all.states.map { it.first }.filter { it == "reply_finished_before_steer" || it == "steer_received" })
    }

    // 文字が表示されない（ストリーミング表示がない）場合は、電話を切る必要はありません。中断して通常どおり接続します。
    @Test fun nothingIsAttachedWhenTheFinishedReplyWasNotShown() = runTest {
        val inbox = RunInbox()
        val all = AllStates()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                all.event(e)
                if (e is RunEvent.ModelReply && e.step == 1) runBlocking { inbox.send(RunInput.Steer("late", steerText)) }
            }
        }
        val model = FakeModel(answerReply("今天晴。"), answerReply("明日は曇りです。"))
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", sink = sink).run(spec(), inbox)
        assertEquals(2, model.requests.size)
        assertTrue(all.detail("reply_finished_before_steer").isEmpty())
        assertEquals(1, all.detail("steer_received").size)
    }
}
