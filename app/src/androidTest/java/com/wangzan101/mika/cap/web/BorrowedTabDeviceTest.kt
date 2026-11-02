package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * 実機テスト: 借用したラベル (ユーザーがブラウザ ページで見ているラベル) がタスクの現在のラベルとして使用されます。タスクを経由せずに、タグ プールと Web ページ ポートを直接取得します。
 * 「ユーザーのタグ」は、タスク プールから借用された別の Web ページ表示 (ブラウザ ページ インターフェイスを置き換える) にハングされており、Web ツールはそれを読み取ってクリックできます。
 * 借用したものはタスクの表示に連動せず、上限としてカウントされません。 URL を開いて新しいタブを開きます。返されてタスクが終了しても破棄されません。ユーザーがページを閉じると、タスクはそれを受け入れて使用し続けます。
 * は内蔵アシスタントを必要とせず、ホーム画面に触れません。
 */
@RunWith(AndroidJUnit4::class)
class BorrowedTabDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var userHost: WebHost
    private lateinit var pool: WebTabPool
    private lateinit var userTab: WebTab
    private val task = "task-borrow"

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val loaders = listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build())
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            userHost = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            pool = WebTabPool(ctx, host, perTask = 2, budget = TabBudget(), assetLoaders = loaders)
            // ユーザーのタグ: プールによって管理されず、独自のディスプレイにハングします (ブラウザ ページのインターフェイスは実マシン上のアクティブ ウィンドウです)
            userTab = WebTab(ctx, "browser1", "user-browser", loaders)
            userTab.devtools.setHandOperated(userTab)
            userHost.attach(userTab, desktop = false)
        }
        runBlocking { assertTrue(userTab.load(fixture("inputs.html"))) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            if (!userTab.destroyed) userTab.destroy()
            host.close()
            userHost.close()
        }
    }

    private fun screen() = WebScreen(pool, task, host, SystemClock::uptimeMillis)
    private fun main(block: () -> Unit) = instr.runOnMainSync(block)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String =
        elements.firstOrNull { it.resourceId == resourceId }?.ref ?: error("$resourceId not in ${elements.map { it.resourceId }}")

    @Test
    fun webToolsActOnTheBorrowedTabWhereItIs() = runBlocking<Unit> {
        var attached = false
        main { attached = pool.attachExternal(task, userTab) }
        assertTrue(attached)
        main {
            assertSame(userTab, pool.current(task))
            assertTrue(pool.isExternal(userTab.id))
            // はタスクの Web ページ表示にリンクされていないため、上限としてカウントされません。
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
            assertEquals(0, pool.tabCount())
        }
        val s = screen()
        val snap = s.snapshot()
        assertTrue(snap.snapshotId.startsWith("wbrowser1-"))
        val r = s.tapRef(snap.ref("#counter"), snap.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("1", js(userTab, "window.clicks"))
        assertEquals("true", js(userTab, "window.lastTrusted"))
        // スクリーンショットはビュー自体を示しています
        assertNotNull(s.screenshotPng())
        // ユーザーのページを変更せずに URL を開きます: 新しいタブを開きます
        val nav = s.navigate(fixture("elements.html"), newTab = false)
        assertTrue(nav.newTab)
        main {
            assertNotSame(userTab, pool.current(task))
            assertTrue(userTab.view.url!!.endsWith("inputs.html"))
            assertEquals(1, pool.tabCount())
        }
        // は、元に戻しても引き続き使用できます。タスクが終了したら返却すれば破棄されません。
        main { assertSame(userTab, pool.switchTo(task, userTab.id)) }
        main { pool.closeAll(task) }
        main {
            assertFalse(userTab.destroyed)
            assertFalse(pool.isExternal(userTab.id))
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
        }
    }

    @Test
    fun detachReturnsAndAdoptKeepsThePage() = runBlocking<Unit> {
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // 2回借りてカットしただけです
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // 他のタスクに同じ方法を使用しないでください。
        main { assertFalse(pool.attachExternal("other", userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertEquals(null, pool.current(task))
            assertFalse(userTab.destroyed)
            assertFalse(pool.detachExternal(userTab.id))
        }
        // ユーザーがページを閉じます。タスクはそれを通常のラベルとして受け入れ、タスク表示にハングしますが、ページのステータスはまだ存在します。
        js(userTab, "document.getElementById('counter').click()")
        main { assertTrue(pool.attachExternal(task, userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertTrue(pool.adopt(task, userTab, current = true))
            assertSame(userTab, pool.current(task))
            assertFalse(pool.isExternal(userTab.id))
            assertEquals(task, userTab.ownerTaskId)
            assertSame(host, (userTab.view.parent as android.view.View).tag)
            assertEquals(1, pool.tabCount())
        }
        assertEquals("1", js(userTab, "window.clicks"))
        // 集めたものはミッション終了とともに終了となります。
        main { pool.closeAll(task) }
        main { assertTrue(userTab.destroyed) }
    }
}
