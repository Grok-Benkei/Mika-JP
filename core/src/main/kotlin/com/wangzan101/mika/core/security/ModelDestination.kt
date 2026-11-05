package com.wangzan101.mika.core.security

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
object ModelEndpointWatch {
    data class Change(val destination: ModelDestination)

    @Volatile var announce: (Change) -> Unit = {}
    @Volatile var previousKey: String? = null
    @Volatile var persist: (String) -> Unit = {}

    /** 変わったとき true。初回の記録は通知しない。 */
    fun note(name: String, baseUrl: String): Boolean {
        val destination = ModelDestination.parse(name, baseUrl) ?: return false
        val key = "${destination.name}|${destination.host}|${destination.secure}"
        val prev = previousKey
        if (key == prev) return false
        previousKey = key
        persist(key)
        if (prev != null) announce(Change(destination))
        return prev != null
    }
}
