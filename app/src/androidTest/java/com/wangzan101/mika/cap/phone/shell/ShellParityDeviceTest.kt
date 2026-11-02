package com.wangzan101.mika.cap.phone.shell

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.shell.IMikaShell

/**
 * シェルの 20 の機能の比較受け入れ ([ShellDestroyParityDeviceTest] で破棄)。
 * シェルは [DeviceSupport.shell] によって提供されます。どのバックエンドが使用されるかはテストされます。両方のバックエンドは同じアサーション セットを実行します。
 * アサートするときは、シェル以外の証拠を調べてみてください。DisplayManager、プローブ ページ自体によって報告される画面とタスク、appops コマンド、テスト プロセスはクリップボードを直接読み取ります。
 * ではシェルを接続する必要があります。ユーザーのホーム画面には影響しません（ミカが作成した画面でページのみが開きます）。
 */
@RunWith(AndroidJUnit4::class)
class ShellParityDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = DeviceSupport.shell()
    private val screens = mutableListOf<ParityScreen>()
    private lateinit var probe: ShellProbe
    private var audioBefore: String = "default"
    private var clipBefore: String? = null

    @Before
    fun setUp() {
        probe = ShellProbe(context)
        // 前の使用例が途中で失敗した場合、復元されるクリップボードがシェル内でハングしたままになる可能性があります。
        shell.restoreClip()
        audioBefore = ParityAudio.mode(SETTINGS)
        clipBefore = ParityClip.text(context)
    }

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.close() } }
        runCatching { probe.close() }
        runCatching { shell.restoreClip() }
        runCatching { shell.setAudioMode(SETTINGS, audioBefore) }
        ParityAudio.set(SETTINGS, audioBefore)
        ParityClip.put(context, clipBefore)
    }

    private fun newScreen(width: Int = 720, height: Int = 1600, dpi: Int = 320) =
        ParityScreen(shell, width, height, dpi).also { screens += it }

    /** am を使用して、指定された画面にプローブ ページを開き (シェルを使用しないメソッドの開始)、報告を待ちます。 */
    private fun probeOn(displayId: Int): ProbeEvent {
        sh("am start --display $displayId -f 0x18000000 -n ${ShellProbe.COMPONENT}")
        return probe.await { it.kind == ShellProbe.RESUMED && it.display == displayId }
            ?: error("probe did not come up on display $displayId: ${probe.all()}")
    }

    private fun focusedProbeOn(displayId: Int): ProbeEvent {
        val up = probeOn(displayId)
        assertNotNull("probe on $displayId never got window focus",
            probe.await { it.kind == ShellProbe.FOCUSED && it.task == up.task })
        return up
    }

    // ---- createDisplay ----

    @Test
    fun createDisplayAddsPublicTrustedDisplayOfRequestedSize() {
        val s = newScreen(720, 1600, 320)
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(s.id)
        assertNotNull("display ${s.id} not visible in DisplayManager", d)
        assertEquals("mika-agent", d.name)
        assertEquals(720, d.mode.physicalWidth)
        assertEquals(1600, d.mode.physicalHeight)
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { d.getRealMetrics(it) }
        assertEquals(320, metrics.densityDpi)
        assertEquals("display must be public", 0, d.flags and Display.FLAG_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("display is not trusted", d.flags and DISPLAY_FLAG_TRUSTED != 0)
        }
    }

    @Test
    fun createDisplayRendersIntoGivenSurface() {
        val s = newScreen()
        probeOn(s.id)
        assertNotNull("no frame reached the surface", pollUntil(5_000) { s.reader.acquireLatestImage()?.also { it.close() } })
    }

    @Test
    fun createDisplayReturnsMinusOneForInvalidSize() {
        val reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2)
        try {
            assertEquals(-1, shell.createDisplay(reader.surface, 0, 0, 320))
        } finally {
            reader.close()
        }
    }

    // ---- releaseDisplay ----

    @Test
    fun releaseDisplayRemovesDisplay() {
        val s = newScreen()
        s.close()
        val dm = context.getSystemService(DisplayManager::class.java)
        assertNotNull("display ${s.id} still present", pollUntil(3_000) { true.takeIf { dm.getDisplay(s.id) == null } })
    }

    /** 画面のみが解放される (finishDisplay を使用せずに削除される) と、画面上のタスクは画面とともにオフになります。タスクが終了したら、[AppsAfterTaskDeviceTest] を参照してください。 */
    @Test
    fun releaseDisplayDestroysTasksOnIt() {
        val s = newScreen()
        val task = probeOn(s.id).task
        s.close()
        assertNotNull("task $task outlived its display", pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test
    fun releaseDisplayIgnoresUnknownIdAndLeavesOthers() {
        val s = newScreen()
        shell.releaseDisplay(Int.MAX_VALUE)
        shell.releaseDisplay(Display.DEFAULT_DISPLAY)
        assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(s.id))
        assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
    }

    // ---- injectTap / injectKey ----

    @Test
    fun injectTapDeliversDownAndUpAtCoordinatesOnThatDisplay() {
        val s = newScreen()
        val up = focusedProbeOn(s.id)
        val main = DeviceSupport.mainScreenState(shell)
        assertTrue(shell.injectTap(s.id, 123, 456))
        val down = probe.await { it.kind == ShellProbe.TOUCH && it.action == MotionEvent.ACTION_DOWN }
        val release = probe.await { it.kind == ShellProbe.TOUCH && it.action == MotionEvent.ACTION_UP }
        assertNotNull("no DOWN reached the probe", down)
        assertNotNull("no UP reached the probe", release)
        for (e in listOf(down!!, release!!)) {
            assertEquals(up.task, e.task)
            assertEquals(s.id, e.display)
            assertTrue("x=${e.x}", kotlin.math.abs(e.x - 123) <= 1)
            assertTrue("y=${e.y}", kotlin.math.abs(e.y - 456) <= 1)
        }
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun injectStrokeDownAtStartUpAtEndAfterItsDuration() {
        val s = newScreen()
        val up = focusedProbeOn(s.id)
        val main = DeviceSupport.mainScreenState(shell)
        val xs = intArrayOf(100, 120, 160, 200)
        val ys = intArrayOf(600, 500, 400, 300)
        val times = longArrayOf(0, 60, 120, 180)
        val started = System.currentTimeMillis()
        assertTrue(shell.injectStroke(s.id, xs, ys, times))
        // 入金完了後の返却: 少なくともこの時間が経過しました
        assertTrue(System.currentTimeMillis() - started >= 170)
        val down = probe.await { it.kind == ShellProbe.TOUCH && it.action == MotionEvent.ACTION_DOWN }
        val release = probe.await { it.kind == ShellProbe.TOUCH && it.action == MotionEvent.ACTION_UP }
        assertNotNull("no DOWN reached the probe", down)
        assertNotNull("no UP reached the probe", release)
        assertEquals(up.task, down!!.task)
        assertEquals(s.id, down.display)
        assertTrue("down ${down.x},${down.y}", kotlin.math.abs(down.x - 100) <= 1 && kotlin.math.abs(down.y - 600) <= 1)
        assertTrue("up ${release!!.x},${release.y}", kotlin.math.abs(release.x - 200) <= 1 && kotlin.math.abs(release.y - 300) <= 1)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun injectStrokeRefusesOtherDisplaysAndBadTimes() {
        val s = newScreen()
        assertFalse(shell.injectStroke(Display.DEFAULT_DISPLAY, intArrayOf(1, 2), intArrayOf(1, 2), longArrayOf(0, 10)))
        assertFalse(shell.injectStroke(s.id, intArrayOf(1, 2), intArrayOf(1, 2), longArrayOf(10, 0)))
        assertFalse(shell.injectStroke(s.id, intArrayOf(1), intArrayOf(1), longArrayOf(0)))
        assertFalse(shell.injectStroke(s.id, intArrayOf(1, 2), intArrayOf(1), longArrayOf(0, 10)))
    }

    @Test
    fun injectKeyDeliversAllowedKeyToThatDisplay() {
        val s = newScreen()
        focusedProbeOn(s.id)
        assertTrue(shell.injectKey(s.id, KeyEvent.KEYCODE_SPACE))
        assertNotNull(probe.await { it.kind == ShellProbe.KEY && it.keyCode == KeyEvent.KEYCODE_SPACE && it.action == KeyEvent.ACTION_DOWN && it.display == s.id })
        assertNotNull(probe.await { it.kind == ShellProbe.KEY && it.keyCode == KeyEvent.KEYCODE_SPACE && it.action == KeyEvent.ACTION_UP })
    }

    @Test
    fun injectKeyRefusesKeysOutsideTheAllowlist() {
        val s = newScreen()
        focusedProbeOn(s.id)
        // は、仮想画面上で無害なキーを押すだけで済みます。実機で電源や音量などを試しないでください。 タスクがあるときに
        assertFalse(shell.injectKey(s.id, KeyEvent.KEYCODE_HOME))
        assertFalse(shell.injectKey(s.id, KeyEvent.KEYCODE_SEARCH))
        Thread.sleep(1_000)
        assertTrue("refused key still arrived: ${probe.all()}", probe.all().none { it.kind == ShellProbe.KEY })
    }

    // ---- startApp ----

    @Test
    fun startAppLaunchesPackageOnGivenDisplay() {
        val s = newScreen()
        val main = DeviceSupport.mainScreenState(shell)
        assertEquals(ShellService.START_OK, shell.startApp(s.id, TEST_PACKAGE))
        val up = probe.await { it.kind == ShellProbe.RESUMED }
        assertNotNull("launch entry did not come up", up)
        assertEquals(s.id, up!!.display)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun startAppRefusesDisplayItDidNotCreate() {
        assertEquals(ShellService.START_ERROR, shell.startApp(Display.DEFAULT_DISPLAY, TEST_PACKAGE))
        Thread.sleep(1_000)
        assertTrue("probe started anyway: ${probe.all()}", probe.all().isEmpty())
    }

    @Test
    fun startAppReportsMissingPackage() {
        val s = newScreen()
        assertEquals(ShellService.START_NOT_FOUND, shell.startApp(s.id, NO_SUCH_APP))
    }

    // ---- startView ----

    @Test
    fun startViewOpensLinkOnGivenDisplay() {
        val s = newScreen()
        val main = DeviceSupport.mainScreenState(shell)
        assertEquals(ShellService.START_OK, shell.startView(s.id, null, ShellProbe.LINK, null))
        val up = probe.await { it.kind == ShellProbe.RESUMED }
        assertNotNull("link target did not come up", up)
        assertEquals(s.id, up!!.display)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun startViewRefusesBlockedLink() {
        val s = newScreen()
        assertEquals(ShellService.START_INVALID_LINK, shell.startView(s.id, null, "tel:10086", null))
    }

    @Test
    fun startViewReportsUnresolvedLink() {
        val s = newScreen()
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(s.id, null, "mika-no-such-scheme://page/1", null))
    }

    @Test
    fun startViewRefusesDisplayItDidNotCreate() {
        assertEquals(ShellService.START_ERROR, shell.startView(Display.DEFAULT_DISPLAY, null, ShellProbe.LINK, null))
        Thread.sleep(1_000)
        assertTrue("probe started anyway: ${probe.all()}", probe.all().isEmpty())
    }

    // ---- restartApp ----

    @Test
    fun restartAppKillsAndRelaunchesOnDisplay() {
        val s = newScreen()
        // が別の場所 (バックグラウンドでも) に設定されている場合、コールド スタートは通常のスタートにダウングレードされます。まずそれをクリアして、必ずプロセスを終了してください。
        sh("am force-stop $SETTINGS")
        assertEquals(ShellService.START_OK, shell.startApp(s.id, SETTINGS))
        assertNotNull(pollUntil(5_000) { shell.topTaskId(s.id).takeIf { it != -1 } })
        val before = pollUntil(3_000) { pidOf(SETTINGS) }
        assertNotNull("settings has no process", before)
        assertEquals(ShellService.START_OK, shell.restartApp(s.id, SETTINGS))
        val after = pollUntil(5_000) { pidOf(SETTINGS)?.takeIf { it != before } }
        assertNotNull("settings process was not restarted", after)
        val top = pollUntil(5_000) { shell.topTaskId(s.id).takeIf { it != -1 } }
        assertNotNull("settings not back on display ${s.id}", top)
        assertEquals(SETTINGS, shell.packageOfTask(top!!))
    }

    @Test
    fun restartAppRefusesDisplayItDidNotCreate() {
        val s = newScreen()
        assertEquals(ShellService.START_OK, shell.startApp(s.id, SETTINGS))
        val pid = pollUntil(5_000) { pidOf(SETTINGS) }
        assertNotNull(pid)
        assertEquals(ShellService.START_ERROR, shell.restartApp(Display.DEFAULT_DISPLAY, SETTINGS))
        assertEquals("settings process was killed", pid, pidOf(SETTINGS))
    }

    // ---- moveTask ----

    @Test
    fun moveTaskMovesTaskToOtherDisplay() {
        val a = newScreen(720, 1600, 320)
        // 2 つの画面のサイズは異なります。移動後、プローブページが再登録されます。
        val b = newScreen(1080, 1920, 480)
        val task = probeOn(a.id).task
        probe.clear()
        assertTrue(shell.moveTask(task, b.id))
        val moved = probe.await { it.kind == ShellProbe.RESUMED && it.display == b.id }
        assertNotNull("probe did not report display ${b.id}: ${probe.all()}", moved)
        assertEquals(task, moved!!.task)
        assertEquals(-1, pollUntil(3_000) { shell.topTaskId(a.id).takeIf { it == -1 } })
    }

    // ---- topTaskId / displayOfTask / displayOfPackage / packageOfTask ----

    @Test
    fun topTaskIdIsTheTaskShownOnThatDisplay() {
        val s = newScreen()
        val up = probeOn(s.id)
        assertEquals(up.task, pollUntil(3_000) { shell.topTaskId(s.id).takeIf { it == up.task } })
    }

    @Test
    fun topTaskIdIsMinusOneOnEmptyDisplay() {
        val s = newScreen()
        assertEquals(-1, shell.topTaskId(s.id))
    }

    @Test
    fun displayOfTaskMatchesDisplayTheActivityReports() {
        val s = newScreen()
        val up = probeOn(s.id)
        assertEquals(up.display, shell.displayOfTask(up.task))
    }

    @Test
    fun displayOfTaskIsMinusOneForMissingTask() {
        assertEquals(-1, shell.displayOfTask(Int.MAX_VALUE))
    }

    @Test
    fun displayOfPackageFindsTheAgentDisplay() {
        val s = newScreen()
        probeOn(s.id)
        assertEquals(s.id, shell.displayOfPackage(TEST_PACKAGE))
    }

    @Test
    fun displayOfPackageIsMinusOneWhenNotOnAnAgentDisplay() {
        newScreen()
        assertEquals(-1, shell.displayOfPackage(NO_SUCH_APP))
    }

    @Test
    fun packageOfTaskIsThePackageOfTheTopActivity() {
        val s = newScreen()
        val up = probeOn(s.id)
        assertEquals(TEST_PACKAGE, shell.packageOfTask(up.task))
    }

    @Test
    fun packageOfTaskIsNullForMissingTask() {
        assertNull(shell.packageOfTask(Int.MAX_VALUE))
    }

    // ---- getAudioMode / setAudioMode / setAudioMuted ----

    // は開始モードを想定していません。新しくインストールされたパッケージのほとんどはデフォルトです。許可をデフォルトと同じに設定すると、appops もデフォルトを報告します。常にベースラインと比較

    @Test
    fun getAudioModeMatchesAppOps() {
        assertEquals(ParityAudio.mode(SETTINGS), shell.getAudioMode(SETTINGS))
        ParityAudio.set(SETTINGS, "ignore")
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        ParityAudio.set(SETTINGS, audioBefore)
        assertEquals(ParityAudio.mode(SETTINGS), shell.getAudioMode(SETTINGS))
    }

    @Test
    fun getAudioModeIsNullForUnknownPackage() {
        assertNull(shell.getAudioMode(NO_SUCH_APP))
    }

    @Test
    fun setAudioModeAppliesMode() {
        assertTrue(shell.setAudioMode(SETTINGS, "ignore"))
        assertEquals("ignore", ParityAudio.mode(SETTINGS))
        assertTrue(shell.setAudioMode(SETTINGS, audioBefore))
        assertEquals(audioBefore, ParityAudio.mode(SETTINGS))
    }

    @Test
    fun setAudioModeRejectsMalformedMode() {
        val baseline = ParityAudio.mode(SETTINGS)
        assertFalse(shell.setAudioMode(SETTINGS, "ignore; echo x"))
        assertFalse(shell.setAudioMode(SETTINGS, "IGNORE"))
        assertEquals(baseline, ParityAudio.mode(SETTINGS))
    }

    @Test
    fun setAudioMutedIgnoresThenRestoresOriginalMode() {
        val baseline = ParityAudio.mode(SETTINGS)
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertEquals("ignore", ParityAudio.mode(SETTINGS))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(baseline, ParityAudio.mode(SETTINGS))
    }

    @Test
    fun setAudioMutedTwiceStillRestoresModeBeforeFirstMute() {
        val baseline = ParityAudio.mode(SETTINGS)
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(baseline, ParityAudio.mode(SETTINGS))
    }

    @Test
    fun setAudioMutedFailsForUnknownPackage() {
        assertFalse(shell.setAudioMuted(NO_SUCH_APP, true))
    }

    // ---- setClipText / restoreClip / savedClipText / restoreClipText ----

    @Test
    fun setClipTextPutsSensitiveTextOnClipboard() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        assertEquals("parity-ours", ParityClip.text(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = ParityClip.description(context)?.extras
            assertTrue("clip is not marked sensitive", extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        }
    }

    @Test
    fun restoreClipPutsBackUserText() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        // 2 回連続でプレイしても、復元は初回以前のユーザーのままです。
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours-2"))
        shell.restoreClip()
        assertEquals("parity-user", ParityClip.text(context))
    }

    @Test
    fun restoreClipClearsWhenUserClipboardWasEmpty() {
        ParityClip.put(context, null)
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        shell.restoreClip()
        assertNull("Mika's text leaked", ParityClip.text(context))
    }

    @Test
    fun restoreClipLeavesTextUserCopiedMeanwhile() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        ParityClip.put(context, "parity-user-new")
        shell.restoreClip()
        assertEquals("parity-user-new", ParityClip.text(context))
    }

    @Test
    fun savedClipTextIsNullWithNothingPending() {
        assertNull(shell.savedClipText())
    }

    @Test
    fun savedClipTextIsUserTextWhilePendingAndClearedAfterRestore() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        assertEquals("parity-user", shell.savedClipText())
        shell.restoreClip()
        assertNull(shell.savedClipText())
    }

    @Test
    fun savedClipTextIsEmptyWhenUserClipboardWasEmpty() {
        ParityClip.put(context, null)
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        assertEquals("", shell.savedClipText())
    }

    @Test
    fun restoreClipTextPutsBackOriginalWhenClipIsOurs() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        shell.restoreClipText("parity-user", "parity-ours")
        assertEquals("parity-user", ParityClip.text(context))
    }

    @Test
    fun restoreClipTextClearsWhenOriginalWasEmpty() {
        ParityClip.put(context, null)
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        shell.restoreClipText("", "parity-ours")
        assertNull("Mika's text leaked", ParityClip.text(context))
    }

    @Test
    fun restoreClipTextLeavesClipThatIsNotOurs() {
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))
        ParityClip.put(context, "parity-user-new")
        shell.restoreClipText("parity-user", "parity-ours")
        assertEquals("parity-user-new", ParityClip.text(context))
    }

    private fun pidOf(pkg: String): String? = sh("pidof $pkg").trim().takeIf { it.isNotEmpty() }

    companion object {
        const val NO_SUCH_APP = "com.wangzan101.no.such.app"
        // Display.FLAG_TRUSTED は非表示の定数です
        val DISPLAY_FLAG_TRUSTED: Int =
            runCatching { Display::class.java.getField("FLAG_TRUSTED").getInt(null) }.getOrDefault(1 shl 7)
    }
}

/** は、シェルを通じてこのプロセスの画面と画面に直接入る ImageReader をテストします。 */
class ParityScreen(private val shell: IMikaShell, width: Int, height: Int, dpi: Int) : AutoCloseable {
    val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val id: Int = shell.createDisplay(reader.surface, width, height, dpi)
    private var closed = false

    init {
        if (id < 0) {
            reader.close()
            error("createDisplay failed")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            shell.releaseDisplay(id)
        } finally {
            reader.close()
        }
    }
}

/** は、appops コマンドを直接使用して、シェルを経由せずに PLAY_AUDIO の読み取りと書き込みを行います。 */
object ParityAudio {
    fun mode(pkg: String): String =
        Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(sh("appops get $pkg PLAY_AUDIO"))?.groupValues?.get(1) ?: "default"

    fun set(pkg: String, mode: String) {
        sh("appops set $pkg PLAY_AUDIO $mode")
    }
}

/**
 * テスト プロセスは、システム クリップボードを直接読み書きします。バックグラウンドでクリップボードを読み取るには、シェルの権限を一時的に借用する READ_CLIPBOARD_IN_BACKGROUND が必要です。
 * は、ユーザーの元のコンテンツをプレーン テキストでのみ保存および復元します。
 */
object ParityClip {
    private const val READ_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

    private fun manager(context: Context) = context.getSystemService(ClipboardManager::class.java)

    private fun <T> asShell(block: () -> T): T {
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ua.adoptShellPermissionIdentity(READ_IN_BACKGROUND)
        return try {
            block()
        } finally {
            ua.dropShellPermissionIdentity()
        }
    }

    /** クリップボード上のテキスト。空の場合は null が返されます。 */
    fun text(context: Context): String? = asShell {
        manager(context).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    fun description(context: Context): ClipDescription? = asShell { manager(context).primaryClipDescription }

    /** ユーザー独自のテキストを挿入します。 null はクリアを意味します。 */
    fun put(context: Context, text: String?) {
        val cm = manager(context)
        if (text == null) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("user", text))
        pollUntil(2_000) { true.takeIf { text(context) == text } }
    }
}
