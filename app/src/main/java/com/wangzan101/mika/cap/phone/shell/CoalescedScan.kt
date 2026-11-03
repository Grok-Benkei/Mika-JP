package com.wangzan101.mika.cap.phone.shell

/** イベントのバッチは 1 回のスキャンに対してのみスケジュールされます。後続のイベントによってスケジュールされたスキャンが延期されることはなく、連続するイベントは時間内に処理できます。 */
internal class CoalescedScan(
    private val schedule: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val scan: () -> Unit,
) {
    private val lock = Any()
    private var queued = false
    private var closed = false
    private val work = Runnable {
        synchronized(lock) {
            if (closed) return@Runnable
            queued = false
        }
        scan()
    }

    fun request() = synchronized(lock) {
        if (!closed && !queued) {
            queued = true
            schedule(work)
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        cancel(work)
    }
}
