package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.tool.LinkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実マシン: ホワイトリストの設定ページはエージェントの画面で開くことができ、メイン画面は影響を受けません。アプリで開けないリンクは 5 に戻ります。 */
@RunWith(AndroidJUnit4::class)
class OpenLinkDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun everySettingsActionOpensOnAgentDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val settings = SystemPickers.settingsPackage(InstrumentationRegistry.getInstrumentation().targetContext.packageManager)
        assertNotNull("no settings app on this device", settings)
        val main = DeviceSupport.mainScreenState(shell)
        val failed = mutableListOf<String>()
        for (action in LinkPolicy.SETTINGS_ACTIONS.sorted()) {
            val uri = if (action == LinkPolicy.APP_DETAILS) "package:${DeviceSupport.TEST_PACKAGE}" else null
            val r = agent.screen.openLink(action, uri, null)
            // 一部のシステムでは、元の設定ページの一部が削除されています。解析の失敗は、失敗ではなくダウングレードとみなされます。開いた場合は、システム設定に配置する必要があります。
            if (r is ActionResult.Failed && r.reason == "link_unresolved") continue
            val top = agent.screen.snapshot().packageName
            if (r !is ActionResult.Ok || top != settings) failed += "$action -> $r on $top"
            assertEquals("main screen changed after $action", main, DeviceSupport.mainScreenState(shell))
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test
    fun unresolvableUriReturns5() {
        val shell = DeviceSupport.shell()
        val id = agent.display.displayId
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "mika-no-such-scheme://page/1", null))
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "https://example.com/", "com.wangzan101.mika.no.such.app"))
        // シェル側を再度確認してください。ディストリビューターをバイパスして直接調整することはできません。
        assertEquals(ShellService.START_INVALID_LINK, shell.startView(id, null, "tel:10086", null))
        val r = runBlocking { agent.screen.openLink(null, "mika-no-such-scheme://page/1", null) }
        assertEquals(ActionResult.Failed("link_unresolved"), r)
    }
}
