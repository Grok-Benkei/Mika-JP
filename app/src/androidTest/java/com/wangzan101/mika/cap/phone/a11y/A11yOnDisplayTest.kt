package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.graphics.Path
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.a11y.MikaAccessibilityService

/** 実機テスト: 内蔵アシスタントが実行されている必要があり (ペアリング、ワイヤレス デバッグがオン)、Mika アクセシビリティ サービスがオンになっています。 */
@RunWith(AndroidJUnit4::class)
class A11yOnDisplayTest {
    companion object {
        private const val SETTINGS = "com.android.settings"
        private lateinit var connection: BuiltinShellConnection

        @BeforeClass
        @JvmStatic
        fun bindShell() {
            val phone = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)
            connection = BuiltinShellConnection(phone.hub) { phone.shellManager.engine.need() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.bind() }
            awaitShell()
            rebindAccessibility()
        }

        @AfterClass
        @JvmStatic
        fun unbindShell() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.unbind() }
        }

        /**
         * テストを開始すると、システムはアプリを強制的に停止し、ユーザー補助サービスは「クラッシュ」として記録され、自動的に再接続されなくなります。
         * がオンになっている場合は、スタートアップ リストから削除して元に戻し、システムに現在のプロセスへの再バインドを要求します。オンにしないと動きません。
         */
        private fun rebindAccessibility() {
            if (ScreenAccessibility.instance.value != null) return
            val key = "enabled_accessibility_services"
            val mika = "com.wangzan101.mika/${MikaAccessibilityService::class.java.name}"
            val enabled = shellCommand("settings get secure $key").trim().split(':')
            if (mika !in enabled) return
            try {
                shellCommand("settings put secure $key ${enabled.filter { it != mika }.joinToString(":").ifEmpty { "\"\"" }}")
                Thread.sleep(500)
            } finally {
                // 他のアプリのサービスを含む、元の一覧全体を必ず復元する。
                shellCommand("settings put secure $key ${enabled.joinToString(":")}")
            }
        }

        private fun shellCommand(cmd: String): String {
            // 他のユーザー補助サービスを抑制しない。抑制すると、テスト中にMikaのサービスも一時停止する。
            val ua = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val pfd = ua.executeShellCommand(cmd)
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

        private fun awaitShell(): IMikaShell =
            DeviceSupport.awaitShell(connection)
    }

    private val shell: IMikaShell get() = awaitShell()
    private var display: AgentDisplay? = null

    @After
    fun tearDown() {
        display?.release()
    }

    private fun service(): ScreenAccessibility = runBlocking {
        withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
    } ?: error("Mika accessibility service is not enabled")

    private fun settingsOnNewDisplay(): AgentDisplay {
        val d = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)).also { display = it }
        shell.startApp(d.displayId, SETTINGS)
        assertNotNull(pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } })
        return d
    }

    @Test
    fun seesVirtualDisplayWindow() {
        val a11y = service()
        val d = settingsOnNewDisplay()
        val found = pollUntil(5_000) {
            a11y.windowsOn(d.displayId).firstNotNullOfOrNull { w ->
                w.root?.let { root ->
                    (root.findAccessibilityNodeInfosByText("WLAN") + root.findAccessibilityNodeInfosByText("设置"))
                        .firstOrNull()
                }
            }
        }
        assertNotNull("no WLAN/设置 node in windows on display ${d.displayId}", found)
        // メイン画面のウィンドウが仮想画面の一覧に混ざらないこと。
        assertTrue(a11y.windowsOn(d.displayId).none { it.displayId != d.displayId })
    }

    @Test
    fun windowsOnUnknownDisplayIsEmpty() {
        assertEquals(emptyList<Any>(), service().windowsOn(987_654))
    }

    @Test
    fun gestureTapsVirtualDisplayOnly() = runBlocking {
        val a11y = service()
        val mainTop = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val d = settingsOnNewDisplay()
        // タップ可能な一覧項目が表示されるまで待つ。
        val item = pollUntil(5_000) { clickableItem(a11y, d.displayId) }
        assertNotNull("no clickable item on display ${d.displayId}", item)
        val before = stableFrame(d)

        val eventDisplays = async(start = CoroutineStart.UNDISPATCHED) {
            // 一定時間のイベントをすべて集め、メイン画面のイベントが混入していないか確認する。
            val got = mutableListOf<AccessibilityEvent>()
            withTimeoutOrNull(3_000) { a11y.eventsFor(d.displayId).collect { got += it } }
            got
        }
        val path = Path().apply { moveTo(item!!.first.toFloat(), item.second.toFloat()) }
        assertTrue("gesture was cancelled", a11y.gesture(d.displayId, path, 50))

        val changed = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(before) } }
        assertNotNull("tap had no visible effect on the virtual display", changed)
        val events: List<AccessibilityEvent> = eventDisplays.await()
        assertTrue("no accessibility event for display ${d.displayId}", events.isNotEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(
                "main-screen event leaked into display ${d.displayId}",
                events.none { it.displayId == Display.DEFAULT_DISPLAY },
            )
        }
        assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
    }

    /** 最初のタップ可能な項目の、画面上での中心座標。 */
    private fun clickableItem(a11y: ScreenAccessibility, displayId: Int): Pair<Int, Int>? {
        val rect = android.graphics.Rect()
        for (w in a11y.windowsOn(displayId)) {
            val root = w.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                n.getBoundsInScreen(rect)
                // 上部のタイトルバーを避け、中央より下の一覧項目をタップする。
                if (n.isClickable && n.isVisibleToUser && rect.top > 300 && rect.height() in 40..400) {
                    return rect.centerX() to rect.centerY()
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun stableFrame(d: AgentDisplay): ByteArray {
        var last = pollUntil(2_000) { d.latestFramePng() }!!
        repeat(10) {
            Thread.sleep(500)
            val now = d.latestFramePng()!!
            if (now.contentEquals(last)) return now
            last = now
        }
        return last
    }

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
