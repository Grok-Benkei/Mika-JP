package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * は単一のクラスを破棄します。これにより、シェル プロセスが終了します。クリップボードの復元、ミュートの解除、仮想画面の解除、バインダーの障害など、残せる痕跡のみを受け入れます。
 * 最後に、後続のテスト クラスが影響を受けないように、接続が新しいライブ シェル (プロセス終了後の自動回復) を取得するまで待ちます。
 */
@RunWith(AndroidJUnit4::class)
class ShellDestroyParityDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var probe: ShellProbe
    private var screen: ParityScreen? = null
    private var dead: IBinder? = null
    private var audioBefore = "default"
    private var clipBefore: String? = null

    @Before
    fun setUp() {
        probe = ShellProbe(context)
        DeviceSupport.shell().restoreClip()
        audioBefore = ParityAudio.mode(SETTINGS)
        clipBefore = ParityClip.text(context)
    }

    @After
    fun tearDown() {
        runCatching { probe.close() }
        // releaseDisplay は、シェルが終了すると例外をスローします。これは、ローカルの ImageReader をオフにするだけです。
        runCatching { screen?.close() }
        ParityAudio.set(SETTINGS, audioBefore)
        ParityClip.put(context, clipBefore)
        val old = dead ?: return
        val fresh = pollUntil(RECOVER_MS) {
            runCatching { DeviceSupport.shell() }.getOrNull()?.takeIf { it.asBinder() != old }
        }
        assertNotNull("no live shell came back after destroy", fresh)
        ParityScreen(fresh!!, 720, 1600, 320).close()
    }

    @Test
    fun destroyUndoesEverythingAndEndsTheProcess() {
        val shell = DeviceSupport.shell()
        val s = ParityScreen(shell, 720, 1600, 320).also { screen = it }
        sh("am start --display ${s.id} -f 0x18000000 -n ${ShellProbe.COMPONENT}")
        assertNotNull(probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id })
        val baseline = ParityAudio.mode(SETTINGS)
        assertEquals(true, shell.setAudioMuted(SETTINGS, true))
        assertEquals("ignore", ParityAudio.mode(SETTINGS))
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))

        val binder = shell.asBinder().also { dead = it }
        // プロセスは応答する前に終了し、呼び出し元は DeadObjectException を受け取る可能性が高くなります。通常の復帰も考慮されます
        runCatching { shell.destroy() }

        assertNotNull("binder still alive", pollUntil(5_000) { true.takeIf { !binder.isBinderAlive } })
        val dm = context.getSystemService(DisplayManager::class.java)
        assertNotNull("display ${s.id} not released", pollUntil(5_000) { true.takeIf { dm.getDisplay(s.id) == null } })
        assertEquals("audio not unmuted", baseline, ParityAudio.mode(SETTINGS))
        assertEquals("clipboard not restored", "parity-user", ParityClip.text(context))
    }

    private companion object {
        // 新しいバックエンドはウォッチドッグによってプルアップされ、その間隔は古いバックエンドの即時の再バインドよりも長くなります。
        const val RECOVER_MS = 60_000L
    }
}
