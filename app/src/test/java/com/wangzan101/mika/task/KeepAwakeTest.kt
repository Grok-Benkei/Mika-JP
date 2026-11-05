package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {
    private class FakeLock : WakeLockPort {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }

    @Test
    fun holdsWhileRunningAndReleasesAtEnd() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun waitingKeepsScreenOnForGraceThenReleases() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.WaitingTakeover("login", "请登录"))
        advanceTimeBy(KeepAwake.GRACE_MS - 1)
        runCurrent()
        assertTrue(lock.held)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(lock.held)

        // ユーザーが処理を終了した後も、タスクは引き続き実行されます (常に再び実行されます)。
        awake.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
    }

    @Test
    fun resumingWithinGraceKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(TaskGate.paused("x")))
        advanceTimeBy(KeepAwake.GRACE_MS / 2)
        awake.onState(TaskState.Running(2, ""))
        advanceTimeBy(KeepAwake.GRACE_MS)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Cancelled)
        assertFalse(lock.held)
    }

    // 仮想画面の順番待ちを利用者の応答待ちと区別し、画面ロックを保持します。
    @Test
    fun queuedForDisplayKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        assertTrue(lock.held)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        advanceTimeBy(KeepAwake.GRACE_MS * 2)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Running(2, ""))
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun failureAndCancelRelease() = runTest {
        for (end in listOf(TaskState.Failed("x", FailKind.GaveUp), TaskState.Cancelled)) {
            val lock = FakeLock()
            val awake = KeepAwake(lock, backgroundScope)
            awake.onState(TaskState.Running(1, ""))
            awake.onState(end)
            assertFalse(lock.held)
        }
    }

    @Test
    fun noReacquireAfterClose() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.close()
        assertFalse(lock.held)
        // タスクがロックを取り戻すことができなかった後の遅いステータスの解放。
        awake.onState(TaskState.Running(2, ""))
        assertFalse(lock.held)
    }

    // 2 つのタスクはそれぞれコピーを保持します。最初のタスクは最初にロックを取得し、最後のタスクの終了時にロックを解放します。
    @Test
    fun refCountedAcrossRuns() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        val b = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        b.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        a.onState(TaskState.Succeeded("ok"))
        assertTrue(lock.held)
        a.close()
        assertTrue(lock.held)
        b.onState(TaskState.Cancelled)
        assertFalse(lock.held)
        b.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    // 前のタスクが次の分を取得してから自分のロックを解放し、引継ぎ中に画面ロックが途切れないことを確認します。
    @Test
    fun handoffBridgesToQueuedRun() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        val handoff = shared.lease().also { it.acquire() }
        a.close()
        assertTrue(lock.held)
        val b = KeepAwake(shared.lease(), backgroundScope)
        b.onState(TaskState.Running(0, ""))
        handoff.release()
        assertTrue(lock.held)
        // 起動時と完了時に重複して解放しても、カウンターを余分に減らしません。
        handoff.release()
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    /** 画面は最大の明るさでロックされず、オンのままになります。ユーザーが操作していないときは、システムは通常どおり画面を暗くします。 */
    @Suppress("DEPRECATION")
    @Test
    fun screenLockLetsTheSystemDim() {
        assertEquals(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, ScreenWakeLock.LEVEL)
    }
}
