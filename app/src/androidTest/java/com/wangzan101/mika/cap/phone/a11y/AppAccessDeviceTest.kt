package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.access.AppAccessDenied
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessDeviceTest {
    @Test fun blockedLaunchIsDeniedWhileShellRemainsAvailable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        val script = "/sdcard/Android/data/${app.packageName}/files/usb-start.sh"
        DeviceSupport.sh("sh $script")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.wangzan101.mika.core.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName)
            app.appAccess.setBlocked(pkg, true)
            try { screen.openApp(pkg); fail("Blocked launch must be denied") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            val command = screen.systemCommand("printf app-access-shell-ok")
            assertEquals(0, command?.exitCode)
            assertTrue(command?.output?.contains("app-access-shell-ok") == true)
            app.appAccess.setBlocked(pkg, false)
            assertNull(app.appAccess.current().denial(pkg))
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }

    /**
     * これまで設定されていないアプリはデフォルトで許可されます。開いて画面を読み取ることができます。タスクの進行中にこれを無効に設定すると、次の画面の読み取りは直ちに拒否されます。
     * は、再度許可された後に再度読み取ることができます。権限により、タスクを再起動することなく、毎回最新のステータスが読み取られます。検証結果を引き継いだ場合、リストが変更されると使用されなくなります。
     */
    @Test fun blockingWhileRunningStopsTheNextRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        DeviceSupport.sh("sh /sdcard/Android/data/${app.packageName}/files/usb-start.sh")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.wangzan101.mika.core.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() })
            app.appAccess.setBlocked(pkg, false)
            app.appAccess.setShareAllowed(pkg, false)
            assertNull("a package nobody configured must be allowed", app.appAccess.current().denial(pkg))
            screen.openApp(pkg)
            screen.checkAppAccess()
            screen.checkScreen()

            app.appAccess.setBlocked(pkg, true)
            try { screen.checkScreen(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            // 画面を読み、この画面に付属するウィンドウの所有権を使用して確認します。これも拒否されました
            try { screen.snapshot(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }

            app.appAccess.setBlocked(pkg, false)
            screen.checkScreen()
            screen.checkAppAccess()
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }
}
