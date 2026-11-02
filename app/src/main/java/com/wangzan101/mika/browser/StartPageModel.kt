package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「最近開いた」行: 検索は「検索:<単語>」(字幕は「Bing」)、Web ページはタイトル (読み取れない場合は Web サイト名を使用) と Web サイト名です。
 * [host] は Web ページ (アイコンの描画に使用) のホスト名で、検索は null です。
 */
data class RecentItem(val url: String, val title: String, val subtitle: String, val host: String?, val search: Boolean, val openedAtMs: Long)

/** を入力すると、[search] の関連付けが行われます。この文が Bing 検索を使用して入力された場合は、そうでない場合は、[url] を開きます。 */
data class Suggestion(val title: String, val subtitle: String, val url: String, val search: Boolean, val host: String?)

/** スタート ページの配置: 最近開いたもの、入力の関連付け、相対時間。これらはすべて純粋な関数であり、名前はホスト名に従って呼び出し元によって付けられます ([SiteLooks.face] を参照)。 */
object StartPageModel {
    val SEARCH_ENGINE: String get() = tr(R.string.browser_engine)
    val RECENT_SOURCE: String get() = tr(R.string.browser_recent_source)
    /** Lenovo には、最近オープンしたアイテムがせいぜい数件あります。 */
    const val MAX_RECENT_SUGGESTIONS = 5

    /** 最近連続して開いたもの: 同じ URL (次の # を除く) の最新のもののみが残り、最新のものが最初になります。 */
    fun recentRows(pages: List<RecentPage>, nameOf: (String) -> String): List<RecentItem> {
        val seen = HashSet<String>()
        return pages.sortedByDescending { it.openedAtMs }.filter { seen.add(it.url.trim().substringBefore('#')) }.map { p ->
            val q = AddressInput.searchQuery(p.url)
            if (q != null) {
                RecentItem(p.url, tr(R.string.browser_search_colon, q), SEARCH_ENGINE, null, true, p.openedAtMs)
            } else {
                val host = WebUrlPolicy.host(p.url)
                val site = host?.let(nameOf) ?: p.url
                RecentItem(p.url, p.title.ifBlank { site }, site, host, false, p.openedAtMs)
            }
        }
    }

    /**
     * 入力時の関連付け: 最初の項目は文そのものです。URL のように開き、Bing を使用して残りを検索します。 2 番目の項目は、最近開いたタイトルまたはこの文を含む URL です (大文字と小文字は関係ありません)。
     * 空の入力には関連付けがありません。
     */
    fun suggestions(input: String, recent: List<RecentItem>): List<Suggestion> {
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val target = AddressInput.resolve(q) ?: return emptyList()
        val first = if (AddressInput.searchQuery(target) != null) Suggestion(tr(R.string.browser_search_q, q), SEARCH_ENGINE, target, true, null)
        else Suggestion(q, tr(R.string.browser_open_url), target, false, WebUrlPolicy.host(target))
        val hits = recent.filter { it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .filter { it.url != target }
            .take(MAX_RECENT_SUGGESTIONS)
            .map { Suggestion(it.title, RECENT_SOURCE, it.url, false, it.host) }
        return listOf(first) + hits
    }

    /** 相対時間：「今」「10分前」「今日11時20分」「昨日」「10月21日」、今年ではありません。 */
    fun relativeTime(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = nowMs - atMs
        if (diff in 0 until 60_000) return tr(R.string.browser_just_now)
        if (diff in 60_000 until 3_600_000) return trPlural(R.plurals.browser_minutes_ago, (diff / 60_000).toInt())
        val then = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> tr(R.string.browser_today_at, "%02d:%02d".format(then.hour, then.minute))
            days == 1L -> tr(R.string.drawer_yesterday)
            then.year == now.year -> tr(R.string.date_month_day, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
            else -> tr(R.string.date_year_month_day, then.year, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
        }
    }
}
