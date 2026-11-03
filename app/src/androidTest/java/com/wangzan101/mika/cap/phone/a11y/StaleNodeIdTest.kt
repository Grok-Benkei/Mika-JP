package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実機テスト: この番号は、対応するスナップショットでのみ有効です。ページが変更されると、古い番号を取得できなくなり、ref_expired になります。 */
@RunWith(AndroidJUnit4::class)
class StaleNodeIdTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun oldIdAfterNavigationFails() = runBlocking<Unit> {
        val screen = agent.screen
        assertTrue(screen.openApp(SETTINGS) is ActionResult.Ok)
        val home = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.clickable && it.name.startsWith("蓝牙") } }
        }!!
        val bluetooth = home.elements.first { it.clickable && it.name.startsWith("蓝牙") }
        val wlan = home.elements.first { it.clickable && it.name.startsWith("WLAN") }

        val went = screen.tapRef(wlan.ref, home.snapshotId)
        assertTrue("$went", went is ActionResult.Ok && went.changed == true)
        val page = screen.snapshot()

        // ホームページの「Bluetooth」には、WLAN ページに対応する要素がありません
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef(bluetooth.ref, home.snapshotId))
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef("@e1", "no-such-snapshot"))
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef("@e${page.elements.size + 1}", page.snapshotId))
    }
}
