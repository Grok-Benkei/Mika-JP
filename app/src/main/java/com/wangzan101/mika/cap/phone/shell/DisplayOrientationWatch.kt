package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * は、仮想画面がある場合にタスクの方向とウィンドウ サイズの変更のみを監視します。アプリケーションのコンテンツは読み取られず、タスクのフォーカスも変更されません。
 * をタスク監視に登録できない場合、[POLL_MS] ごとに 1 回スキャンに戻り、[active] (およびミカの画面) が true の場合にのみスキャンします。
 * 画面が表示された後、[close]で停止します。 [active] が false になると、ポーリングも自動的に停止します。
 */
@SuppressLint("PrivateApi")
internal class DisplayOrientationWatch(
    private val taskManager: Any,
    private val active: () -> Boolean,
    private val sync: () -> Unit,
) {
    private val thread = HandlerThread("mika-display-orientation").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private val scan = CoalescedScan(
        schedule = { handler.postDelayed(it, 50) },
        cancel = { handler.removeCallbacks(it) },
        scan = { runCatching(sync).onFailure { Log.w("MikaShell", "display orientation scan failed", it) } },
    )
    private val signal = object : Binder() {
        init { attachInterface(null, "android.app.ITaskStackListener") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                scan.request()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
    private val listener = runCatching {
        Class.forName("android.app.ITaskStackListener\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, signal)
    }.getOrNull()
    private val listening = listener != null && runCatching {
        taskManager.javaClass.methods.first { it.name == "registerTaskStackListener" }.invoke(taskManager, listener)
    }.isSuccess
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !active()) return
            scan.request()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init { if (listening) scan.request() else handler.post(poll) }

    fun close() {
        closed = true
        scan.close()
        if (listening) runCatching {
            taskManager.javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager, listener)
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
