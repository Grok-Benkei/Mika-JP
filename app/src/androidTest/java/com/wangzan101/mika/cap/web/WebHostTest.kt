package com.wangzan101.mika.cap.web

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * 実機テスト: Web ページは、アプリによって構築されたプライベート仮想ディスプレイのバックグラウンドでレンダリングされます。内蔵アシスタントを使用したり、ホーム画面をタッチしたりする必要はありません。
 * フィクスチャはテスト パッケージのアセット内にあり、https アドレスを使用して WebViewAssetLoader によって提供され、外部ネットワークに依存しません。
 */
@RunWith(AndroidJUnit4::class)
class WebHostTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var tab: WebTab

    private val fixture = "https://appassets.androidplatform.net/assets/web-fixtures/host.html"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            // フィクスチャはテスト パッケージに含まれているため、テスト パッケージのコンテキストを使用してアセットを読み取ります
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context))
                .build()
            tab = WebTab(ctx, "t1", "task-1", listOf(loader))
            host.attach(tab)
        }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            host.detach(tab.view)
            tab.destroy()
            host.close()
        }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private suspend fun num(expr: String): Int {
        val raw = js(expr)
        return raw.toIntOrNull() ?: throw AssertionError("$expr is not a number: $raw")
    }

    private fun topResumed(): String? {
        val lines = DeviceSupport.sh("dumpsys activity activities").lines().map { it.trim() }
        return lines.firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }
            // 一部の Android 11 および 12 システムでは、概要に現在のフォアグラウンド アクティビティのみが表示されます。
            ?: lines.firstOrNull { it.startsWith("ResumedActivity:") }
    }

    @Test
    fun rendersInBackgroundWithoutTouchingMainScreen() = runBlocking<Unit> {
        val before = topResumed()
        assertNotNull("topResumedActivity not found in dumpsys", before)
        assertTrue(tab.load(fixture))
        assertEquals("\"host\"", js("document.title"))
        assertEquals(before, topResumed())
        assertNotEquals(HostMode.Detached, host.mode)
    }

    @Test
    fun pageThinksItIsVisible() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        assertEquals("\"visible\"", js("document.visibilityState"))
    }

    @Test
    fun timersAndAnimationFramesRun() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        val t0 = num("window.ticks")
        val f0 = num("window.frames")
        Thread.sleep(2_000)
        val ticks = num("window.ticks") - t0
        val frames = num("window.frames") - f0
        assertTrue("ticks=$ticks", ticks >= 10)
        assertTrue("frames=$frames", frames >= 10)
    }

    @Test
    fun frameIsNotBlack() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        val png = DeviceSupport.pollUntil(5_000) { host.latestFramePng()?.takeIf { isMostlyRed(it) } }
        assertNotNull("no red frame; mode=${host.mode} last=${host.latestFramePng()?.size}", png)
    }

    @Test
    fun touchProducesTrustedClick() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        // CSS ピクセル × (ビュー幅 / innerWidth) = WebView ビュー ピクセル (デスクトップ バージョンのシミュレーションでは、ビュー幅に 1280 CSS ピクセルがインデントされます。devicePixelRatio は使用できません)
        val viewW = withContext(Dispatchers.Main) { tab.view.width }
        val c = js(
            "(function(){var r=document.getElementById('btn').getBoundingClientRect();" +
                "var d=$viewW/window.innerWidth;return [(r.left+r.width/2)*d,(r.top+r.height/2)*d].join(',');})()"
        ).trim('"').split(',').map { it.toFloat() }
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, c[0], c[1], 0).also { tab.view.dispatchTouchEvent(it) }.recycle()
        }
        Thread.sleep(50)
        withContext(Dispatchers.Main) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, c[0], c[1], 0)
                .also { tab.view.dispatchTouchEvent(it) }.recycle()
        }
        val trusted = DeviceSupport.pollUntil(3_000) { runBlocking { js("window.clickTrusted") }.takeIf { it != "null" } }
        assertEquals("true", trusted)
    }

    private fun isMostlyRed(png: ByteArray): Boolean {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return false
        var red = 0
        var total = 0
        for (y in 0 until bmp.height step bmp.height / 20) for (x in 0 until bmp.width step bmp.width / 20) {
            val p = bmp.getPixel(x, y)
            total++
            if (Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) < 60) red++
        }
        return red * 2 > total
    }
}
