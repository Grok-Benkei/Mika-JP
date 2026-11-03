package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機テスト: バックグラウンド プロセスにより、ホーム画面にダイアログ スタイルのページが開きます (ログイン確認やアカウント確認など、ユーザーがホーム画面をクリックして確認する必要があるポップアップ ウィンドウ)。
 * この種のポップアップ ウィンドウは、ユーザーのホーム画面に残しておく必要があります。ミカの画面が隠れたり、背景画面に移動したりしないでください。
 */
@RunWith(AndroidJUnit4::class)
class DialogSpillDeviceTest {
    // はタスク内と同じで、画面のサイズと密度はメイン画面と同じです。移動されたページはサイズの変更によって再構築される必要はありません。
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()

    @After
    fun tearDown() {
        // 失敗するとポップアップ ウィンドウがホーム画面に残る場合があります。閉じてください。
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        agent.release()
    }

    /** メイン画面上の各ウィンドウのタイプ、パッケージ、および範囲: ダイアログ スタイルのウィンドウは、明らかに画面全体よりも小さいです。 */
    private fun dumpMainWindows(label: String) {
        val service = DeviceSupport.service()
        for (w in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val b = Rect()
            w.getBoundsInScreen(b)
            println("$label window type=${w.type} pkg=${w.root?.packageName} bounds=$b")
        }
    }

    @Test
    fun dialogOnTheMainScreenIsLeftForTheUser() = runBlocking<Unit> {
        // まずはホーム画面にあるこのようなダイアログボックスのウィンドウを見てください(美香のアクションなし)
        DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${DialogSpillTestActivity::class.java.name}")
        DeviceSupport.pollUntil(5_000) {
            shell.topActivity(Display.DEFAULT_DISPLAY)?.takeIf { it.contains("DialogSpillTestActivity") }
        }
        dumpMainWindows("dialog")
        DeviceSupport.sh("input keyevent 4")
        delay(500)

        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_DIALOG} ${DialogSpillTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open dialog" } }
        }
        assertNotNull("spill page did not come up", s)
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val vdBefore = shell.topTaskId(vd)
        val since = SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open dialog" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // アシスタントのレコードからホーム画面にポップアップするポップアップ タスクを特定します (タスク番号は削除後も変わりません)。
        val b = shell.mainArrivals(since)
        val packages = b?.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val tasks = b?.getIntArray(MainScreenWatch.KEY_TASK)
        val dialogTask = packages?.indices?.firstOrNull { packages[it] == DeviceSupport.TEST_PACKAGE }?.let { tasks?.get(it) }
        assertNotNull("no arrival on the main screen was reported for the popup", dialogTask)
        dumpMainWindows("after action")

        // は、今度はミカにホーム画面にポップアップ表示されるタスクを処理する機会を与えます (アクションに含まれていないものはすべて、画面を読み取るときに入力されます)
        delay(1_500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? com.wangzan101.mika.core.uistate.ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println(
            "after: opened=$opened main top=${shell.topTaskId(Display.DEFAULT_DISPLAY)} " +
                "dialogTask=$dialogTask stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore, main before $mainBefore)",
        )
        // ポップアップ ウィンドウはまだホーム画面にあるため、ユーザーはクリックして確認できます。
        assertTrue("the dialog was moved off the user's screen", stillThere)
        // ミカの画面は以前と同じです
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the popup was not reported to the model", opened)
        assertEquals(DeviceSupport.TEST_PACKAGE, opened!!.packageName)
        assertTrue("the popup was reported as pulled back: $opened", !opened.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
