package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import java.net.URLEncoder

/**
 * アドレスバーやホーム画面の検索欄への入力を、開くURLへ変換する。
 * - 空白を含まず、`scheme://` で始まる入力はそのまま返す。実際に開けるかは [WebUrlPolicy.checkNavigable] で判定する。
 * - localhost、IPv4、角括弧で囲んだIPv6形式には `http://` を補う。ポートやパスも指定できる。
 * - ドメイン名の形式なら `https://` を補う。各ラベルは英数字とハイフンで構成し、末尾は2文字以上の英字かxn--形式とする。ポートやパスも指定できる。
 * - それ以外はBingの検索URLにする。空白を含む文、単語、メールアドレスなども検索として扱う。
 */
object AddressInput {
    const val SEARCH_PREFIX = "https://www.bing.com/search?q="

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val TLD = Regex("^([A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")
    private val PORT = Regex("^\\d{1,5}$")

    /** 前後の空白を除いて判定し、空の入力なら null を返す。 */
    fun resolve(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return search(s)
        if (SCHEME.containsMatchIn(s)) return s
        val (host, port) = authority(s) ?: return search(s)
        if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return search(s)
        return when {
            host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || IPV6.matches(host) -> "http://$s"
            domain(host) -> "https://$s"
            else -> search(s)
        }
    }

    fun search(query: String): String = SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    /** このクラスのBing検索URLから検索語を復元する。別のURL、デコードの失敗、空の検索語なら null。 */
    fun searchQuery(url: String): String? {
        if (!url.startsWith(SEARCH_PREFIX)) return null
        val q = url.removePrefix(SEARCH_PREFIX).substringBefore('&').substringBefore('#')
        return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 最初の /・?・# より前を、ホストと任意のポートに分ける。空、@を含む入力、区切りを解釈できない形式は null。 */
    private fun authority(s: String): Pair<String, String?>? {
        val auth = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (auth.isEmpty() || '@' in auth) return null
        if (auth.startsWith("[")) {
            val end = auth.indexOf(']')
            if (end < 0) return null
            val rest = auth.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            return auth.substring(0, end + 1) to rest.removePrefix(":").ifEmpty { null }
        }
        val parts = auth.split(':')
        return when (parts.size) {
            1 -> parts[0] to null
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun domain(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { LABEL.matches(it) } && TLD.matches(labels.last())
    }
}
