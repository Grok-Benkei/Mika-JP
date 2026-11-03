package com.wangzan101.mika.cap.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * サイトを終了します: 最善を尽くしてください。システム インターフェイスは、URL によって Cookie の名前のみを読み取ることができますが、パス、ドメイン、および HttpOnly の詳細を読み取ることはできません。
 * したがって、このホスト名およびすべてのレベル (ドメイン名が登録できるまで) の上位ドメインによって読み取られる各 Cookie に対して、「ホストにのみ属する」および「ドメイン全体に属する」に従って有効期限レコードを書き込みます。
 * 次に、これらのソースから Web ページ ストレージをクリアします。一部の Web サイトのログインがまだ残っている可能性があります。[logoutAll] を使用して完全にクリアしてください。
 * [cookies]、[storage] は mika Web ページを使用して設定されます ([WebTab.cookieManager] を参照)。メインスレッドによって呼び出されます。
 */
class WebLogin(private val cookies: CookieManager, private val storage: WebStorage, private val registry: SiteRegistry?) {

    /** は、期限切れの Cookie の数を (名前ごとに) 返します。 */
    fun logout(host: String): Int {
        val h = WebUrlPolicy.host("https://${host.trim()}") ?: return 0
        var expired = 0
        for (d in domainsFor(h)) {
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://$d/"
                val names = cookieNames(cookies.getCookie(url))
                for (name in names) expiryLines(name, d).forEach { cookies.setCookie(url, it) }
                if (scheme == "https") expired += names.size
                storage.deleteOrigin("$scheme://$d")
            }
            storage.deleteOrigin("https://www.$d")
        }
        cookies.flush()
        registry?.remove(h)
        return expired
    }

    /**
     * すべて終了して Web ページ データをクリアします: すべての Cookie (セッション Cookie を含む)、すべての Web ページ ストレージ、および [views] のキャッシュ (キャッシュは Web ページ構成全体で共有されます)。
     * (1 つのビューを与えてすべてクリア)、[database] に記憶されている HTTP 認証アカウント、およびサイト登録フォームがクリアされます。それでも頑張ってください。
     */
    fun logoutAll(views: List<WebView> = emptyList(), database: WebViewDatabase? = null) {
        cookies.removeSessionCookies(null)
        cookies.removeAllCookies(null)
        cookies.flush()
        storage.deleteAllData()
        views.forEach { runCatching { it.clearCache(true) } }
        database?.let { runCatching { it.clearHttpAuthUsernamePassword() } }
        registry?.clear()
    }

    companion object {
        /** ホスト名と、登録可能なドメイン名までのすべてのレベルの上位ドメイン。登録可能なドメイン名が認識できない場合は、そのドメイン名のみが認識されます。 */
        fun domainsFor(host: String): List<String> {
            val top = WebUrlPolicy.registrable(host) ?: return listOf(host)
            val out = mutableListOf<String>()
            var d = host
            while (true) {
                out += d
                if (d == top || !d.contains('.')) break
                d = d.substringAfter('.')
            }
            return out
        }

        /** Cookie ヘッダー内の名前。重複を削除し、順序を維持します。 */
        fun cookieNames(header: String?): List<String> =
            header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

        /** Cookie を期限切れにする方法は 2 つあります。ホストにのみ属する方法と、ドメイン全体に属する方法です。 */
        fun expiryLines(name: String, domain: String) = listOf(
            "$name=; Max-Age=0; Path=/",
            "$name=; Max-Age=0; Path=/; Domain=.$domain",
        )
    }
}
