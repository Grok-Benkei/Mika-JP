package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.shell.HelperLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/**
 * 内蔵アシスタントの接続。アシスタント プロセスは常駐しており、バインドを解除しても終了しません。バインドを解除するときに、この変更を元に戻します ([IMikaShell.releaseAll])。
 * 効果はサービス終了と同じです。綴じ直すときは、手持ちのバインダーを直接使用してください。助手がいない場合は、番犬に迎えに来てもらいます。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellConnection(hub: HelperHub, need: () -> Unit) : ShellBinding {
    // シングルスレッド: 失効と次のリリースは順番に実行されます。取り消しは同期バインダー呼び出しであり、メインスレッドは解放されません
    private val link = HelperLink(
        hub.shell,
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        release = { it.releaseAll() },
        need = need,
    )

    override val shell: StateFlow<IMikaShell?> get() = link.shell
    override val dropped: StateFlow<Boolean> get() = link.dropped
    override fun bind() = link.bind()
    override fun unbind() = link.unbind()
    override fun reconnect() = link.reconnect()
}
