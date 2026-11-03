package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockStateTest {
    private var interactive = false
    private var keyguardLocked = true
    private var reads = 0

    private fun TestScope.lockState() = LockState({ reads++; LockState.Reading(interactive, keyguardLocked) }, backgroundScope)

    private fun TestScope.tick() {
        advanceTimeBy(DeviceStateWatcher.POLL_MS)
        runCurrent()
    }

    // ロック解除のブロードキャストは、ロック画面が終了するよりも早く行われます。読み取られた内容はまだロックされており、その後ブロードキャストはないため、自分でロックの解除を確認する必要があります。
    @Test fun unlockSeenWithoutAnotherBroadcast() = runTest {
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        // 画面がオフのときは読み取りません。SCREEN_ON になるまで待ちます
        val dark = reads
        repeat(5) { tick() }
        assertEquals(dark, reads)

        interactive = true
        lock.refresh() // SCREEN_ON または USER_PRESENT: オン、ロック画面はまだ存在します
        tick()
        assertFalse(passed)
        keyguardLocked = false // ロック画面が終了し、ブロードキャストなし
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())

        // が読み取られてロックが解除された後は読み取られなくなります
        val unlocked = reads
        repeat(10) { tick() }
        assertEquals(unlocked, reads)
    }

    // 誰も見ていないときはこのステータスを読まないでください
    @Test fun noReadsWithoutSubscribers() = runTest {
        interactive = true
        val lock = lockState()
        runCurrent()
        val start = reads
        repeat(10) { tick() }
        assertEquals(start, reads)
        assertTrue(lock.locked.value)
    }
}
