package com.wangzan101.mika.cap.web

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 実機テスト: タスクが完了したら、ページを離れて実行を続けます。タスクに関連付けられていない一連の Web ページ ([WebStack]、タスク内と同じように構築)、ローカル タイミング ページを開きます。
 * web_keep_open パスを使用してダウンロードし、「タスクを終了」([WebStack.close]): そのページはタスクとともに閉じられませんが、プロセス [PageKeeper] のコピーに渡されます。
 * ページのタイマーとピクチャ フレームは増加し続けます。マークのないページは通常どおり閉じられます。古いページを置き換えるために新しいページが残されます。そして最後に [PageKeeper] によって閉じられます。
 * 秒は、`-e keptRunSec N` (デフォルトは 5) で調整できます。
 */
@RunWith(AndroidJUnit4::class)
class KeptPageDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val keeper = WebOwner.of(ctx).pageKeeper
    private val stacks = mutableListOf<WebStack>()
    private val seconds = InstrumentationRegistry.getArguments().getString("keptRunSec")?.toLongOrNull()?.coerceIn(1, 120) ?: 5L

    @Before
    fun setUp() {
        instr.runOnMainSync { keeper.close(KeptPageClose.User) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            stacks.forEach { runCatching { it.close() } }
            keeper.close(KeptPageClose.User)
        }
    }

    private fun stack(task: String): WebStack {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        lateinit var s: WebStack
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            s = WebStack.create(ctx, task, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi, listOf(loader))
            s.keepOrigin = { KeptOrigin("conv-$task", "run-$task", "打开计时页,挂着别关") }
        }
        stacks += s
        return s
    }

    private fun open(s: WebStack) = runBlocking { s.screen.navigate(TICKER, newTab = false) }.also { assertTrue("ticker loaded", it.loaded) }

    /** ページ内の現時点でのカウント (タイマー、ピクチャ フレーム)。 */
    private fun counts(tab: WebTab): Pair<Long, Long> {
        val latch = CountDownLatch(1)
        var raw = ""
        instr.runOnMainSync { tab.view.evaluateJavascript("window.ticks + ',' + window.frames") { raw = it; latch.countDown() } }
        assertTrue("page answered", latch.await(5, TimeUnit.SECONDS))
        val (a, b) = raw.trim('"').split(',')
        return a.toLong() to b.toLong()
    }

    /** タスクが終了します (メインスレッドでの TaskRun の終了と同じです)。 */
    private fun endTask(s: WebStack) {
        instr.runOnMainSync { s.close() }
        stacks -= s
    }

    @Test
    fun keptPageKeepsRunningAfterTheTaskEnds() {
        val s = stack("keep-1")
        open(s)
        val tab = s.pool.current("keep-1")!!
        val r = runBlocking { s.screen.keepOpen(true, "测试:接着运行") }!!
        assertTrue(r.keep)
        assertEquals("appassets.androidplatform.net", r.host)
        assertNull("nothing kept before", r.replaces)
        endTask(s)

        // タスク プールは閉じられていますが、このページは閉じられていません。ページは PageKeeper に渡され、まだ表示上にハングしています。
        assertFalse("kept tab not destroyed at task end", tab.destroyed)
        assertTrue(tab === keeper.keptTab())
        val page = keeper.page.value!!
        assertEquals("conv-keep-1", page.conversationId)
        assertEquals("run-keep-1", page.runId)
        assertEquals("appassets.androidplatform.net" to null, keeper.recordFor("run-keep-1"))
        assertNotNull("still on its display", tab.view.parent)

        // ページは引き続き実行されます。タイマーとピクチャ フレームは増加しています。
        val before = counts(tab)
        Thread.sleep(seconds * 1_000)
        val after = counts(tab)
        assertTrue("timer advanced ${before.first} -> ${after.first}", after.first - before.first >= seconds * 5)
        assertTrue("frames advanced ${before.second} -> ${after.second}", after.second > before.second)
        // 同じソースからのピクチャー・イン・ピクチャー画像も利用可能です
        assertNotNull("live frame", runBlocking { keeper.frames().let { f -> kotlinx.coroutines.withTimeoutOrNull(5_000) { f.first { it != null } } } })

        // は PageKeeper によってオフにされました: ラベルが破棄されています。理由をメモしてください
        instr.runOnMainSync { assertTrue(keeper.close(KeptPageClose.User)) }
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
        assertEquals("appassets.androidplatform.net" to "user", keeper.recordFor("run-keep-1"))
    }

    @Test
    fun unmarkedPagesCloseWithTheTask() {
        val s = stack("plain")
        open(s)
        val tab = s.pool.current("plain")!!
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun takenBackMarkClosesWithTheTask() {
        val s = stack("back")
        open(s)
        val tab = s.pool.current("back")!!
        runBlocking { s.screen.keepOpen(true, "x") }
        assertFalse(runBlocking { s.screen.keepOpen(false, "不用了") }!!.keep)
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun keepingAnotherPageClosesTheOldOne() {
        val a = stack("first")
        open(a)
        val first = a.pool.current("first")!!
        runBlocking { a.screen.keepOpen(true, "x") }
        endTask(a)
        val b = stack("second")
        open(b)
        // がページを残しました。結果に明記されている場合は削除されます。
        assertEquals("appassets.androidplatform.net", runBlocking { b.screen.keepOpen(true, "y") }!!.replaces)
        endTask(b)
        assertTrue("old kept page closed", first.destroyed)
        assertEquals("conv-second", keeper.page.value?.conversationId)
        assertEquals("appassets.androidplatform.net" to "replaced", keeper.recordFor("run-first"))
    }

    private companion object {
        const val TICKER = "https://appassets.androidplatform.net/assets/web-fixtures/ticker.html"
    }
}
