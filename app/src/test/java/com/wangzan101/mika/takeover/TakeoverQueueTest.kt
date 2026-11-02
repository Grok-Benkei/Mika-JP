package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverQueueTest {
    // メイン画面では一度に 1 つのアプリのみを配信します。2 番目のタスクは最初のタスクの後に引き継がれます。
    @Test fun secondWaitsForFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(queue.isFirst(a))
        assertEquals(1, queue.behind(a))

        assertFalse(b.handOver())
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.log.isEmpty())

        assertTrue(a.handOver())
        assertTrue(a.handBack())
        assertTrue(queue.isFirst(b))
        assertEquals(0, queue.behind(b))
        assertTrue(b.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), portB.log)
    }

    // キューの最後尾でのテイクオーバーには時間が計測されません。前のユーザーが処理している限り、タイムアウトとは判断されません。タイマーはキューの最後に到達するまで開始されません。
    @Test fun queuedTakeoverDoesNotTimeOutBehindFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(a.handOver())
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1_000)
        runCurrent()
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.results.isEmpty())

        assertTrue(a.handBack())
        runCurrent()
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1_000)
        runCurrent()
        assertTrue(portB.results.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(HandBackOutcome.timeout), portB.results.map { it.outcome })
    }
}
