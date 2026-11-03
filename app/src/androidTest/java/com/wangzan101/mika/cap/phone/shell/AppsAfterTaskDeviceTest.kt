package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * タスクが終了したときに、Mika 画面上のアプリをどうするか (IMikaShell.finishDisplay): アプリを静かに放置し、ホーム画面の後ろに移動し、ユーザーの正面にいないようにします。
 * 次回スタートアップ ポータルを開いたときに、同じタスクがミカの画面に戻されます。鳴っていて「閉じる」とマークされている音は、画面とともにオフになります。 keep マークが付いている音も残ります。
 * はハウジングの取り付けが必要です。ユーザーのホーム画面は変更しないでください (フォーカスとフォーカス ウィンドウは変更されず、残りのタスクは表示されません)。
 */
@RunWith(AndroidJUnit4::class)
class AppsAfterTaskDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = DeviceSupport.shell()
    private val screens = mutableListOf<ParityScreen>()
    private lateinit var probe: ShellProbe

    @Before fun setUp() { probe = ShellProbe(context) }

    @After fun tearDown() {
        screens.forEach { runCatching { it.close() } }
        // メイン画面の後ろに残されたプローブ タスク: 新しい画面を元の位置に移動し、画面と一緒に閉じます (つまり、次回開く方法)
        runCatching { ParityScreen(shell, 720, 1600, 320).use { s -> shell.startApp(s.id, TEST_PACKAGE); Thread.sleep(500) } }
        runCatching { probe.close() }
    }

    private fun newScreen() = ParityScreen(shell, 720, 1600, 320).also { screens += it }

    /** 現時点でのメイン画面のフォーカス (トップ フォーカス画面、フォーカス ウィンドウ、フォーカス アクティビティ) には、「トップ タスク」が含まれていません。一部のシステムは、最近の使用状況に応じてタスクを配置します。 */
    private fun mainFocus(): String = DeviceSupport.mainScreenState(shell).substringBefore(" topTask=")

    private fun Bundle.kept() = getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty()
    private fun Bundle.closed() = getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty()

    private fun probeOn(displayId: Int, component: String): Int {
        sh("am start --display $displayId -f 0x18000000 -n $component")
        return (probe.await { it.kind == ShellProbe.RESUMED && it.display == displayId }
            ?: error("probe did not come up on display $displayId: ${probe.all()}")).task
    }

    @Test fun quietAppStaysBehindOnMainAndReopenBringsTheSameTaskBack() {
        val s = newScreen()
        assertEquals(ShellService.START_OK, shell.startApp(s.id, TEST_PACKAGE))
        val task = probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id }?.task ?: error("probe did not come up: ${probe.all()}")
        val before = mainFocus()

        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())
        assertNotNull(result)
        val note = result!!.getString(AppsAfterTask.KEY_NOTE)
        if (note?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) {
            // このシステムは移動できません。画面とともに電源を切る必要があります。
            assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.MoveUnsupported.wire}"), result.closed())
            assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
            return
        }
        assertEquals("note=$note closed=${result.closed()}", listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        // はユーザーには表示されません: 非表示、フォーカスは変化しません。 最近のタスクの
        assertFalse("kept task is visible on the main screen", shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
        assertEquals("main screen focus changed", before, mainFocus())
        // : システム タスク リストにもあります
        // 最近のタスク レコード内の各タスクは、Task{… #<number> …} として書き込まれます。
        assertTrue("kept task is gone from recents", Regex("#$task\\b").containsMatchIn(sh("dumpsys activity recents")))

        // 次回開く: 同じタスクをミカの新しい画面に戻します
        val s2 = newScreen()
        probe.clear()
        assertEquals(ShellService.START_OK, shell.startApp(s2.id, TEST_PACKAGE))
        assertEquals(s2.id, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == s2.id } })
        assertNotNull("probe did not come back on ${s2.id}: ${probe.all()}",
            probe.await { it.kind == ShellProbe.RESUMED && it.display == s2.id && it.task == task })
        assertEquals("main screen focus changed", before, mainFocus())
    }

    @Test fun playingAppIsClosedByDefault() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        // プレーヤーがオーディオ サービスに登録されるまでに時間がかかります。
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())!!
        assertEquals("kept=${result.kept()}", listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Playing.wire}"), result.closed())
        assertNotNull("playing task outlived the display", pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun keepMarkKeepsAPlayingApp() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, arrayOf(TEST_PACKAGE), emptyArray())!!
        if (result.getString(AppsAfterTask.KEY_NOTE)?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) return
        assertEquals(listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        assertFalse(shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
    }

    @Test fun closeMarkClosesAQuietApp() {
        val s = newScreen()
        val task = probeOn(s.id, ShellProbe.COMPONENT)
        val result = shell.finishDisplay(s.id, emptyArray(), arrayOf(TEST_PACKAGE))!!
        assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Marked.wire}"), result.closed())
        assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun refusesDisplaysItDidNotCreate() {
        assertEquals(null, shell.finishDisplay(Display.DEFAULT_DISPLAY, emptyArray(), emptyArray()))
    }

    private companion object {
        const val AUDIO_PROBE = "com.wangzan101.mika.test/com.wangzan101.mika.shell.AudioProbeActivity"
        const val PLAYER_SETTLE_MS = 1_000L
    }
}
