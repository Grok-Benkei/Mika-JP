package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ホーム画面を見つめる組み込みアシスタントの部分: エージェントのアクションの前後の短期間で、ホーム画面の上部でタスクが変更されました ([MainScreenLog])。
 * アプリが、Mika 画面上の独自のページからではなく、バックグラウンド (サービス、通知ジャンプ、システム セレクター) から別のアプリを開くと、システムはそのアプリをホーム画面に配置します。
 * ミカはこの記録を認識し、元に戻りました。
 *
 * タスクが変更されたときに一度確認します。タスク スタック リスナーをシステムに登録します (ITaskStackListener、シェルにはタスクを管理する権限があります)。
 * は「変更された」信号のみを監視し、パラメーターを解析しません (各バージョンのメソッドの順序は異なります)。受け取ったら、タスクリストをもう一度読んでください。
 * が登録に失敗した場合、スターリング期間中 [POLL_MS] ごとに 1 回返されて表示されます。 ([arrivals]) をクエリするときは、最終的な外観を見逃さないように、その場でもう一度見てください。
 * 見つめていないときはタスクリストを見ないでください。
 */
internal class MainScreenWatch(
    private val taskManager: () -> Any,
    /** 現時点で画面上のすべてのタスクは、最近の使用状況に従って配置されています。 */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("mika-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    /** いつまで見るか (uptimeMillis)。 */
    @Volatile private var until = 0L
    /** タスクスタックモニターが登録されている場合はtrue、登録されていない場合はfalse、未試行の場合はnullとなります。 */
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** 今から[windowMs]を見つめてください現時点でのメイン画面の一番上のタスクに戻ります (そうでない場合は -1)。 */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * [since] その後、メイン画面に上部のタスクが次々と表示されます。それぞれは、現在どの画面にあるか (そうでない場合は -1)、およびこのレコードがモニタリングまたはポーリングによって取得されたかどうかも示します。
     * フィールドは、[toBundle] を参照してください。
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    /** タスク スタックが変更されました。タスク スタックをマージして再度表示し、一連の変更を 1 回だけ表示します。 */
    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    /**
     * システムは ITaskStackListener インターフェイスを通じてバインダーをコールバックします。 「コールバックが来た」ことのみを認識し、パラメータを読み取りません。
     * これらのコールバックは一方向であり、応答は必要ありません。記述子はインターフェイスの名前に従って入力され、システムはそれに従ってパッケージ化します。
     */
    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        /** 期限が過ぎた後に再度確認し、その場で確認します。ミカが判断を完了するまでに時間がかかります。 */
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        /** は、並列配列に編成されたレコードのセットです (バンドルにはオブジェクト リストはありません)。 [now] は、タスクが現在どの画面にあるかを示します。 */
        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }
    }
}
