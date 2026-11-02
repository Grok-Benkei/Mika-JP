package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.uistate.AppWait
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ResourceSchedulerTest {
    @Test fun simultaneousAppClaimsHaveExactlyOneOwnerAndReleaseFreesIt() {
        val resources = ResourceScheduler()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val wins = AtomicInteger()
        for (owner in listOf("a", "b")) Thread {
            start.await()
            if (resources.reserveApp("com.example", owner, { true }, { true })) wins.incrementAndGet()
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        val first = resources.appHolder("com.example")!!
        resources.releaseOwner(first)
        assertTrue(resources.reserveApp("com.example", if (first == "a") "b" else "a", { true }, { true }))
    }

    @Test fun clipboardLeaseSerializesTwoTasks() {
        val resources = ResourceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = Thread {
            resources.clipboard.lock()
            try { entered.countDown(); release.await() } finally { resources.clipboard.unlock() }
        }
        val second = Thread {
            resources.clipboard.lock()
            try { secondEntered.set(true) } finally { resources.clipboard.unlock(); secondFinished.countDown() }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(secondEntered.get())
        release.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        first.join(5_000)
    }

    // ---------- 他のタスクがアプリをリリースするまで待ちます ----------

    private val active: (String) -> Boolean = { true }

    /** 「登録に誰もいない」場合でも放置してください（テスト中は他の画面を見ないでください）。 */
    private fun ResourceScheduler.freeFor(pkg: String, owner: String): suspend () -> Boolean = { appFree(pkg, owner, active, { true }) }

    /** 占有されているタスクが解放されるとすぐに、待機中のタスクはすぐに起動します (次のポーリングを待たずに)。 制限時間が経過しても、 */
    @Test fun releaseWakesTheWaiter() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        val waiter = async { r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b"), pollMs = 30_000) }
        advanceTimeBy(5_000)
        assertFalse(waiter.isCompleted)
        r.releaseOwner("a")
        runCurrent()
        assertEquals(AppWait.Free, waiter.await())
        assertTrue("次のポーリングより十分早く復帰する", currentTime < 30_000)
        assertTrue(r.reserveApp("com.chat", "b", active, { true }))
    }

    /** はまだ占有されています。正直に言って、待ちきれません。待機としてカウントされなくなりました。 */
    @Test fun waitingTimesOut() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.chat", "b", 3_000, active, r.freeFor("com.chat", "b")))
        assertEquals(3_000L, currentTime)
        // b は待機中として登録されなくなりました。a は b を待機するようになりました。手持ちのアプリは相互に待機しているとはみなされません。
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.mail", "a", 1_000, active, r.freeFor("com.mail", "a")))
    }

    /** a はチャットをしながら、b の手元にある電子メールを待っています。 b はチャット中にお互いを待っています。b はその場で待たず、a はまだ待ちます。 */
    @Test fun mutualWaitIsRefusedAtOnce() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        val a = async { r.awaitApp("com.mail", "a", 60_000, active, r.freeFor("com.mail", "a")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b")))
        assertEquals("待機していないため、時刻は進まない", 0L, currentTime)
        // b 待たずに、終わったらメールをしまってください。 a そのまま待ってください。
        r.releaseOwner("b")
        runCurrent()
        assertEquals(AppWait.Free, a.await())
    }

    /** 円内の 3 つのタスク (a は b を待機、b は c を待機、c は a を待機) も認識されます。円の中にいない場合は、通常どおり待ちます。 */
    @Test fun longerCyclesAreFoundAndChainsWithoutCycleWait() = runTest {
        val r = ResourceScheduler()
        for ((pkg, owner) in listOf("p.a" to "a", "p.b" to "b", "p.c" to "c")) assertTrue(r.reserveApp(pkg, owner, active, { true }))
        val a = async { r.awaitApp("p.b", "a", 60_000, active, r.freeFor("p.b", "a")) }
        val b = async { r.awaitApp("p.c", "b", 60_000, active, r.freeFor("p.c", "b")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("p.a", "c", 60_000, active, r.freeFor("p.a", "c")))
        // d は何も占有しません。a の手が円を形成しないまで待ちます
        val d = async { r.awaitApp("p.a", "d", 2_000, active, r.freeFor("p.a", "d")) }
        advanceTimeBy(2_001)
        assertEquals(AppWait.TimedOut, d.await())
        a.cancel(); b.cancel()
    }

    /** 占有しているタスクはもう存在しません (手放す時間がありません)。占有しているとはみなされず、相互に待機しているともみなされません。 */
    @Test fun goneHolderDoesNotBlockOrDeadlock() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "gone", active, { true }))
        val alive: (String) -> Boolean = { it != "gone" }
        assertEquals(AppWait.Free, r.awaitApp("com.chat", "b", 1_000, alive, { r.appFree("com.chat", "b", alive, { true }) }))
    }
}

