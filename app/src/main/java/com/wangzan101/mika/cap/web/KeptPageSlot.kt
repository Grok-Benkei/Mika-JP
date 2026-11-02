package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS
import com.wangzan101.mika.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * タスク終了後もバックグラウンドで動かし続けるページの外部向けの姿: どの会話のどの実行に由来するか（[conversationId]、[runId]）、その実行の元の発言 [goal]、
 * そのページのサイト、アドレス、タイトル、残した時刻と自動で閉じる時刻（実時刻、ミリ秒）。
 */
data class KeptPage(
    val id: Long,
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val host: String,
    val url: String?,
    val title: String?,
    val keptAt: Long,
    val closesAt: Long,
)

/** ページを残したタスクが誰か: 会話、実行記録の番号、元の発言。 */
data class KeptOrigin(val conversationId: String, val runId: String?, val goal: String)

/**
 * ページを残す純粋ロジック（WebView には触れない）: 全体で最大1ページとし、新しく残す場合は先に古いものを閉じる（[KeptPageClose.Replaced]）; [closesAt][KeptPage.closesAt] に達したら [expireIfDue] で閉じる;
 * 閉じるときに手に持っているもの（[release]）を置き、[onClosed]に報告します（ソースダイアログラウンドに書き込みます）。 [H] は、ページ (ラベルとその表示) を実際にサポートするものです。
 * 最近閉じたページとその理由を覚えておいてください。タスク ラウンドがまだダイアログ ([recordFor]) に書き込まれていなくても、それを補うことができます。時刻は発信者によって与えられ、テストでは偽の時計が使用されます。スレッドセーフ。
 */
class KeptPageSlot<H>(
    private val clock: () -> Long,
    private val release: (H) -> Unit,
    private val onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> },
    private val ttlMs: Long = KEEP_OPEN_HOURS * HOUR_MS,
) {
    private val _page = MutableStateFlow<KeptPage?>(null)
    /** 現在ページは終了しています。 nullではありません。 */
    val page: StateFlow<KeptPage?> = _page.asStateFlow()

    private var held: H? = null
    private var counter = 0L
    private var lastClosed: Pair<KeptPage, KeptPageClose>? = null

    /** は、[handle] が保持するページを離れます。ページがすでに終了している場合は、最初にページを閉じてください。新しい左のページに戻ります。 */
    fun keep(handle: H, origin: KeptOrigin, host: String, url: String?, title: String?): KeptPage {
        close(KeptPageClose.Replaced)
        val now = clock()
        val p = synchronized(this) {
            held = handle
            KeptPage(++counter, origin.conversationId, origin.runId, origin.goal, host, url, title, now, now + ttlMs).also { _page.value = it }
        }
        return p
    }

    /** は残りのページを閉じます。残りのページがない場合は false を返します。 */
    fun close(reason: KeptPageClose): Boolean {
        val (p, h) = synchronized(this) {
            val p = _page.value ?: return false
            val h = held
            held = null
            _page.value = null
            lastClosed = p to reason
            p to h
        }
        h?.let { runCatching { release(it) } }
        onClosed(p, reason)
        return true
    }

    /** は、[id] ページがまだ存在する場合にのみページを閉じます (クラッシュとリサイクルのコールバックは遅れて到着します。後で残った間違ったページを閉じないでください)。 */
    fun closeIf(id: Long, reason: KeptPageClose): Boolean = if (_page.value?.id == id) close(reason) else false

    /** 時間が過ぎるとオフになります ([KeptPageClose.Expired])。オフの場合は true を返します。 */
    fun expireIfDue(): Boolean {
        val p = _page.value ?: return false
        return if (clock() >= p.closesAt) closeIf(p.id, KeptPageClose.Expired) else false
    }

    /** が自動的にシャットダウンするまでの残り時間 (ミリ秒、0 以上)。残っていない場合は null になります。 */
    fun remainingMs(): Long? = _page.value?.let { (it.closesAt - clock()).coerceAtLeast(0) }

    @Synchronized
    fun handle(): H? = held

    /**
     * が [runId] を実行する場合、そのラウンドで記録される残りのページは次のとおりです: (サイト、シャットダウンの理由)。まだ保持されている場合、理由は null です。このラウンドに残りのページがない場合、理由は null です。
     * ダイアログはタスクの最後のラウンドでのみ書き込まれました。この期間中、ページは閉じられた (折りたたまれたり、新しいページによって押し出された) 可能性がありますが、まだここで見つけることができます。 Web ポートは、
     */
    @Synchronized
    fun recordFor(runId: String?): Pair<String, String?>? {
        if (runId == null) return null
        _page.value?.takeIf { it.runId == runId }?.let { return it.host to null }
        return lastClosed?.takeIf { it.first.runId == runId }?.let { (p, r) -> p.host to r.wire }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
