package com.wangzan101.mika.cap.phone.display

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.selfcheck.SelfCheckTargetActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 一部のシステムでは、デフォルトで仮想画面上のアプリが小さなウィンドウで開きます。Mika の画面は全画面モードである必要があります。同時に、画面の解像度だけでなく、アクセス可能な読み取りのための実際のウィンドウ範囲を確認してください。 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayWindowingDeviceTest {
    @Test
    fun testPageFillsVirtualDisplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shell = DeviceSupport.shell()
        val a11y = DeviceSupport.service()
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, context.getSystemService(DisplayManager::class.java))
        val mainTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        try {
            assertEquals(0, shell.startSystemAction(display.displayId, SelfCheckTargetActivity.intent(context), null))
            var lastBounds: Rect? = null
            // 起動アニメーション終了時、小ウィンドウを拡大する途中の最初のフレームを最終的なウィンドウサイズとみなすことができません。
            val bounds = DeviceSupport.pollUntil(5_000) {
                a11y.windowsOn(display.displayId).firstOrNull { it.root?.packageName == context.packageName }
                    ?.let { Rect().apply { it.getBoundsInScreen(this) } }
                    ?.also { lastBounds = it }
                    ?.takeIf {
                        it.left in 0..(display.width / 40) && it.right in (display.width * 39 / 40)..display.width &&
                            it.height() >= display.height * 0.9
                    }
            }
            assertNotNull("window $lastBounds does not fill ${display.width}x${display.height}", bounds)
            assertEquals("main screen task changed", mainTask, shell.topTaskId(Display.DEFAULT_DISPLAY))
        } finally {
            display.release()
        }
    }
}
