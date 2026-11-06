package com.wangzan101.mika.core.security

import com.wangzan101.mika.core.net.LocalHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 画面データが向かう先。表示はプロバイダ名と実際のホストだけ。 */
data class ModelDestination(val name: String, val host: String, val secure: Boolean) {
    val label: String get() = "$name ($host)"

    companion object {
        fun parse(name: String, baseUrl: String): ModelDestination? {
            val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
            val shown = name.trim().ifEmpty { url.host }
            return ModelDestination(shown, url.host, url.isHttps)
        }

        fun label(name: String, baseUrl: String): String? = parse(name, baseUrl)?.label
    }
}

/**
 * 宛先が変わったときだけ通知する。鍵やパスは渡さない。
 * [previousKey] が null の初回は記録するだけで通知しない。
 */
/**
 * 保存済みのモデル宛先と違うホストへ変えるときは、ユーザーの保存操作で true を渡す。
 * ループバックは確認不要。モデルや設定ツールからは true を渡さない。
 */
object ModelDestinationConsent {
    /** 保存済みのキーは name|host|secure または name|host|secure|port。 */
    fun needsConfirmation(previousKey: String?, nextUrl: String): Boolean {
        val next = nextUrl.trim().toHttpUrlOrNull() ?: return true
        if (LocalHosts.isLoopback(next.host)) return false
        if (previousKey.isNullOrBlank()) return false
        val parts = previousKey.split("|")
        val prevHost = if (parts.size == 1) parts[0] else parts.getOrNull(1) ?: return true
        if (!next.host.equals(prevHost, ignoreCase = true)) return true
        val prevSecure = parts.getOrNull(2)?.toBooleanStrictOrNull()
        if (prevSecure != null && prevSecure != next.isHttps) return true
        val prevPort = parts.getOrNull(3)?.toIntOrNull()
        if (prevPort != null && prevPort != next.port) return true
        val prevPath = parts.getOrNull(4)
        if (prevPath != null && prevPath != pathOf(next)) return true
        return false
    }

    fun normalized(baseUrl: String): String? {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}${pathOf(url)}"
    }

    fun keyOf(name: String, baseUrl: String): String? {
        val destination = ModelDestination.parse(name, baseUrl) ?: return null
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        return "${destination.name}|${destination.host}|${destination.secure}|${url.port}|${pathOf(url)}"
    }

    internal fun pathOf(url: okhttp3.HttpUrl): String {
        val raw = url.encodedPath.trimEnd('/')
        return if (raw.isEmpty()) "/" else raw
    }
}

object ModelEndpointWatch {
    data class Change(val destination: ModelDestination)

    @Volatile var announce: (Change) -> Unit = {}
    @Volatile var previousKey: String? = null
    @Volatile var persist: (String) -> Unit = {}

    /** 変わったとき true。初回の記録は通知しない。 */
    fun note(name: String, baseUrl: String): Boolean {
        val destination = ModelDestination.parse(name, baseUrl) ?: return false
        val key = ModelDestinationConsent.keyOf(name, baseUrl) ?: return false
        val prev = previousKey
        if (key == prev) return false
        previousKey = key
        persist(key)
        if (prev != null) announce(Change(destination))
        return prev != null
    }
}
