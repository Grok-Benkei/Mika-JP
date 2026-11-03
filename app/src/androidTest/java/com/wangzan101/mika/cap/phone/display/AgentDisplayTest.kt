package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 実機テスト: 内蔵アシスタントが実行されている必要があります (ペアリング、ワイヤレス デバッグがオン)。 */
@RunWith(AndroidJUnit4::class)
class AgentDisplayTest {
    // テスト クラス全体で 1 つの接続を共有します。これは、アプリ内で 1 つの接続を長期間保持する使用法と一致しています。
    companion object {
        private const val SETTINGS = "com.android.settings"
        private const val SHELL_PROCESS = "com.wangzan101.mika:helper"
        private lateinit var connection: BuiltinShellConnection

        @BeforeClass
        @JvmStatic
        fun bindShell() {
            val phone = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)
            connection = BuiltinShellConnection(phone.hub) { phone.shellManager.engine.need() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.bind() }
            awaitShell()
        }

        @AfterClass
        @JvmStatic
        fun unbindShell() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.unbind() }
        }

        private fun awaitShell(): IMikaShell =
            DeviceSupport.awaitShell(connection)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = awaitShell()
    private var display: AgentDisplay? = null

    @After
    fun tearDown() {
        display?.release()
    }

    private fun newDisplay() = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)).also { display = it }

    @Test
    fun createsVersionAppropriatePublicDisplay() {
        val d = newDisplay()
        val dm = context.getSystemService(DisplayManager::class.java)
        val shown = dm.getDisplay(d.displayId)
        assertNotNull("display ${d.displayId} not visible to the app", shown)
        assertEquals(0, shown.flags and Display.FLAG_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("display is not trusted", shown.flags and displayFlagTrusted() != 0)
        }
    }

    @Test
    fun startsSettingsOnDisplay() {
        val d = newDisplay()
        assertEquals(ShellService.START_OK, shell.startApp(d.displayId, SETTINGS))
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }
        assertNotNull("no task on display ${d.displayId}", task)
    }

    @Test
    fun startAppRefusesAppVisibleOnMainScreen() {
        val d = newDisplay()
        shellCommand("am start -W -a android.settings.SETTINGS --display 0")
        try {
            val mainTop = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != -1 } }
            assertEquals(ShellService.START_IN_USE_ON_MAIN, shell.startApp(d.displayId, SETTINGS))
            Thread.sleep(1_000)
            assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals(-1, shell.topTaskId(d.displayId))
        } finally {
            // ホーム画面で開いた設定をテストします。デスクトップに戻って終了します。これにより、アニメーションの終了中に「ホーム画面が表示される」ことが後続の使用例に影響を与えなくなります。
            shellCommand("input -d 0 keyevent KEYCODE_HOME")
            shellCommand("am force-stop $SETTINGS")
        }
    }

    @Test
    fun frameArrives() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val png = pollUntil(2_000) { d.latestFramePng() }
        assertNotNull(png)
        // PNG ファイルのヘッダー
        assertEquals(0x89.toByte(), png!![0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun framesFlowEmits() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val frame = withTimeoutOrNull(2_000) { d.frames.filterNotNull().first() }
        assertNotNull("no preview frame within 2s", frame)
        assertTrue("preview width ${frame!!.width} > 360", frame.width <= 360)    }

    @Test
    fun releaseClearsFrames() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        withTimeout(2_000) { d.frames.filterNotNull().first() }
        d.release()
        assertEquals(null, d.frames.value)
    }

    @Test
    fun appOrientationUpdatesPreviewScreenshotAndScreenCoordinates() = runBlocking {
        val d = newDisplay()
        val main = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mainRotation = main.rotation
        val mainState = DeviceSupport.mainScreenState(shell)
        val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d))
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            shellCommand("am start -W --display ${d.displayId} -f 0x30000000 " +
                "-n $testPackage/${RotationTestActivity::class.java.name} --ei orientation $orientation")
            val landscape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            val width = if (landscape) 1600 else 720
            val height = if (landscape) 720 else 1600
            val previewWidth = if (landscape) 800 else 360
            val preview = pollUntil(5_000) {
                d.frames.value?.takeIf { it.width == previewWidth && it.height == height * previewWidth / width && uprightCorners(it) }
            }
            assertNotNull("preview did not follow orientation $orientation: logical=${d.width}x${d.height}, " +
                "preview=${d.frames.value?.let { "${it.width}x${it.height}" }}", preview)
            assertEquals(width, d.width)
            assertEquals(height, d.height)
            val snapshot = screen.snapshot()
            assertEquals(width, snapshot.width)
            assertEquals(height, snapshot.height)
            val png = d.latestFramePng()!!
            val image = BitmapFactory.decodeByteArray(png, 0, png.size)
            try {
                assertEquals(width, image.width)
                assertEquals(height, image.height)
                assertTrue("PNG corners for orientation $orientation", uprightCorners(image))
            } finally { image.recycle() }
            assertTrue(shell.injectTap(d.displayId, width * 3 / 4, height * 3 / 4))
            assertNotNull("logical tap missed the bottom-right quadrant", pollUntil(3_000) {
                val image = d.latestFrameBitmap() ?: return@pollUntil null
                try { true.takeIf { nearColor(image.getPixel(width * 3 / 4, height * 3 / 4), Color.CYAN) } }
                finally { image.recycle() }
            })
            assertEquals("main display rotated", mainRotation, main.rotation)
            assertEquals("main screen focus changed", mainState, DeviceSupport.mainScreenState(shell))
        }
    }

    private fun uprightCorners(frame: Bitmap): Boolean =
        nearColor(frame.getPixel(frame.width / 4, frame.height / 4), Color.RED) &&
            nearColor(frame.getPixel(frame.width * 3 / 4, frame.height / 4), Color.GREEN) &&
            nearColor(frame.getPixel(frame.width / 4, frame.height * 3 / 4), Color.BLUE) &&
            nearColor(frame.getPixel(frame.width * 3 / 4, frame.height * 3 / 4), Color.YELLOW)

    // サーフェス合成と GPU 回転により、チャネル 255 が 254 に変更される場合があります。量子化誤差を考慮しても、4 つの象限は厳密に区別されます。
    private fun nearColor(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 4 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 4 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 4

    @Test
    fun movesTaskBetweenVirtualDisplays() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }!!
        // ユーザーのホーム画面に触れずに 2 つの仮想画面間を移動します
        val other = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            assertTrue(shell.moveTask(task, other.displayId))
            assertEquals(task, pollUntil(3_000) { shell.topTaskId(other.displayId).takeIf { it == task } })
            assertNotEquals(task, shell.topTaskId(d.displayId))
        } finally {
            other.release()
        }
    }

    @Test
    fun coldStartDoesNotKillAppWithTaskOnAnotherScreen() {
        val d = newDisplay()
        // は、ユーザーのホーム画面に触れることなく、別の仮想画面を使用して「ミカ画面以外のタスク」を表現します
        val other = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            assertEquals(ShellService.START_OK, shell.startApp(other.displayId, SETTINGS))
            assertNotNull(pollUntil(5_000) { shell.topTaskId(other.displayId).takeIf { it != -1 } })
            val pid = pollUntil(3_000) { shellCommand("pidof $SETTINGS").trim().takeIf { it.isNotEmpty() } }
            assertNotNull(pid)
            // 他の画面もミカの画面です (別のタスクを表します): 直接拒否、強制停止せず、「他のタスクによって占有されています」を返します。
            assertEquals(ShellService.START_IN_USE_BY_OTHER, shell.restartApp(d.displayId, SETTINGS))
            assertEquals("settings process was killed", pid, shellCommand("pidof $SETTINGS").trim())
        } finally {
            other.release()
        }
    }

    @Test
    fun injectsInputOnDisplay() {
        val d = newDisplay()
        val mainTop = shell.topTaskId(Display.DEFAULT_DISPLAY)
        shell.startApp(d.displayId, SETTINGS)
        assertNotNull(pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } })
        val home = stableFrame(d)

        assertTrue(shell.injectTap(d.displayId, 360, 800))
        val afterTap = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(home) } }
        assertNotNull("tap had no visible effect on the virtual display", afterTap)
        val subPage = stableFrame(d)

        assertTrue(shell.injectKey(d.displayId, KeyEvent.KEYCODE_BACK))
        val afterBack = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(subPage) } }
        assertNotNull("BACK had no visible effect on the virtual display", afterBack)

        assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun mutesAndRestoresAudio() {
        val before = shellCommand("appops get $SETTINGS PLAY_AUDIO").trim()
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertTrue(shellCommand("appops get $SETTINGS PLAY_AUDIO").contains("PLAY_AUDIO: ignore"))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(before, shellCommand("appops get $SETTINGS PLAY_AUDIO").trim())
    }

    /** アシスタント プロセスが強制終了された後、ウォッチドッグはアシスタントを再起動し、利用可能な新しいサービスを取得するために接続します。 */
    @Test
    fun reconnectsAfterShellProcessDies() {
        val before = shell
        val pid = shellPid()
        assertNotNull("no $SHELL_PROCESS process", pid)
        shellCommand("kill $pid")
        val after = pollUntil(30_000) {
            connection.shell.value?.takeIf { it.asBinder() != before.asBinder() && it.asBinder().pingBinder() }
        }
        assertNotNull("shell did not come back after its process died", after)
        assertNotEquals(pid, shellPid())
        val d = AgentDisplay(after!!, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)).also { display = it }
        assertTrue(d.displayId >= 0)
    }

    private fun shellPid(): Int? = shellCommand("ps -A -o PID,NAME").lines()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1] == SHELL_PROCESS }
        ?.get(0)?.toIntOrNull()

    private fun shellCommand(cmd: String): String {
        return DeviceSupport.sh(cmd)
    }

    /** 画像が静止するまで待ってから (500 ミリ秒離れた 2 つのフレームは同じです)、このフレームに戻ります。 */
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

    // Display.FLAG_TRUSTED は隠し定数 (1 shl 7) であり、その値は反射が取得できない場合に使用されます。
    private fun displayFlagTrusted(): Int =
        runCatching { Display::class.java.getField("FLAG_TRUSTED").getInt(null) }.getOrDefault(1 shl 7)

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(50)
        }
        return block()
    }
}
