package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.shell.IMikaShell

/**
 * 実機テスト: 内蔵アシスタントがワイヤレスでデバッグされ、Binder に渡されて、独自の仮想画面にクリックが挿入されます。バインドを解除した後もアシスタントは残り、再バインド後に直接使用できます。
 * 前提条件: Android 12 以降、ワイヤレス デバッグがオンになっています。 2 つのペアのうち 1 つを選択します。
 * - `-e pairingCode <6 位码>` (`-e pairingPort <端口>` を追加可能) では、この時点でシステムの「ペアリング コードを使用したペアリング」ウィンドウが開いているはずです。
 * - すでにミカのキーとペアリングされています: `-e assumePaired true`。
 */
@RunWith(AndroidJUnit4::class)
class BuiltinHelperDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phone = PhoneServices.of(context)
    private val engine = phone.shellManager.engine

    private fun helperUp(): IMikaShell = runBlocking {
        when {
            args.getString("pairingCode") != null && engine.status.value != BuiltinShellStatus.Running ->
                engine.startPairing(args.getString("pairingCode")!!, args.getString("pairingPort")?.toIntOrNull())
            else -> {
                if (args.getString("assumePaired") == "true") {
                    context.getSharedPreferences("builtin_shell", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
                }
                engine.retry()
                assumeTrue("not paired: pass -e pairingCode <code> or -e assumePaired true", engine.status.value != BuiltinShellStatus.NotPaired)
            }
        }
        assertEquals(BuiltinShellStatus.Running, engine.status.value)
        withTimeout(5_000) { phone.hub.shell.filterNotNull().first() }
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun helperRunsAsShellDrivesItsDisplayAndSurvivesUnbind() {
        assumeTrue(Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK)
        val shell = helperUp()
        val ids = shell.identity()
        assertEquals("helper uid", 2000, ids[0])
        assertTrue("helper pid", ids[1] > 0 && ids[1] != android.os.Process.myPid())

        val conn = BuiltinShellConnection(phone.hub) { engine.need() }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { conn.bind() }
        val bound = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
        assertSame(shell, bound)

        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val dm = context.getSystemService(DisplayManager::class.java)
        try {
            val displayId = bound.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay -> $displayId", displayId > 0)
            assertNotNull("display $displayId not visible", dm.getDisplay(displayId))
            assertTrue("injectTap", bound.injectTap(displayId, 100, 100))

            // バインド解除: サービスが終了したのと同じように、画面が削除されます。アシスタントプロセスとそのバインダーはまだ存在します
            main.runOnMainSync { conn.unbind() }
            assertNull(conn.shell.value)
            assertTrue("display not released on unbind", poll(5_000) { dm.getDisplay(displayId) == null })
            assertTrue("helper died on unbind", shell.asBinder().pingBinder())
            assertSame(shell, phone.hub.shell.value)

            // の再バインド: 同じアシスタントを直接取得し、引き続き使用します
            main.runOnMainSync { conn.bind() }
            val again = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
            assertSame(shell, again)
            val second = again.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay after rebind -> $second", second > 0)
            assertTrue(again.injectTap(second, 50, 50))
        } finally {
            main.runOnMainSync { conn.unbind() }
            reader.close()
        }
    }
}
