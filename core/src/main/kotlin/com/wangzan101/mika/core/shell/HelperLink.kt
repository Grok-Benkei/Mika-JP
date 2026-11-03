package com.wangzan101.mika.core.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 常駐アシスタントへの「接続」: アシスタント プロセスはバインド解除で終了せず、バインダーは [source] によって提供されます (使用できない場合は null)。
 * はサービスによってバインドされた外部接続と同じです。[shell] はバインド期間中にのみ値を持ち、[dropped] は接続されており、まだ必要ですが、現在は切断されていることを意味します。
 * バインドを解除するときは、[release] を使用して、このバインドによって残された変更 (仮想画面、ミュート、クリップボード) を削除するようにアシスタントに依頼します。その効果はサービスの終了と同等です。
 *
 * [scope] は単一スレッドで連続して実行する必要があります。バインド解除の取り消しは再バインドのリリースより前にランク付けされ、新しいタスクによって作成された画面が最後の取り消しによって誤って削除されることはありません。
 */
class HelperLink<T : Any>(
    private val source: StateFlow<T?>,
    private val scope: CoroutineScope,
    private val release: (T) -> Unit,
    private val need: () -> Unit,
) {
    private val _shell = MutableStateFlow<T?>(null)
    val shell: StateFlow<T?> = _shell.asStateFlow()

    private val _dropped = MutableStateFlow(false)
    val dropped: StateFlow<Boolean> = _dropped.asStateFlow()

    private var wanted = false
    private var job: Job? = null

    @Synchronized
    fun bind() {
        if (wanted) return
        wanted = true
        var seen = false
        job = scope.launch {
            source.collect { t ->
                synchronized(this@HelperLink) {
                    if (!wanted) return@collect
                    _shell.value = t
                    if (t != null) {
                        seen = true
                        _dropped.value = false
                    } else {
                        _dropped.value = seen
                    }
                }
                if (t == null) need()
            }
        }
    }

    @Synchronized
    fun unbind() {
        if (!wanted) return
        wanted = false
        job?.cancel()
        job = null
        val last = _shell.value
        _shell.value = null
        _dropped.value = false
        if (last != null) scope.launch { runCatching { release(last) } }
    }

    /** 「再接続」: バインドされているがアシスタントがいない場合は、すぐにウォッチドッグを再度開始してください。 */
    fun reconnect() {
        val ask = synchronized(this) { wanted && _shell.value == null }
        if (ask) need()
    }
}
