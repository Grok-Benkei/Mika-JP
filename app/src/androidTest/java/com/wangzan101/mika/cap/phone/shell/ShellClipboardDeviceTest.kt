package com.wangzan101.mika.cap.phone.shell

import android.os.Looper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 実際のシェル uid で実稼働コードを呼び出します。実行中のヘルパーがまだ古いバージョンであるかどうかには依存しません。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ShellClipboardDeviceTest {
    @Test
    fun shellCanRoundTripChineseClipboardAndRestoreOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val classpath = listOf(instrumentation.targetContext, instrumentation.context)
            .joinToString(":") { it.applicationInfo.sourceDir }
        // executeShellCommand はシェルを通過しません。環境変数は env および VAR=... を介して渡され、リダイレクトは書き込めません
        val output = sh("env CLASSPATH=$classpath app_process / ${ShellClipboardProbe::class.java.name}")
        assertTrue(output, output.lineSequence().any { it == ShellClipboardProbe.PASSED })
    }
}

/** app_process は、実行中のアシスタントがまだ古いバージョンであるかどうかに関係なく、テスト対象の APK から製品コードを読み込みます。 */
object ShellClipboardProbe {
    const val PASSED = "shell-clipboard-passed"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(Process.myUid() == ShellContext.SHELL_UID)
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val clipboard = ShellClipboard(ShellContext.create())
            val originalText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            val shell = ShellService(ShellContext.SHELL_UID)
            try {
                val text = "Mika 中文输入回归"
                val result = shell.setClipText(text)
                check(result == ShellService.CLIP_SET) { "setClipText returned $result" }
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text)
            } finally {
                // は、ユーザーのクリップボードの内容を出力せずに、再利用された製品コードを保存/復元します。
                shell.restoreClip()
            }
            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == originalText)
            println(PASSED)
            System.out.flush()
            // は System.exit を離れません: クリップボード サービスによって残されたスレッドによりスタックし、テストは待機し続けます。
            Runtime.getRuntime().halt(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }
    }
}
