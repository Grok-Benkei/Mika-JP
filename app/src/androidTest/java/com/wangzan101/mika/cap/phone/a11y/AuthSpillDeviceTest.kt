package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機テスト: テスト パッケージはアカウント認証システムとして一時的に登録されます (TestAuthenticatorService を参照してください。リストではデフォルトでオフになっています。ここでオンにして、テスト後にオフにします)。
 * テスト パッケージには、システムのアカウント ログイン サービスと同様に、デスクトップ アイコンがありません。全画面ログイン ページにはダイアログ テーマもパスワード ボックスもありません。
 * excludeFromRecents、形状は通常のアプリ ページと同じです。 「パッケージはデスクトップアイコンのないアカウント認証システムです」という項目のみが認識できます
 * はユーザーが手動で処理する必要があり、ホーム画面に残ったままにする必要があり、Mika の画面に移動することはできません。
 */
@RunWith(AndroidJUnit4::class)
class AuthSpillDeviceTest {
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

    private val authenticatorService = "${DeviceSupport.TEST_PACKAGE}/${TestAuthenticatorService::class.java.name}"

    @After
    fun tearDown() {
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        // 閉じて、オーセンティケータが終了するまで待ちます。テストでテスト パッケージを移動する他のページは、アカウント ログイン ページとして使用できません。
        DeviceSupport.sh("pm disable $authenticatorService")
        DeviceSupport.pollUntil(10_000) { (!testAuthenticatorListed()).takeIf { it } }
        agent.release()
    }

    private fun testAuthenticatorListed(): Boolean {
        val am = AccountManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        return runCatching { am.authenticatorTypes.any { it.packageName == DeviceSupport.TEST_PACKAGE } }.getOrDefault(false)
    }

    /** この使用例が意味を持つようになる前に、テスト パッケージの認証子を開き、それがシステムのアカウント認証子テーブルに表示されるまで待ちます。 */
    private fun registerTestAuthenticator(): Boolean {
        DeviceSupport.sh("pm enable $authenticatorService")
        return DeviceSupport.pollUntil(10_000) { testAuthenticatorListed().takeIf { it } } == true
    }

    /** テスト パッケージには LAUNCHER エントリがありません (リストにありません)。これが基準です。 */
    private fun testPackageHasNoLauncher(): Boolean {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(DeviceSupport.TEST_PACKAGE)
        return pm.queryIntentActivities(launcher, 0).isEmpty()
    }

    @Test
    fun authenticatorFullScreenPageStaysOnTheMainScreen() = runBlocking<Unit> {
        assertTrue("the test package has a launcher entry", testPackageHasNoLauncher())
        assertTrue("the test package is not registered as an account authenticator", registerTestAuthenticator())
        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_AUTH} ${FullScreenAuthTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open auth" } }
        }
        assertNotNull("spill page did not come up", s)
        val vdBefore = shell.topTaskId(vd)
        val since = android.os.SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open auth" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // サービス プロセスがコールドになっている可能性があり、ポップアップ ウィンドウが 1 ～ 2 秒遅れて表示されます。アシスタントの記録に入るまで待ちます
        val b = DeviceSupport.pollUntil(5_000) {
            val arr = shell.mainArrivals(since)
            val pkgs = arr?.getStringArray(MainScreenWatch.KEY_PACKAGE)
            if (pkgs?.contains(DeviceSupport.TEST_PACKAGE) == true) arr else null
        }
        assertNotNull("no arrival on the main screen was reported for the auth page", b)
        delay(500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println("after: opened=$opened stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore)")
        assertTrue("the authenticator page was moved off the user's screen", stillThere)
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the auth page was not reported to the model", opened)
        assertTrue("the auth page was reported as pulled back: $opened",
            !opened!!.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
