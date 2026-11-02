package com.wangzan101.mika.core.net

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/** URL のホスト名とサイト: 「どの Web サイトがあるか」 (画面の読み取り、実行ステータス、知識の一致、スキルのドラフト) はすべてそれに基づいて計算されます。 */
object UrlHost {
    /** は、一般的な 2 セクションのパブリック サフィックスです。 */
    private val TWO_PART_SUFFIXES = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.mo", "com.sg", "com.au", "com.br",
        "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
    )

    /** ホスティング プラットフォーム: 各ユーザーのサブドメイン名は異なるサイトであり、プラットフォームのドメイン名自体がパブリック サフィックスとして使用されます。 */
    private val PRIVATE_SUFFIXES = setOf(
        "github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "herokuapp.com",
        "amazonaws.com", "workers.dev", "web.app", "appspot.com", "firebaseapp.com",
    )

    /** は、国のトップレベル ドメイン名の 2 番目のパブリック サフィックスとしてよく使用される単語です。 */
    private val GENERIC_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

    /** 小文字の ASCII ドメイン名 (xn-- 形式に変換された国際化ドメイン名)、ポートと www を削除します。初めに。解析されない場合は null になります。 */
    fun of(url: String): String? = raw(url)?.removePrefix("www.")?.ifEmpty { null }

    /**
     * 2 つのアドレスが同じサイトであるかどうか: ホスト名が同じ (www を除く)、またはそれらが判断できる同じ登録可能なドメイン名に属しているかどうか。
     * はプロトコルとポートを調べません。接尾辞が不明な場合は別のサイトとして判断されます。
     */
    fun sameSite(a: String, b: String): Boolean {
        val ha = of(a) ?: return false
        val hb = of(b) ?: return false
        if (ha == hb) return true
        val sa = registrable(ha) ?: return false
        return sa == registrable(hb)
    }

    internal fun raw(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        // 非 ASCII 文字を含むホスト名 URI はホストを解決できません。それは権威から取られます。
        val h = u.host ?: u.rawAuthority?.let(::hostOfAuthority) ?: return null
        val ascii = runCatching { IDN.toASCII(h.trimEnd('.')) }.getOrNull()?.lowercase() ?: return null
        return ascii.ifEmpty { null }
    }

    private fun hostOfAuthority(authority: String): String? {
        val a = URLDecoder.decode(authority.substringAfterLast('@'), "UTF-8")
        if (a.startsWith("[")) return a.substringBefore(']') + "]"
        return a.substringBefore(':').ifEmpty { null }
    }

    /** はドメイン名を登録できます (パブリック サフィックスに段落を追加します)。サフィックスが不確かな場合、またはホスト名がサフィックスそのものである場合は、null になります。 */
    fun registrable(host: String): String? {
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        fun tail(n: Int) = labels.takeLast(n).joinToString(".")
        val suffixLen = when {
            tail(2) in PRIVATE_SUFFIXES || tail(2) in TWO_PART_SUFFIXES -> 2
            labels.last().length == 2 && labels.size >= 2 &&
                (labels[labels.size - 2].length <= 3 || labels[labels.size - 2] in GENERIC_SECOND_LEVEL) -> 2
            else -> 1
        }
        if (labels.size <= suffixLen) return null
        return tail(suffixLen + 1)
    }
}
