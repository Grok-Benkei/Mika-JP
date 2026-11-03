package com.wangzan101.mika.cap.phone.shell

import android.os.Handler
import android.os.Looper

/**
 * はプロセス内でシェル接続を共有し、参照カウントに基づいてバインド/バインド解除します。 1 つのコピーは [com.wangzan101.mika.cap.phone.PhoneServices] によって作成されます。
 * をアンバインドすると、シェル側の仮想画面やその他の変更が削除されるため、タスクとスタートアップ リカバリを 1 つにバインドしてから個別にアンバンドルすることはできません。 [create] 初めて使用するときに接続を確立します。
 */
class SharedShell(private val create: () -> ShellBinding) {
    private var connection: ShellBinding? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun acquire(): ShellBinding {
        val c = connection ?: create().also { connection = it }
        if (users++ == 0) onMain { c.bind() }
        return c
    }

    @Synchronized
    fun release() {
        check(users > 0) { "release without acquire" }
        if (--users == 0) {
            val c = connection
            onMain { synchronized(this) { if (users == 0) c?.unbind() } }
        }
    }

    /** 「再接続」: バインドされているがアシスタントがない場合は、すぐにウォッチドッグを再度開始してください。誰も縛られていないなら、何もしないでください。 */
    @Synchronized
    fun reconnect() {
        connection?.takeIf { users > 0 }?.reconnect()
    }

    // バインド/アンバインドは、接続コールバックとのインターリーブを避けるためにメインスレッドに統合されます。
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
