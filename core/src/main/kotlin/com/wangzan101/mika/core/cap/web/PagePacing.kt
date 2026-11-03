package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.net.UrlHost
import kotlin.random.Random
import com.wangzan101.mika.core.uistate.PlacePace

/**
 * Web ページでページからページへ読むリズム (時計やページに触れずに、純粋なロジック): ユーザーは、ページを開くとき、次のページに移動して次の項目をクリックする前に、常にページをしばらく見続ける必要があります。
 * コンテンツの新しいページが表示されるたびに ([noteLoad]: 新しいアドレスが開かれるか、下にスクロールするとページ自体がコンテンツのバッチを取り込みます)、この Web サイトで最近閲覧したページの数に基づいて、このページに滞在する時間を決定します。
 * 次のページ変更アクション (同じサイト URL を開く、クリック、戻る、スクロールダウン、ボタンを押す) を行う前に、[waitBefore] を尋ねます: このページが十分に停止していない場合は、残りの時間を補ってください。
 *
 * - Web サイトの最初のページが停止しません (最初にこの Web サイトにアクセスしたときの最初のアクションは通常どおりです)。
 * - 2 ページ目以降、滞在期間は [Limits.firstDwellMs] でランダムに選択されます。最近[Limits.windowMs]に表示されるものが多いほど、上限と下限が1つずつ増えます([Limits.perPageMs])。
 *   は [Limits.maxDwellMs] を超えません。
 * - ページが滞在する瞬間から、モデルが自分で考えてページを読むのに費やした時間を含め、足りない部分だけを補います。
 * - Web サイトは登録可能なドメイン名によって識別されます (www.example.com と m.example.com は同じです)。別の Web サイトにアクセスすると、最初のページから始まります。
 * - サイト ナレッジによると、この Web サイトが閲覧速度を制限すると ([PlacePace.CAREFUL])、この Web サイトのページは [careful] (より長く) の範囲内に留まります。
 * - タスク内の同じ Web サイトからのページ数には上限があります ([Limits.pageCap])。制限に達すると、[capReached] が true になり、呼び出し元はページを変更しなくなります。
 *   ユーザーが答えた（[userAnswered]）後は数え直す。上限が管轄するのは閲覧だけ: このタスクがその Web サイトで対外アクション（[outwardDone]、送信や提出の類）を行った後は、
 *   その Web サイトについてはこのタスクの残りの時間は上限を設けない（まとめて連絡・投函する処理が Web サイト自身の上限まで行えるように）、滞在は従来どおり。
 *
 * [Off] は決して待たず、ページ数の上限も設けない（テストや、リズムを必要としない呼び出し側が使う）。
 */
class PagePacing(private val random: Random?, val limits: Limits = Limits(), val careful: Limits = Limits.CAREFUL) {
    /** ミリ秒単位の継続時間。 */
    data class Limits(
        /** 1つの Web サイトの2ページ目の滞在範囲。 */
        val firstDwellMs: LongRange = 4_000L..7_000L,
        /** 以降の追加ページごとに、上限と下限 (下限、上限) を追加します。 */
        val perPageMs: Pair<Long, Long> = 600L to 1_000L,
        /** 滞在上限：ページ数があっても超えません。 */
        val maxDwellMs: Long = 12_000L,
        /** は、「最近閲覧されたページ数」の時間枠を計算します。 */
        val windowMs: Long = 10 * 60_000L,
        /** 1 つのタスク (ユーザー応答間) 内の同じ Web サイトからの最大ページ数。 */
        val pageCap: Int = 20,
    ) {
        companion object {
            /** 閲覧速度を制限する Web サイト: 各ページの一時停止時間が長くなり、ページ制限が低くなります。 */
            val CAREFUL = Limits(firstDwellMs = 10_000L..16_000L, perPageMs = 600L to 1_000L, maxDwellMs = 30_000L, pageCap = 12)
        }
    }

    private class Load(val site: String, val atMs: Long, var dwellMs: Long, val limits: Limits)

    private val loads = ArrayDeque<Load>()

    /** (ユーザーが最後に回答してから) このタスクの各 Web サイトからのページ数。タイムウィンドウではクリアされません。 */
    private val pages = HashMap<String, Int>()

    /** このタスクで外部アクションを実行した Web サイト: ページ制限は考慮されなくなりました。ユーザーの反応も不明瞭だ。 */
    private val outward = HashSet<String>()

    /** リズムがある（[Off] ではない）。 */
    val paced: Boolean get() = random != null

    /**
     * Web サイトには、[site] (ホスト名または URL) にコンテンツの新しいページがあります。指定された滞在時間の間、このページに戻ります。
     * [pace] は、この Web サイトの閲覧速度に関するサイトナレッジの声明です。
     */
    @Synchronized
    fun noteLoad(site: String, nowMs: Long, pace: PlacePace = PlacePace.NORMAL): Long {
        if (random == null) return 0
        val key = key(site) ?: return 0
        prune(nowMs)
        val l = limitsFor(pace)
        val seen = loads.count { it.site == key }
        val dwell = if (seen == 0) 0 else dwellFor(seen + 1, l)
        loads.addLast(Load(key, nowMs, dwell, l))
        pages[key] = (pages[key] ?: 0) + 1
        return dwell
    }

    /** このタスクでは (最後のユーザーが回答して以来) [site] が数ページ登場しました。 */
    @Synchronized
    fun pagesOn(site: String): Int = key(site)?.let { pages[it] } ?: 0

    /** は[pace]のページ番号の上限を押します。 */
    fun capFor(pace: PlacePace): Int = limitsFor(pace).pageCap

    /** [site] アップロードされたページが上限に達しました。ページを再度変更する前に停止してください (ユーザーの同意後に読み取ります)。外部アクションを実行した Web サイト ([outwardDone]) および [Off] は常に false です。 */
    @Synchronized
    fun capReached(site: String, pace: PlacePace = PlacePace.NORMAL): Boolean =
        random != null && key(site)?.let { it in outward } != true && pagesOn(site) >= capFor(pace)

    /** このタスクは、[site] で外部アクション (送信、送信) を実行しました。残りの期間、この Web サイトにはページ制限がありません。 */
    @Synchronized
    fun outwardDone(site: String) {
        key(site)?.let(outward::add)
    }

    /** ユーザーは答えました (フォローアップの質問に答え、同意書を提出しました): 各 Web サイトのページ数が再計算されました。 */
    @Synchronized
    fun userAnswered() = pages.clear()

    /**
     * [site] でページ変更アクションを実行するまでの待機時間: 残り時間は、最新のページがこの Web サイトから取得され、十分に停止していないとき、それ以外の場合は 0 です。
     * [Off] は常に 0 です。
     */
    @Synchronized
    fun waitBefore(site: String?, nowMs: Long): Long {
        if (random == null) return 0
        val key = site?.let(::key) ?: return 0
        val last = loads.lastOrNull() ?: return 0
        if (last.site != key) return 0
        return (last.dwellMs - (nowMs - last.atMs)).coerceIn(0, last.limits.maxDwellMs)
    }

    /** は、[site] の最新ページまで十分に停止しました (呼び出し側は待機を終了しました)。このページ以降のアクションは待機しなくなりました。 */
    @Synchronized
    fun served(site: String) {
        val key = key(site) ?: return
        loads.lastOrNull()?.takeIf { it.site == key }?.dwellMs = 0
    }

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    /** ページ [n] (n ≥ 2) [l] の滞留時間を押します。 */
    private fun dwellFor(n: Int, l: Limits): Long {
        val r = random ?: return 0
        val extra = (n - 2).toLong()
        val lo = (l.firstDwellMs.first + extra * l.perPageMs.first).coerceAtMost(l.maxDwellMs)
        val hi = (l.firstDwellMs.last + extra * l.perPageMs.second).coerceAtMost(l.maxDwellMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (loads.isNotEmpty() && nowMs - loads.first().atMs > limits.windowMs) loads.removeFirst()
    }

    private fun key(site: String): String? {
        val host = UrlHost.of(if ("://" in site) site else "https://$site") ?: return null
        return UrlHost.registrable(host) ?: host
    }

    companion object {
        /** は決して待ちません。 */
        val Off = PagePacing(null)

        /** は、人々が Web ページを読むリズムに似ています。 [random] は固定シード (テスト) に変更できます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            PagePacing(random, limits, careful)
    }
}
