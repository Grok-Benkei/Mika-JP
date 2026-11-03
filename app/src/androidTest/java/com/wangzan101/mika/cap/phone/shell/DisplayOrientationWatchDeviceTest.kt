package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DisplayOrientationWatchDeviceTest {
    @Test fun unavailableTaskListenerFallsBackToPollingAndStopsOnClose() {
        val calls = AtomicInteger()
        val twice = CountDownLatch(2)
        // 登録インターフェイスのないオブジェクト シミュレーション システムは、タスクの監視を提供しません。実際のタスク マネージャーには登録されません。
        val watch = DisplayOrientationWatch(Any(), active = { true }) { calls.incrementAndGet(); twice.countDown() }
        try {
            assertTrue("polling fallback did not run", twice.await(4, TimeUnit.SECONDS))
        } finally { watch.close() }
        val atClose = calls.get()
        Thread.sleep(650)
        assertEquals("closed watcher kept polling", atClose, calls.get())
    }

    @Test fun pollingStopsOnceNoDisplayIsLeft() {
        val calls = AtomicInteger()
        val displays = AtomicBoolean(true)
        val first = CountDownLatch(1)
        val watch = DisplayOrientationWatch(Any(), active = { displays.get() }) { calls.incrementAndGet(); first.countDown() }
        try {
            assertTrue("polling fallback did not run", first.await(4, TimeUnit.SECONDS))
            // 画面は削除されましたが、まだ閉じられていません。ポーリングは停止します。
            displays.set(false)
            Thread.sleep(650)
            val stopped = calls.get()
            Thread.sleep(1_100)
            assertEquals("polling kept running without a display", stopped, calls.get())
        } finally { watch.close() }
    }
}
