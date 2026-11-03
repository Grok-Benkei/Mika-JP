package com.wangzan101.mika.core.knowledge

enum class Surface { Android, Web, Desktop }

/** アプリ(APP.md,[Surface.Android]) またはサイト(SITE.md,[Surface.Web]) ナレッジのコピー。 */
data class KnowledgeEntry(
    val name: String,
    val display: String,
    val surface: Surface,
    val packages: List<String>,
    val domains: List<String>,
    val aliases: List<String>,
    val open: String?,
    val description: String,
    val body: String?,
    /** 同じアプリに複数のパッケージ名がある場合、エイリアスはそれぞれのパッケージ名に対応します（例：「Meituan Takeout」→ Takeout 独立パッケージ）。 */
    val packageAliases: Map<String, String> = emptyMap(),
    /** は実際のデバイスによって検証されたページであり、open_link を通じて直接アクセスできます。ヘッダーは `links: ["名称 | uri", "名称 | uri | 包名", …]` と書き込まれます。 */
    val links: List<Link> = emptyList(),
    /** 同じサービスの別の実行サーフェスのエントリ名: アプリ エントリはそのサイトを書き込み、サイト エントリはそのアプリを書き込みます。 */
    val related: List<String> = emptyList(),
    /** サイト コマンドの宣言 (サイト内でのみ使用可能)。スクリプト テキストは [GuideCatalog.siteCommands] から取得されます。 */
    val commands: List<SiteCommandDecl> = emptyList(),
    /** テキストには、ミカの独学と未検証のアプリ知識の試用版が添付されています。 */
    val trial: Boolean = false,
) {
    /** アプリと同じサービスのサイトによって共有される名前: サイトがアプリに関連付けられている場合のアプリの名前。 */
    val service: String get() = if (surface == Surface.Web) related.firstOrNull() ?: name else name
}

/** は直接リンクです。 Web ページのリンクには、[packageName] (どのアプリが開かれるか) が含まれている必要があります。 */
data class Link(val name: String, val uri: String, val packageName: String? = null)
