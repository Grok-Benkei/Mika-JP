package com.wangzan101.mika.browser

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.PageOrigin
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * ミカにブラウザ ページの 1 ページを表示するように要求する場合、どの会話が続く必要があります (純粋なロジック)。ルール:
 * 1. 同じページ (`#` を除く同じ URL) で質問され、どれだけ時間が経っても最新の会話を続けます。
 * 2. それ以外の場合は、同じ Web サイト (同じホスト名) で、[SAME_SITE_MS] で質問し、次に最新の Web サイトをクリックします (検索結果 → 製品ページ → ショッピング カート)。
 *    それでも同じです。
 * 3. 何もない場合は、新しい会話を開きます。ユーザーはいつでも「新しい会話」を選択できます。
 * 長い時間が経ってから同じ Web サイトの別のページに戻ってきた場合は、おそらく別のことについて話している可能性があるため、第 2 条には期限があります。同じページには時間制限はありません。このページに戻るには、このページを続行するだけです。
 */
object PageConversations {
    /** 同じ Web サイトでの「質問を続ける」の制限時間: 最新の変更はこの範囲内です。 */
    const val SAME_SITE_MS = 2 * 60 * 60 * 1000L

    /** [all] は会話リストです (最近変更されたものが最初に表示されます。[com.wangzan101.mika.chat.ConversationStore.list] を参照)。 [url] は現在ご覧のページです。 */
    fun pick(all: List<ConversationSummary>, url: String, now: Long): ConversationSummary? {
        val page = pageKey(url)
        val host = WebUrlPolicy.host(url)
        val fromPages = all.filter { it.page != null }.sortedByDescending { it.updatedAt }
        return fromPages.firstOrNull { pageKey(it.page!!.url) == page }
            ?: fromPages.firstOrNull { host != null && it.page!!.host == host && now - it.updatedAt in 0..SAME_SITE_MS }
    }

    /** このページには、会話のソース: URL、ホスト名、タイトルが記録されます。そうでない場合、Web アドレスは null です。 */
    fun origin(url: String, title: String): PageOrigin? {
        val host = WebUrlPolicy.host(url)?.takeIf { url.startsWith("http", ignoreCase = true) } ?: return null
        return PageOrigin(url, host, title.trim())
    }

    /** 「同じページ」を識別するために使用される URL: `#` の前後にあるスラッシュを削除します。 */
    fun pageKey(url: String): String = url.substringBefore('#').trimEnd('/')
}
