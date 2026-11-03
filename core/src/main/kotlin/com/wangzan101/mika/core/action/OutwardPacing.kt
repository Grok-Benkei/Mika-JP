package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.net.UrlHost
import kotlin.random.Random

/**
 * 外部アクション間の間隔 (純粋なロジック、時計とインターフェイスに触れない): 不可逆的な判断が認識された送信、送信 (他者への連絡、アプリケーションの送信)、削除、注文、
 * 同じ Web サイトまたはアプリを継続的に操作すると、人が 1 つずつ処理するのと同じように、2 つの時間の間に連続した秒数が存在せず、ランダムな時間が発生します。
 * 途中でページ変更があるかどうかに関係なく、ページ変更の一時停止は Web ページのリズムによって制御されます。携帯電話もWebページも判断が同じなので同じです。
 *
 * - Web サイト (アプリ) の初回起動には待ち時間がありません。
 * - その後毎回、前回との間に少なくとも [Limits.firstGapMs] のランダムなセクションが存在します。このウィンドウで操作を行うほど、上限と下限にそれぞれ 1 つのセクション ([Limits.perActionMs]) が追加されます。
 *   は [Limits.maxGapMs] を超えません。
 * - 間隔は最後に終了した時間から計算されます。ページを読んで次のステップを考える時間も含まれます。足りない部分だけを補う。
 * - Web ページは登録可能なドメイン名 (www.example.com と m.example.com は同じ) によって識別され、携帯電話はパッケージ名によって識別されます。他の Web サイトやアプリは個別にカウントされます。
 *
 * [Off] 待ち時間はありません (テストやリズムを必要としない発信者にこれを使用します)。
 */
class OutwardPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** ミリ秒単位の継続時間。 */
    data class Limits(
        /** 2 回目と 1 回目の間の間隔範囲。 */
        val firstGapMs: LongRange = 8_000L..20_000L,
        /** 再度実行するたびに、上限値と下限値（下限値、上限値）を加算します。 */
        val perActionMs: Pair<Long, Long> = 500L to 1_000L,
        /** 間隔の上限。 */
        val maxGapMs: Long = 30_000L,
        /** 「最近何回行ったか」を数える時間枠。 */
        val windowMs: Long = 30 * 60_000L,
    )

    private class Done(val site: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** リズムがある（[Off] ではない）。 */
    val paced: Boolean get() = random != null

    /** [site] で次の対外アクションを行うまでにあとどれだけ待つ必要があるか: 前回から十分な間隔が空いていなければ残り時間、そうでなければ 0。 */
    @Synchronized
    fun waitBefore(site: String, nowMs: Long): Long {
        if (random == null) return 0
        prune(nowMs)
        val last = done.lastOrNull { it.site == site } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, limits.maxGapMs)
    }

    /** は、[site] で外部アクションを実行しました。このウィンドウで実行した回数をクリックして、少なくとも次回までにどれくらいの時間がかかるかを確認します。 */
    @Synchronized
    fun noteDone(site: String, nowMs: Long) {
        if (random == null) return
        prune(nowMs)
        val count = done.count { it.site == site } + 1
        done.addLast(Done(site, nowMs, gapAfter(count)))
    }

    /** は、[n] 回実行した後の次回までの間隔です (n ≥ 1)。 */
    private fun gapAfter(n: Int): Long {
        val r = random ?: return 0
        val extra = (n - 1).toLong()
        val lo = (limits.firstGapMs.first + extra * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
        val hi = (limits.firstGapMs.last + extra * limits.perActionMs.second).coerceAtMost(limits.maxGapMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > limits.windowMs) done.removeFirst()
    }

    companion object {
        /** は決して待ちません。 */
        val Off = OutwardPacing(null)

        /** は人が 1 つずつ処理するようなものです。 [random] は固定シード (テスト) に置き換えることができます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = OutwardPacing(random, limits)

        /** はどの Web サイトまたはアプリに属しますか? Web ページ ([web] が true) をクリックしてドメイン名を登録し、携帯電話でパッケージ名を押します。 */
        fun siteOf(packageName: String?, web: Boolean): String? {
            val p = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return if (web) UrlHost.registrable(p) ?: p else p
        }
    }
}
