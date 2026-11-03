package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunRecoveryTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private class RecordingChannel : Channel {
        val sent = mutableListOf<Delivery>()
        override val id = LocalChannel.ID
        override val confirms = true
        override fun start(sink: ChannelInputSink) = AutoCloseable { }
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome { sent += delivery; return SendOutcome.Delivered }
    }

    private object NoTriggers : TriggerOutcomes {
        override fun interrupted(key: String, reason: String) {}
        override fun delivered(key: String) {}
        override fun actionFailed(key: String, reason: String) {}
    }

    private inner class Rig(val scope: TestScope) {
        val store = MemoryRunStore()
        val channel = RecordingChannel()
        val outbox = Outbox()
        val conversations = ConversationStore(tmp.newFolder())
        val recovery = RunRecovery(store, outbox, channel, NoTriggers, conversations, scope)
    }

    private fun run(id: String, phase: RunPhase = RunPhase.Running) = StoredRun(RunId(id), "thread-$id", "目标 $id", phase)

    // プロセスが強制終了された後、アプリが (サービスなしで) 開始されるとすぐに、中断されたものとしてマークされ、通知されます。
    @Test fun appStartMarksUnfinishedRunsInterruptedAndTellsUser() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.save(run("done", RunPhase.Ended))

        val interrupted = rig.recovery.recover()

        assertEquals(listOf(RunId("a")), interrupted.map { it.id })
        assertEquals(RunPhase.Interrupted, rig.store.get(RunId("a"))?.phase)
        assertEquals(RunPhase.Ended, rig.store.get(RunId("done"))?.phase)
        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
    }

    // アプリの起動とタスク サービスの起動がトリガーされます: 通知されるのは 1 回のみで、その時点ではサービスは何も行いません
    @Test fun secondTriggerDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))

        rig.recovery.recover()
        // 以降、新たな運用が開始され、再度サービスの開始と調整が行われました。
        rig.store.save(run("b", RunPhase.Preparing))
        val second = rig.recovery.recover()

        assertTrue(second.isEmpty())
        assertEquals(RunPhase.Preparing, rig.store.get(RunId("b"))?.phase)
        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
    }

    // 応答は保存されましたが、配信されていません。これは一度送信され、再度トリガーされても再送信されません。
    @Test fun redeliversSavedReplyOnlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a", RunPhase.Finishing).copy(reply = "结果", deliveryId = "d1"))

        rig.recovery.recover()
        rig.recovery.recover()

        assertEquals(listOf("d1"), rig.channel.sent.map { it.id })
        assertEquals(RunPhase.Ended, rig.store.get(RunId("a"))?.phase)
    }

    // 現在実行中の操作は中断とみなされません。
    @Test fun liveRunsAreNotInterrupted() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("live"))
        rig.store.save(run("dead"))

        val interrupted = rig.recovery.recover(live = setOf(RunId("live")))

        assertEquals(listOf(RunId("dead")), interrupted.map { it.id })
        assertEquals(RunPhase.Running, rig.store.get(RunId("live"))?.phase)
        assertEquals(listOf("interrupted:dead"), rig.channel.sent.map { it.id })
    }

    // タスク サービスが破棄されると、中断としてマークされます (プロセスはまだ存在します)。その場で一度通知され、スキャン後は繰り返されません (次回起動時)。
    @Test fun runInterruptedByServiceDestroyIsToldExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.interruptUnfinished()

        rig.recovery.settleInterrupted()
        rig.recovery.settleInterrupted()
        rig.recovery.recover()

        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
        assertEquals(RunPhase.Interrupted, rig.store.get(RunId("a"))?.phase)
    }

    // かなり前に通知されていた中断操作は要求されなくなりました。
    @Test fun alreadyToldRunIsNotToldAgain() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("old", RunPhase.Interrupted))
        rig.outbox.deliver(rig.channel, ChannelPeer(LocalChannel.ID, "phone-owner", "thread-old"),
            Delivery.Notice("interrupted:old", "thread-old", "旧提示"))
        rig.channel.sent.clear()

        rig.recovery.settleInterrupted()

        assertTrue(rig.channel.sent.isEmpty())
        assertTrue(rig.conversations.turns("thread-old").isEmpty())
    }

    // 中断は会話レコードに書き込まれます。失敗したラウンドと同じステータス セットです。
    @Test fun interruptionIsWrittenIntoConversation() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.interruptUnfinished()

        rig.recovery.settleInterrupted()
        rig.recovery.settleInterrupted()

        val turns = rig.conversations.turns("thread-a")
        assertEquals(1, turns.size)
        assertEquals("failed", turns.single().status)
        assertEquals("目标 a", turns.single().userText)
        assertEquals("タスクが中断されました。もう一度開始できます。", turns.single().outcome)
    }
}
