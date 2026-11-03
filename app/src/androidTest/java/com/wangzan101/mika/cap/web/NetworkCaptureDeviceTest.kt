package com.wangzan101.mika.cap.web

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.WaitKind
import com.wangzan101.mika.cap.web.cdp.NetworkTracker
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume
import com.wangzan101.mika.cap.phone.PhoneServices

/**
 * 実機テスト: Web ページのタグが DevTools によって記録され、アクション後にネットワークがアイドル状態になり、フェッチの JSON テキストが取得され、web_wait_for のさまざまな条件が取得されます。
 * ページとインターフェイスはすべて、テスト対象の WebViewAssetLoader のパス プロセッサによって提供されます (https のローカルの偽のドメイン名は実際のネットワークを経由せず、プレーン テキスト HTTP によって制限されません)。
 * DevTools セッションは、テスト独自の接続プロセスのデバッグ ソケットによって確立され、[devtools.provider] に渡されます。
 */
@RunWith(AndroidJUnit4::class)
class NetworkCaptureDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** このテストは私自身のものです。セッション ソースと接続方法を変更しても、他のテストには影響しません。 */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var screen: WebScreen
    private var session: CdpSession? = null

    private val socketName = "webview_devtools_remote_${Process.myPid()}"
    private val transport = CdpTransport { name -> LocalStream(name) }

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val loader = WebViewAssetLoader.Builder().addPathHandler("/net/", WebViewAssetLoader.PathHandler { serve(it) }).build()
            tab = WebTab(ctx, "t1", "task-1", listOf(loader), devtools = devtools)
            host.attach(tab)
        }
        runBlocking { assertTrue(tab.load("https://appassets.androidplatform.net/net/page.html")) }
        session = runBlocking { connect() }
        devtools.provider = { if (it === tab) session else null }
        screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
    }

    @After
    fun tearDown() {
        NetworkTracker.release(tab)
        session?.close()
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    @Test
    fun tapWaitsForFetchAndCapturesJson() = runBlocking<Unit> {
        val s = screen.snapshot()
        val load = s.elements.first { it.resourceId == "#load" }
        val r = screen.tapRef(load.ref, s.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(true, (r as ActionResult.Ok).settled)
        // は、インターフェイスが戻り、ページが書き込まれるまで自動的に待機します。
        assertEquals("\"n=42\"", js("document.getElementById('out').textContent"))

        val list = screen.captures("data.json", null, sinceLastAction = true)
        assertEquals(1, list.size)
        val c = list.single()
        assertEquals(CapturedResponse.State.DONE, c.state)
        assertEquals(200, c.status)
        assertEquals("application/json", c.mime)
        val got = screen.capture(c.id)
        assertNotNull(got)
        assertEquals(DATA, got!!.body)
    }

    @Test
    fun hangingRequestHitsTheCap() = runBlocking<Unit> {
        val s = screen.snapshot()
        val t0 = SystemClock.uptimeMillis()
        val r = screen.tapRef(s.elements.first { it.resourceId == "#hang" }.ref, s.snapshotId) as ActionResult.Ok
        val took = SystemClock.uptimeMillis() - t0
        assertEquals(false, r.settled)
        assertTrue("took $took", took < 9_000)
        assertTrue(screen.captures("hang", null, false).any { it.state == CapturedResponse.State.PENDING })
    }

    @Test
    fun waitConditions() = runBlocking<Unit> {
        assertTrue(screen.waitFor(WaitKind.NETWORK_IDLE, null, 3_000).met)
        assertTrue(screen.waitFor(WaitKind.URL_CONTAINS, "page.html", 1_000).met)
        js("setTimeout(function(){document.getElementById('out').textContent='later'}, 700)")
        val w = screen.waitFor(WaitKind.TEXT_PRESENT, "later", 5_000)
        assertTrue(w.met)
        assertTrue("${w.waitedMs}", w.waitedMs >= 500)
        assertFalse(screen.waitFor(WaitKind.TEXT_ABSENT, "later", 800).met)
    }

    @Test
    fun withoutSessionBehavesAsBefore() = runBlocking<Unit> {
        devtools.provider = { null }
        val s = screen.snapshot()
        val r = screen.tapRef(s.elements.first { it.resourceId == "#load" }.ref, s.snapshotId) as ActionResult.Ok
        assertEquals(null, r.settled)
        assertTrue(runCatching { screen.captures(null, null, false) }.exceptionOrNull() is com.wangzan101.mika.core.cap.web.DevtoolsUnavailable)
    }

    // ---------- 接続 ----------

    /** このラベルが付いたページが、このプロセスのデバッグ ソケットで見つかりました。まずページにマークを書き込み、次にそれを 1 つずつ読み戻します。 */
    private suspend fun connect(): CdpSession {
        val mark = UUID.randomUUID().toString()
        js("window.__netTestMark='$mark'")
        val targets = CdpDiscovery.list(transport, socketName).filter { it.type == "page" && it.webSocketDebuggerUrl != null }
        for (t in targets) {
            val s = CdpSession.connect(transport, socketName, CdpDiscovery.webSocketPath(t.webSocketDebuggerUrl!!))
            val v = runCatching { (s.evaluate("window.__netTestMark", timeoutMs = 3_000)["value"] as? JsonPrimitive)?.content }.getOrNull()
            if (v == mark) return s
            s.close()
        }
        error("no DevTools target for the tab among ${targets.map { it.url }}")
    }

    private class LocalStream(name: String) : CdpStream {
        private val sock = LocalSocket().apply { connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
        override val input = sock.inputStream
        override val output = sock.outputStream
        override fun close() {
            runCatching { sock.shutdownInput() }
            sock.close()
        }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun serve(path: String): WebResourceResponse {
        val (type, body) = when (path.substringBefore('?')) {
            "page.html" -> "text/html" to PAGE
            "data.json" -> {
                Thread.sleep(800)
                "application/json" to DATA
            }
            "hang" -> {
                Thread.sleep(8_000)
                "text/plain" to "late"
            }
            else -> "text/plain" to "not found"
        }
        return WebResourceResponse(type, "utf-8", ByteArrayInputStream(body.toByteArray()))
    }

    private companion object {
        const val DATA = """{"ok":true,"n":42}"""
        val PAGE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width"><title>net</title></head><body>
            <button id="load" onclick="fetch('/net/data.json').then(function(r){return r.json()}).then(function(j){document.getElementById('out').textContent='n='+j.n})">加载数据</button>
            <button id="hang" onclick="fetch('/net/hang')">一直等</button>
            <p id="out">空</p>
            </body></html>
        """.trimIndent()
    }
}
