package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.shell.HelperHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「コンピューターで開始」スクリプト: コンピューターは、ワイヤレス デバッグと同じアシスタントで開始される USB デバッグを通じて `adb shell sh <外部ファイルディレクトリ>/usb-start.sh` を実行します。
 * もハンドオーバーに同じ [HelperHub.offer] を使用します (シェル ID、パスワード、署名もチェックします)。
 * アシスタントが使用できない場合は、ワンタイム パスワードを使用してスクリプトを作成します。アシスタントが接続されており、使用可能なパスワードがディスクに残っていない場合は、パスワードなしの命令に変更します。
 * 外部ファイル ディレクトリは、アプリ自体が許可なく書き込むことができ、シェルもそれを読み取ることができます。他のアプリでは読み取れません。
 */
class UsbStartScript(context: Context, private val hub: HelperHub) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** 電話機上のスクリプトの場所。外部ストレージが一時的に利用できない場合は null。 */
    private fun file(): File? = app.getExternalFilesDir(null)?.let { File(it, HelperHandoff.USB_SCRIPT) }

    /** コンピューター上で実行されるコマンド (ブート ページに表示およびコピーされるものです)。 */
    fun command(): String =
        HelperHandoff.usbCommand(file()?.absolutePath ?: "/storage/emulated/0/Android/data/${app.packageName}/files/${HelperHandoff.USB_SCRIPT}")

    /** アプリ起動時間調整：アシスタントの有無、パスワードが無効化（ペアリング解除）されているかどうかに応じてスクリプトを書き換えます。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { HelperHandoff.usbScriptWrites(hub.shell, hub.nonceResets).collect { write(connected = it) } }
    }

    /** 誰かがアシスタントを使いたいと思っていますが、それを持っていません。ブート ページを開くと、「Write it again」が呼び出されます (パスワードがまだ新しい場合は、そのままにしておきます。[HelperHub.usbNonce] を参照)。 */
    fun refresh() {
        scope.launch { write(connected = hub.shell.value != null) }
    }

    @Synchronized
    private fun write(connected: Boolean) {
        val f = file() ?: return
        val text = runCatching {
            HelperHandoff.usbFile(
                connected, app.applicationInfo.sourceDir, app.packageName, Process.myUid(), { ownCertPin(app) }, hub::usbNonce, f.absolutePath,
            )
        }.getOrElse {
            Log.w(TAG, "cannot build ${f.name}", it)
            return
        }
        // 最初に一時ファイルを書き込んでから、名前を変更します。コンピュータは、読み取り中にその半分を読み取りません。
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "cannot write ${f.name}", it) }
    }

    private companion object {
        const val TAG = "MikaUsbStart"
    }
}

/** ミカ署名証明書のフィンガープリント: アシスタントは、アプリ自体のインストール情報から取得された、各ハンドオーバー前にミカをチェックするために使用します (両方の起動方法で共有)。 */
internal fun ownCertPin(context: Context): String {
    val signers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
    return HelperHandoff.certPin(signers) ?: error("cannot read own signing certificate")
}
