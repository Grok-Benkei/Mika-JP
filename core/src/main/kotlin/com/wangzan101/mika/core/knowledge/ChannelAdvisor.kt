package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.net.UrlHost

/**
 * チャネル選択の事実: インストールされているパッケージ名、Mika にログインしているサイトのホスト名、内蔵ブラウザが使用できるかどうか、
 * 最近、Mika で人間とコンピュータの認証またはアクセスブロックのためのサイトのホスト名に遭遇しました。
 */
data class ChannelFacts(
    val installed: Set<String>,
    val loggedInSites: Set<String>,
    val webAvailable: Boolean,
    val challengedSites: Set<String> = emptySet(),
)

/** は、タスクの最初に「利用可能なチャネル」の行を表示します。コードは事実のみを提供し、どれを選択するかは Web ページのルールに従ってモデルによって決定されます。 */
object ChannelAdvisor {
    private const val HEADER = "Available channels: "
    /** には、各タスクに挿入される参照コピーの最大数に相当する上位数個のみがリストされます。 */
    private const val MAX_CHANNELS = 3

    /**
     * 例: 「利用可能なチャネル: Xiaohongshu - アプリ: インストール済み (com.xingin.xhs)、アプリの練習あり、Web ページ: xiaohongshu.com、Web ページの練習あり、Mika (m.xiaohongshu.com) にログイン」。
     * [entries] マッチング順序に従って、最初の [MAX_CHANNELS] チャネルのみが取得されます。何も言うことはありません。
     */
    fun line(entries: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val parts = entries.groupBy { it.service }.mapNotNull { (name, same) -> channel(name, same, skills, facts) }.take(MAX_CHANNELS)
        if (parts.isEmpty()) return null
        return HEADER + parts.joinToString(" | ")
    }

    private fun channel(name: String, same: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val packages = same.flatMap { it.packages }.distinct()
        // アプリ エントリに関連付けられたサイト名は、サイトのメイン ドメイン名です。
        val domains = same.flatMap { it.domains + if (it.surface == Surface.Android) it.related else emptyList() }.map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }.distinct()
        val app = if (packages.isEmpty()) null else {
            val state = packages.firstOrNull { it in facts.installed }?.let { "app: installed ($it)" } ?: "app: not installed"
            if (skills?.hasRoute(name, "android") == true) "$state, known way to do it in the app" else state
        }
        val web = domains.firstOrNull()?.takeIf { facts.webAvailable }?.let { d ->
            fun ofSite(hosts: Set<String>) = hosts.firstOrNull { s -> domains.any { dd -> UrlHost.sameSite("https://$dd", "https://$s") } }
            val loggedIn = ofSite(facts.loggedInSites)
            val challenged = ofSite(facts.challengedSites)
            val notes = listOfNotNull(
                "known way to do it on the web".takeIf { skills?.hasRoute(name, "browser") == true },
                loggedIn?.let { "signed in within Mika ($it)" },
                challenged?.let { "recently hit a verification or block here ($it); the user can clear this site's data in settings" },
            )
            (listOf("web: $d") + notes).joinToString(", ")
        }
        if (app == null && web == null) return null
        return "${same.first().display} — " + listOfNotNull(app, web).joinToString("; ")
    }
}
