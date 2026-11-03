package com.wangzan101.mika.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * は、`スキーム://[ユーザー名:パスワード@]ホスト:ポート` の形式のネットワーク プロキシ アドレスです。プロトコルは、http、https、socks5、socks5h のいずれかで、背後にパスはありません。
 * [toString] にはアカウントのパスワードがなく、ログに書き込まれたり、インターフェイスに表示されたりしても漏洩しません。
 */
class ProxyAddress private constructor(
    val scheme: String,
    /** ホスト名または IP。角括弧なしの IPv6。 */
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
) {
    val socks: Boolean get() = scheme == "socks5" || scheme == "socks5h"
    val hasCredentials: Boolean get() = user.isNotEmpty()

    /**
     * ミカ独自の接続（機種調整、アップデートダウンロード、内蔵ブラウザ）は利用できますか？ https プロキシは、まずプロキシ自体で TLS をマスターする必要があります。
     * これらの接続に使用されるネットワーク ライブラリはサポートしていないため、コンテナ内のコマンド (curl など) のみが使用できます。
     */
    val forApp: Boolean get() = scheme != "https"

    fun javaProxy(): Proxy = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    /** は、アカウントのパスワードなしで、プロトコル、ホスト、ポートを人々が見ることができるように書かれています。 */
    val display: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    override fun toString(): String = display

    companion object {
        val SCHEMES = setOf("http", "https", "socks5", "socks5h")

        /** はプロキシ アドレスを解析します。空の文字列または誤って書き込まれた文字列は null です。 */
        fun parse(text: String): ProxyAddress? {
            val v = text.trim()
            if (v.isEmpty() || v.any { it.isWhitespace() }) return null
            val u = runCatching { URI(v) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme !in SCHEMES) return null
            val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (u.port !in 1..65535) return null
            if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/") || u.rawQuery != null || u.rawFragment != null) return null
            val info = u.userInfo.orEmpty()
            val user = info.substringBefore(':')
            val password = if (info.contains(':')) info.substringAfter(':') else ""
            return ProxyAddress(scheme, host, u.port, user, password)
        }

        /** 空の文字列 (プロキシは必要ありません)、または解決可能なプロキシ アドレス。 */
        fun valid(text: String): Boolean = text.isBlank() || parse(text) != null
    }
}
