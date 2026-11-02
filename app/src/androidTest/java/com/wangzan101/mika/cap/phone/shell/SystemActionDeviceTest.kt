package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.SystemAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機テスト: この電話にシステム アクション インテント用のアプリはありますか (ない場合はスキップしてください。失敗とみなされません)。ある場合は、目覚まし時計のリストを開くと、ミカの画面に表示されます。
 * ユーザーのホーム画面が移動しません。本物の目覚まし時計、タイマー、スケジュールはありません。
 */
@RunWith(AndroidJUnit4::class)
class SystemActionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    private fun handlers(a: SystemAction): List<SystemIntents.Handler> =
        context.packageManager.queryIntentActivities(SystemIntents.toIntent(SystemIntents.spec(a)), PackageManager.MATCH_DEFAULT_ONLY).map {
            SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }

    @Test
    fun intentsResolveOrAreSkipped() {
        val all = listOf(SystemAction.SetAlarm(9, 40), SystemAction.SetTimer(60), SystemAction.ShowAlarms, SystemAction.AddCalendarEvent("t", 0))
        val resolved = all.associateWith { handlers(it) }
        assumeTrue("no app on this phone takes these actions: $resolved", resolved.values.any { it.isNotEmpty() })
        // キャッチできる人なら誰でも直接指定したページを取り出すことができます。
        for ((a, h) in resolved) if (h.isNotEmpty()) assertTrue("$a", SystemIntents.choose(h, null) in h)
    }

    @Test
    fun alarmListOpensOnMikasScreenOnly() = runBlocking<Unit> {
        val h = handlers(SystemAction.ShowAlarms)
        assumeTrue("no clock app takes SHOW_ALARMS", h.isNotEmpty())
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.systemAction(SystemAction.ShowAlarms)
        assertTrue("$r", r.result is ActionResult.Ok)
        assertTrue("${r.handledBy}", r.handledBy in h.map { it.packageName })
        assertEquals(r.handledBy, agent.screen.snapshot().packageName)
        assertEquals(main, DeviceSupport.mainScreenState(shell))
    }
}
