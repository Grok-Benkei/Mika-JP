package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * プロセスの「タスク終了後に残された Web ページ」: タスクが終了すると ([WebStack.close])、モデルが web_keep_open でマークしたページをタスクのタグ プールから取り出します。
 * は、ハングアップしているバックグラウンド Web ページ表示とともにここに配信されます。ページはタスク中もディスプレイ上にハングしており、他のトリックを行わなくても、タスク中は通常どおり表示され、実行されます。
 * 全体で最大1ページ（[KeptPageSlot]）で、[KEEP_OPEN_HOURS][com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS] 時間後に自動で閉じる;
 * メモリ逼迫でシステムから解放を求められた場合、レンダリングプロセスがクラッシュするかシステムに回収された場合、ユーザーが閉じた場合、別のページを残した場合にも閉じる。閉じる際はタブを破棄し、表示を解放し、Cookie を書き出す、
 * そして [onClosed] へ渡す（由来した会話のその実行に理由を記録する）。
 * 残されたページはどのタスクのタブプールにも入らない: 後続のタスクからは列挙も終了もできない; 各タスク共通のタブ総数には数える（[TabBudget] 参照）が、空きを作るために閉じられることはない。
 * プロセスは、タスク サービスのフォアグラウンド通知によってサポートされています (TaskService を参照)。 WebView を移動させるものはすべてメインスレッド内にあります。 [close] はどのネジでも調整できます。
 */
class PageKeeper(
    private val context: Context,
    private val budget: TabBudget,
    clock: () -> Long = System::currentTimeMillis,
) : TabBudget.Member {
    /** は、ページのラベルとそれに付属する表示を保持します。 */
    class Held(val tab: WebTab, val host: WebHost)

    private val main = Handler(Looper.getMainLooper())

    /** ページを閉じた後 (メインスレッドの外で調整される場合もあります): ソースダイアログラウンドの理由を書き留めます。アプリで接続。 */
    @Volatile var onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> }

    private val slot: KeptPageSlot<Held> = KeptPageSlot(clock, release = ::release, onClosed = { p, r ->
        Log.i(TAG, "kept page ${p.id} closed: ${r.wire}")
        main.removeCallbacks(expiry)
        onClosed(p, r)
    })

    /** 現在ページは終了しています。 nullではありません。 */
    val page: StateFlow<KeptPage?> get() = slot.page

    private val expiry: Runnable = object : Runnable {
        override fun run() {
            if (slot.expireIfDue()) return
            // このタイマーは、システムがディープ スリープ状態になると停止します。壁時計を時々確認し、しばらくして目覚めたときに電源を切ります。
            slot.remainingMs()?.let { main.postDelayed(this, it.coerceIn(1_000L, CHECK_MS)) }
        }
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) close(KeptPageClose.Memory)
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** が残したページで何が起こるか: クラッシュするだけです。ポップアップ ウィンドウ、ダウンロード、またはジャンプ アプリを受け入れません (それを監視しているタスクはありません)。 */
    private inner class Listener(val id: Long) : WebTab.Listener {
        override fun onCrashed(tab: WebTab) {
            // は WebView 自体のコールバックの外で閉じます
            main.post { slot.closeIf(id, if (tab.reclaimed) KeptPageClose.Memory else KeptPageClose.Crashed) }
        }
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /**
     * は [tab] (タスクのタグ プールから取り出された) とその表示 [host] を受け入れ、バックグラウンドに残して実行を続けます。この時点で残っている他のページが最初に閉じられます。
     * ラベルが閉じているか、折りたたまれているか、または閉じたと表示されているときに閉じることができない場合は、null が返され、呼び出し元が自分でラベルを破棄します。メインスレッド。
     */
    fun keep(tab: WebTab, host: WebHost, origin: KeptOrigin): KeptPage? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PageKeeper.keep must run on the main thread" }
        if (tab.destroyed || tab.crashed || host.closed) return null
        val url = tab.view.url
        val site = url?.let(WebUrlPolicy::host) ?: url.orEmpty()
        // はディスプレイにハングバックします (タスクの終了時にハングアップしていたものではない可能性があります)。ページはまだ表示されていると認識しています
        host.attach(tab)
        runCatching { WebTab.cookieManager().flush() }
        val p = slot.keep(Held(tab, host), origin, site, url, tab.view.title)
        tab.listener = Listener(p.id)
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, (p.closesAt - p.keptAt).coerceIn(1_000L, CHECK_MS))
        Log.i(TAG, "kept page ${p.id} on $site from conversation ${p.conversationId}")
        return p
    }

    /** 残りのページを閉じます (ユーザーが閉じるをクリックした、通知のボタンがクリックされた、メモリが不足しているなど)。残りのページがない場合は false を返します。どのスレッドでも。 */
    fun close(reason: KeptPageClose): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (slot.page.value == null) return false
            main.post { slot.close(reason) }
            return true
        }
        return slot.close(reason)
    }

    /** が自動的にシャットダウンするまでの残り時間 (ミリ秒)。残っていない場合は null になります。 */
    fun remainingMs(): Long? = slot.remainingMs()

    /** によって残されたタグ (テストと実機の受け入れのために、ページがまだ実行されているかどうかを確認してください)。そうでない場合は null です。 */
    fun keptTab(): WebTab? = slot.handle()?.tab

    /** は [runId] を実行します。そのラウンドで記録される残りのページは次のとおりです: (サイト、シャットダウンの理由)、[KeptPageSlot.recordFor] を参照してください。 */
    fun recordFor(runId: String?): Pair<String, String?>? = slot.recordFor(runId)

    /** によって残されたページのリアルタイム ピクチャは、[FRAME_MS] ごとに 1 フレームを取得します (タスク中のピクチャ イン ピクチャと同じソース、つまりそこに表示される最新のフレーム)。画像がない場合はnullを送信します。 */
    fun frames(): Flow<Bitmap?> = flow {
        while (true) {
            emit(slot.handle()?.host?.takeIf { !it.closed }?.latestFrame())
            delay(FRAME_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ---------- グローバル上限値 ----------

    override fun tabCount(): Int = if (slot.page.value != null) 1 else 0

    /** 残りのページは、スペースを確保するために閉じられません。ユーザーは開いたままにしておきたいと考えています。 */
    override fun oldestSpare(except: String?): Pair<String, Long>? = null

    override fun evictForBudget(tabId: String) = Unit

    private fun release(h: Held) {
        val run = Runnable {
            runCatching { WebTab.cookieManager().flush() }
            runCatching { h.tab.destroy() }
            runCatching { h.host.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private companion object {
        const val TAG = "MikaPageKeeper"
        /** 壁時計はどのくらいの頻度でチェックする必要がありますか? */
        const val CHECK_MS = 60_000L
        const val FRAME_MS = 500L
    }
}
