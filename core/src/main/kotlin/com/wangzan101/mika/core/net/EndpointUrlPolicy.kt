package com.wangzan101.mika.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * モデルと WebSocket の宛先をコード側で検査する。
 * Network Security Config だけに頼らない。クラウドの平文 HTTP は拒否し、
 * ループバック HTTP はローカルモデル用に許可し、LAN の平文は明示設定のときだけ許可する。
 */
object EndpointUrlPolicy {
    data class Verdict(val allowed: Boolean, val reason: String) {
        companion object {
            val Allow = Verdict(true, "ok")
            fun reject(reason: String) = Verdict(false, reason)
        }
    }

    fun checkModel(url: String, allowLanCleartext: Boolean): Verdict = check(url, allowLanCleartext, websocket = false)

    /**
     * [allowLoopbackInsecure] が false のときは、ループバックの ws/http も拒否する（リレー本番）。
     * 一般のモデル宛先では true。
     */
    fun checkWebSocket(url: String, allowLanCleartext: Boolean, allowLoopbackInsecure: Boolean = true): Verdict =
        check(url, allowLanCleartext, websocket = true, allowLoopbackInsecure = allowLoopbackInsecure)

    /** https → http、または wss → ws は、宛先がループバックでも拒否する。 */
    fun checkRedirect(fromUrl: String, toUrl: String, allowLanCleartext: Boolean): Verdict {
        val from = classify(fromUrl)
        val to = classify(toUrl)
        if (from == null || to == null) return Verdict.reject("bad_url")
        if (from.secure && !to.secure) return Verdict.reject("http_downgrade")
        if (!from.host.equals(to.host, ignoreCase = true) || from.port != to.port) return Verdict.reject("cross_host")
        return checkModel(toUrl, allowLanCleartext)
    }

    fun isCleartext(url: String): Boolean = classify(url)?.secure == false

    /** Android の NSC は IP リテラルを base-config で扱う。ループバック IP の平文だけ localhost に書き換える。 */
    fun loopbackHttp(url: HttpUrl): HttpUrl {
        if (url.scheme != "http" || !LocalHosts.isLoopbackAddress(url.host)) return url
        return url.newBuilder().host("localhost").build()
    }

    private fun check(url: String, allowLanCleartext: Boolean, websocket: Boolean, allowLoopbackInsecure: Boolean = true): Verdict {
        val kind = classify(url) ?: return Verdict.reject("bad_url")
        if (websocket && kind.scheme !in WEBSOCKET_SCHEMES && kind.scheme !in HTTP_SCHEMES) return Verdict.reject("bad_scheme")
        if (!websocket && kind.scheme !in HTTP_SCHEMES) return Verdict.reject("bad_scheme")
        if (kind.userInfo) return Verdict.reject("userinfo")
        if (kind.secure) return Verdict.Allow
        if (kind.loopback) return if (allowLoopbackInsecure) Verdict.Allow else Verdict.reject("http_loopback")
        if (kind.lan) return if (allowLanCleartext) Verdict.Allow else Verdict.reject("http_lan")
        return Verdict.reject("http_public")
    }

    private data class Kind(val scheme: String, val secure: Boolean, val host: String, val port: Int, val loopback: Boolean, val lan: Boolean, val userInfo: Boolean)

    private fun classify(raw: String): Kind? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val normalized = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            else -> trimmed
        }
        val scheme = normalized.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val secure = scheme == "https" || trimmed.startsWith("wss://", ignoreCase = true)
        val cleartextScheme = scheme == "http" || trimmed.startsWith("ws://", ignoreCase = true)
        if (!secure && !cleartextScheme && scheme !in HTTP_SCHEMES && scheme !in WEBSOCKET_SCHEMES) return null
        return Kind(
            scheme = trimmed.substringBefore("://").lowercase(),
            secure = url.isHttps || trimmed.startsWith("wss://", ignoreCase = true),
            host = url.host,
            port = url.port,
            loopback = LocalHosts.isLoopback(url.host),
            lan = LocalHosts.isLan(url.host),
            userInfo = url.username.isNotEmpty() || url.password.isNotEmpty(),
        )
    }

    private val HTTP_SCHEMES = setOf("http", "https")
    private val WEBSOCKET_SCHEMES = setOf("ws", "wss", "http", "https")
}
