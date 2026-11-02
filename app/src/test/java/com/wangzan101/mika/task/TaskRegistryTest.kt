package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRegistryTest {
    private data class Run(val conversationId: String, val goal: String) {
        val key = "$conversationId/$goal"
    }

    private var limit = ParallelLimit(2, null)
    private val registry = TaskRegistry({ limit }, { it.conversationId }, { it.key }) { c, g -> Run(c, g) }

    @Test fun secondConversationStartsWhenLimitTwo() {
        val a = registry.tryStart("a", "设置版本号")
        val b = registry.tryStart("b", "新建闹钟")
        assertTrue(a is StartResult.Started)
        assertTrue(b is StartResult.Started)
        assertEquals(listOf("a", "b"), registry.running().map { it.conversationId })
        assertEquals(Run("b", "新建闹钟"), registry.byConversation("b"))
    }

    @Test fun sameConversationQueues() {
        registry.tryStart("a", "一")
        // 同じ会話内にすでにタスクがあります。M1 ルールに従い、キューに入る会話に任せてください。登録局では受け付けません。
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("a", "二"))
        assertEquals(1, registry.runs().size)
    }

    @Test fun scheduledRunsQueueBehindSameConversationEvenWithFreeParallelSlot() {
        val first = (registry.tryStart("a", "现在的任务") as StartResult.Started).run
        val scheduled = registry.tryStart("a", "到点的任务", queueInConversation = true) as StartResult.Queued
        assertEquals(listOf(first), registry.running())
        assertEquals(listOf(scheduled.run), registry.queued())
        assertEquals(listOf(scheduled.run), registry.onEnded(first))
    }

    @Test fun overLimitQueues() {
        limit = ParallelLimit(1, null)
        registry.tryStart("a", "一")
        val b = registry.tryStart("b", "二")
        val c = registry.tryStart("c", "三")
        assertEquals(1, (b as StartResult.Queued).position)
        assertEquals(2, (c as StartResult.Queued).position)
        assertEquals(listOf("a"), registry.running().map { it.conversationId })
        assertEquals(listOf("b", "c"), registry.queued().map { it.conversationId })
        assertTrue(registry.isQueued(b.run))
        // キュー内の 2 番目の会話を受け入れることはできません
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("c", "四"))
    }

    @Test fun endedRunStartsQueued() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        registry.tryStart("c", "三")
        assertEquals(listOf(b), registry.onEnded(a))
        assertEquals(listOf("b"), registry.running().map { it.conversationId })
        assertEquals(listOf("c"), registry.queued().map { it.conversationId })
        assertNull(registry.byConversation("a"))
        // キューに入れられたアイテムはキャンセルされました。新しいアイテムはリリースされず、キューは前に進みます。
        val c = registry.byConversation("c")!!
        assertEquals(emptyList<Run>(), registry.onEnded(c))
        assertEquals(emptyList<Run>(), registry.queued())
    }

    // 2 つのタスクが同じアプリを同時に開く必要があります。最初に占有されているタスクは開くことができ、もう 1 つは「他のタスクに使用」されます。
    @Test fun sameAppReservedByFirstRun() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Started).run
        assertTrue(registry.reserve("settings", a.key) { true })
        assertTrue(registry.reserve("settings", a.key) { true })
        assertFalse(registry.reserve("settings", b.key) { true })
        // が占有しているタスクはそれを使用しなくなりました (アプリ自体が閉じられました)。後続のタスクのために残しておきます。
        assertTrue(registry.reserve("settings", b.key) { false })
        assertFalse(registry.reserve("settings", a.key) { true })
        // タスクは終了し、占有されているパッケージはまとめて解放されます。
        registry.onEnded(b)
        assertTrue(registry.reserve("settings", a.key) { true })
    }

    @Test fun centerUsesItsResourceSchedulerForMainScreenAndClipboard() {
        assertSame(registry.resources.takeoverQueue, registry.takeoverQueue)
        assertSame(registry.resources.clipboard, registry.clipLock)
    }

    @Test fun cancelIfQueuedOnlyTakesQueuedRuns() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertFalse(registry.cancelIfQueued(a))
        assertTrue(registry.cancelIfQueued(b))
        assertFalse(registry.cancelIfQueued(b))
        assertEquals(emptyList<Run>(), registry.onEnded(a))
        assertEquals(RunPhase.Ended, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun centerStoresTransitionsBeforeAndAfterDelivery() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(a.key))?.phase)
        assertEquals(RunPhase.Queued, registry.store.get(RunId(b.key))?.phase)
        registry.markRunning(a)
        assertEquals(true, registry.store.get(RunId(a.key))?.roundInProgress)
        registry.markResult(a, "好了")
        assertEquals("好了", registry.store.get(RunId(a.key))?.reply)
        registry.markDelivered(a)
        assertEquals(RunPhase.Ended, registry.store.get(RunId(a.key))?.phase)
        registry.onEnded(a)
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun messagesAtFinishingBoundaryAreKeptInOrder() {
        val run = (registry.tryStart("thread", "原来的") as StartResult.Started).run
        assertFalse(registry.deferOrStart("thread", "first"))
        assertFalse(registry.deferOrStart("thread", "second"))
        // 会話中にまだタスクがあります: 渡さないでください
        assertEquals(emptyList<Pair<String, String>>(), registry.takeReady())
        registry.onEnded(run)
        assertEquals(listOf("thread" to "first", "thread" to "second"), registry.takeReady())
        assertEquals(emptyList<Pair<String, String>>(), registry.takeReady())
    }

    // 終了とメッセージの残しは同時に行われます。その場で開始するか、メッセージを残して終了時に渡すため、両端が失われることはありません。
    @Test fun deferRacingWithEndNeverStrandsTheMessage() {
        repeat(200) { i ->
            val run = (registry.tryStart("t$i", "原来的") as StartResult.Started).run
            var startedNow = false
            val t = Thread { startedNow = registry.deferOrStart("t$i", "插话") }
            t.start()
            registry.onEnded(run)
            t.join()
            val handedOut = registry.takeReady().any { it.first == "t$i" }
            assertTrue("message $i lost or duplicated", startedNow != handedOut)
        }
    }

    // そのままの場合は、他のダイアログ タスクを待たずに、自分のダイアログが空くまで待ってください。
    @Test fun deferredMessageStartsWhenItsConversationIsFree() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        registry.tryStart("b", "别的对话")
        assertFalse(registry.deferOrStart("a", "下一件"))
        registry.onEnded(a)
        assertEquals(listOf("a" to "下一件"), registry.takeReady())
        assertTrue(registry.deferOrStart("c", "空着的对话"))
    }
}
