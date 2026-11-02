package com.wangzan101.mika.task

import android.content.Context
import android.os.PowerManager
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface WakeLockPort {
    fun acquire()
    fun release()
}

/**
 * タスクの実行中に画面が消えません。画面のウェイクロックを押したままにし、システム設定を変更せず、明るさも変更しないでください。
 * 画面がオフになると、システムは挿入されたタッチを破棄してロックするため、エージェントの動作中に画面を自動的にオフにすることはできません。
 * ただし、ユーザーが操作していないときは、画面が完全に明るい必要はありません。システムがタイムアウトすると、通常どおり暗くなります ([ScreenWakeLock] を参照)。
 *
 * は実行時のみ保持されます。引き継ぎ/応答/続行/ロック解除を待機しているときに、エージェントの画面をオンにする必要はありません。
 * を押してから [GRACE_MS] を押したままにして (ユーザーはおそらく処理しようとしているので、目の前で画面をオフにしないでください)、その後放し、タスクの実行が継続したらもう一度押し続けます。
 * 終了、失敗、キャンセル、即時リリース。ロックはプロセスに従って行われます。システムは異常終了すると、Mika を自動的に元に戻します。永続的な回復は必要ありません。
 * ユーザーは、電源ボタンを押して画面をオフにすることができます (ACQUIRE_CAUSES_WAKEUP なし)。このとき、ゲートはタスクをロック解除を待機させます。
 */
class KeepAwake(private val lock: WakeLockPort, private val scope: CoroutineScope) {
    private var held = false
    private var pendingRelease: Job? = null
    private var closed = false

    @Synchronized
    fun onState(state: TaskState) {
        if (closed) return
        when (state) {
            is TaskState.Running -> {
                pendingRelease?.cancel()
                pendingRelease = null
                if (!held) {
                    lock.acquire()
                    held = true
                }
            }
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> release()
            else -> if (held && pendingRelease == null) {
                pendingRelease = scope.launch {
                    delay(GRACE_MS)
                    synchronized(this@KeepAwake) {
                        pendingRelease = null
                        release()
                    }
                }
            }
        }
    }

    @Synchronized
    fun release() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (held) {
            lock.release()
            held = false
        }
    }

    /** タスク終了: 解放し、その後到達した状態を無視します (バイパス コルーチンでの遅い解放はロックを再取得できません)。 */
    @Synchronized
    fun close() {
        closed = true
        release()
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}

/**
 * いくつかのタスクが画面ロックを共有します。各タスクは [lease] のコピーを取得します。これは、最初のコピーが保持されるときに取得され、最後のコピーが解放されるときに解放されます。
 * 各コピーを取得したかどうかを覚えておいてください。繰り返しの取得または解放はカウントされません。
 */
class SharedWakeLock(private val lock: WakeLockPort) {
    private var count = 0

    fun lease(): WakeLockPort = object : WakeLockPort {
        private var held = false

        override fun acquire() {
            synchronized(this@SharedWakeLock) {
                if (held) return
                held = true
                if (count++ == 0) lock.acquire()
            }
        }

        override fun release() {
            synchronized(this@SharedWakeLock) {
                if (!held) return
                held = false
                if (--count == 0) lock.release()
            }
        }
    }
}

/**
 * 画面ロックの SCREEN_DIM_WAKE_LOCK: 画面はオンのままで、ユーザーが一定期間電話に触れないと、システムは通常どおり画面を暗くします。
 * はタッチするとすぐに元の明るさに戻ります。システムの明るさは変更されず、明るくなります。暗くしても、画面は開いたままでインタラクティブです。
 * によって注入されたタッチは引き続き配信されます。エージェントは別の仮想ディスプレイ画面を操作し、そこへの入力はメイン画面の明るさの影響を受けません。
 * このタイプのロックは非推奨としてマークされています (Windows では FLAG_KEEP_SCREEN_ON が推奨されます) が、バックグラウンドでタスクを実行する場合、Mika には独自のウィンドウがありません。
 * フロントエンド サービスによって保持されているこのようなロックは引き続き有効です。
 */
class ScreenWakeLock(context: Context) : WakeLockPort {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(LEVEL, "Mika:task")
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    companion object {
        /** 画面はオンのままですが、タイムアウトによってシステムが暗くなります。 */
        @Suppress("DEPRECATION")
        const val LEVEL = PowerManager.SCREEN_DIM_WAKE_LOCK

        /** には、パスがリリースを逃すことを防ぐための上限があります。通常はタスクのステータスによって解放されます。 */
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
