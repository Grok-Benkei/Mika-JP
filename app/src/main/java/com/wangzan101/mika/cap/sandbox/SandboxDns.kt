package com.wangzan101.mika.cap.sandbox

/**
 * サンドボックスの resolv.conf は端末の DNS だけを書く。
 * 読めないときに 1.1.1.1 / 8.8.8.8 へ落とさない。名前解決は失敗して閉じる。
 * 端末自身のリゾルバが公共 DNS でも、それは端末の設定なのでそのまま使う。
 */
internal object SandboxDns {
    fun resolvConf(deviceServers: List<String>?): String {
        val servers = deviceServers.orEmpty().map { clean(it) }.filter { acceptable(it) }.distinct()
        if (servers.isEmpty()) return "# device DNS unavailable; name lookup disabled\n"
        return servers.joinToString("") { "nameserver $it\n" }
    }

    private fun clean(raw: String): String = raw.trim().removePrefix("[").removeSuffix("]").trim()

    private fun acceptable(host: String): Boolean {
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '#' || it == ';' }) return false
        if (host == "0.0.0.0" || host == "::") return false
        return host.length <= 253
    }
}
