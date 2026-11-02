package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.wangzan101.mika.core.uistate.DwellInterrupted
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PageLimitReached
import com.wangzan101.mika.core.uistate.PlacePace

/**
 * タスク内に留まるには [PagePacing] を押します: ページのアドレス ([seen]) を見ると、新しいページが認識され、下にスクロールすると、ページ自体がコンテンツのバッチ ([loadedMore]) を取得しました。
 * も 1 ページとしてカウントされます。ページ変更アクションの前に、[before] はこのページが停止していない時間を補い、そのページを [onWait] に渡すために停止した時間を補います (この呼び出しの待機時間として記録されます)。
 * [pace] この Web サイトの閲覧速度に関するサイト情報をホスト名ごとに提供します。
 * [idle] が渡され、かつアクションが画面座標で行われない（ポートへ委ねた）場合、滞在の中でその段取りに従ってページを軽く数回スクロールし（[WebPort.idleScroll]）、スクロールした回数を [onIdleScroll] へ渡す。
 * 同じ Web サイトへこのタスクで来たページが上限に達したら、[before] はもう待たず [PageLimitReached] を投げる: 呼び出し側はそのアクションを行わない;
 * 上限が管轄するのは閲覧だけ: 実行中の対外アクション（送信、提出。[outwardStart] から [outwardEnd] の間）は止めない、
 * このタスクがその Web サイトで対外アクションを成し遂げた後は上限を設けない。
 * ユーザーが滞在中に中断 ([waitOrSteer])、[before] はそれ以上待機せず、[DwellInterrupted] をスローします。呼び出し側もこのアクションを実行しません。
 * このページで一時停止が不十分な部分は一時停止とみなされず、次回ページを変更する前に埋められます。
 */
internal class PageDwell(
    private val pacing: PagePacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** は停止後に呼び出されます (パラメータは停止したミリ秒数です)。 */
    var onWait: (Long) -> Unit = {}

    /** は、滞在中にページをゆっくりスクロールした後に呼び出されます (パラメータはスクロール数です)。 */
    var onIdleScroll: (Int) -> Unit = {}

    /** は、滞在中にページを少しスクロールするとき、およびアクションを実行する前に呼び出されます (アクションの効果としてスクロールをカウントしないように、アクションの前に外観を確認してください)。 */
    var afterIdle: suspend () -> Unit = {}

    private var lastUrl: String? = null

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** は、現時点でのページのアドレス (画面を読み取って Web ページを開いた結果) を確認しました。これは、前回とは異なり (次の # はカウントしません)、新しいページです。 */
    @Synchronized
    fun seen(url: String?) {
        val u = url?.substringBefore('#')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (u == lastUrl) return
        lastUrl = u
        pacing.noteLoad(u, clock(), paceOf(u))
    }

    /** のアドレスは変更されておらず、ページ自体はコンテンツのバッチを取得しています (さらに読み込むには下にスクロールします)。 */
    @Synchronized
    fun loadedMore() {
        lastUrl?.let { pacing.noteLoad(it, clock(), paceOf(it)) }
    }

    /** ユーザーは次のように回答しました: 各 Web サイトのページ数は再計算されます。 */
    fun userAnswered() = pacing.userAnswered()

    /** 外部アクションが実行されている Web サイト (登録可能なドメイン名)。そうでない場合は null。 */
    @Volatile private var outwardNow: String? = null

    /** は、[site] (Web サイトのホスト名) に対して外部アクションを実行します。今回は、ページ制限による制限はありません。 */
    fun outwardStart(site: String) { outwardNow = siteKey(site) }

    /** 外部アクションが完了したとき。 [done] (完了) になると、この Web サイトにはページ制限がなくなります。 */
    fun outwardEnd(site: String, done: Boolean) {
        outwardNow = null
        if (done) pacing.outwardDone(site)
    }

    /**
     * ページを変更する前に: このページだけでは不十分な場合は埋められます。 [target] は開く URL (Web ページを開くとき) ですが、現時点ではこのページの Web サイトをクリックしないでください。一時停止されていた時間を返します。
     * [port] を与えると（画面座標に従った動作は行われません）、滞在中に軽くページを数回スクロールできます。 [gate] の場合は、まずページ数の上限を見てから [PageLimitReached] を投げます。
     */
    suspend fun before(target: String? = null, port: WebPort? = null, gate: Boolean = true): Long {
        val site = target ?: synchronized(this) { lastUrl } ?: return 0
        val p = paceOf(site)
        if (gate && siteKey(site) != outwardNow && pacing.capReached(site, p)) throw PageLimitReached(hostOf(site), pacing.pagesOn(site), pacing.capFor(p))
        val wait = pacing.waitBefore(site, clock())
        if (wait <= 0) return 0
        if (port != null && idle != null) idleWait(port, idle, wait) else pause(wait)
        pacing.served(site)
        onWait(wait)
        return wait
    }

    /** は [ms] ミリ秒間停止します。ユーザーが中断して [DwellInterrupted] をスローすると、停止が停止します。 */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /**
     * は [wait] ミリ秒間一時停止し、その間、[plan] の配置に従って数回軽く回転します。現時点でスクロールするのが不便であるとポートが判断した場合 (ポップアップ ボックス、入力ボックスがフォーカスを保持しているなど)、スクロールは停止しますが、それでも十分に停止します。
     * ユーザーが一時停止を中断したときも、スクロール時間は依然として報告され、モデルの手の画面には古いものとしてマークが付けられたままでした。
     */
    private suspend fun idleWait(port: WebPort, plan: IdleScroll, wait: Long) {
        val start = clock()
        var done = 0
        suspend fun scrolled() { if (done > 0) { onIdleScroll(done); afterIdle() } }
        try {
            for (step in plan.plan(wait)) {
                val left = step.atMs - (clock() - start)
                if (left > 0) pause(left)
                val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                if (!ok) break
                done++
            }
            val rest = wait - (clock() - start)
            if (rest > 0) pause(rest)
        } catch (e: DwellInterrupted) {
            scrolled()
            throw e
        }
        scrolled()
    }

    private fun paceOf(site: String): PlacePace {
        val host = hostOf(site)
        return synchronized(paces) { paces.getOrPut(host) { pace(host) } }
    }

    private fun siteKey(site: String): String { val h = hostOf(site); return UrlHost.registrable(h) ?: h }

    private fun hostOf(site: String): String = UrlHost.of(if ("://" in site) site else "https://$site") ?: site

    /** 下にスクロールする前に、ページが受け取った応答を書き留めてください。スクロール後に [afterScroll] を使用すると、新しいデータのバッチがある場合、さらに多くのデータがロードされます。 */
    suspend fun scrollMark(port: WebPort): Set<String>? = if (!paced) null else try {
        port.captures(null, null, false).mapTo(HashSet()) { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun afterScroll(port: WebPort, mark: Set<String>?) {
        mark ?: return
        val fresh = try { port.captures(null, null, false) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        if (fresh.any { it.id !in mark && batch(it) }) loadedMore()
    }

    /** リスト データのバッチのような応答: 正常に受信された、JSON またはテキスト。小さすぎない (統計やハートビートなどの小さな応答はカウントされません)。 */
    private fun batch(c: CapturedResponse): Boolean {
        if (c.state != CapturedResponse.State.DONE || c.status !in 200..299) return false
        val mime = c.mime?.lowercase().orEmpty()
        if ("json" !in mime && !mime.startsWith("text/")) return false
        return (c.size ?: 0) >= MORE_MIN_BYTES
    }

    private companion object {
        /** 「コンテンツの別のバッチが来る」としてカウントされる応答は、少なくともこれくらいの大きさです。 */
        const val MORE_MIN_BYTES = 2_048L
    }
}
