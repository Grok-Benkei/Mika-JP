package com.wangzan101.mika.core.knowledge

/**
 * ホスト名がどのサイトに属しているかに関する情報: エントリのドメインに従って、ホスト名が特定のドメイン名またはそのサブドメイン名と等しい場合は正しいです。
 * 複数のコピーが一致する場合、最も長く一致するドメイン名が選択されます (example.com よりも shop.example.com など、最も具体的なドメイン名が適しています)。
 * は、これを使用してサイト ナレッジ (KnowledgeStore.byDomain) を挿入し、サイト コマンド (GuideCatalog.siteCommands) を検索します。両方の側で同じコピーが認識されます。
 */
object SiteMatch {
    /** [host] は、[domains] の最長のドメイン名の長さと一致します。一致しない場合は null になります。 */
    fun score(host: String, domains: List<String>): Int? {
        val h = host.lowercase().removePrefix("www.")
        return domains.map { it.lowercase() }.filter { h == it || h.endsWith(".$it") }.maxOfOrNull { it.length }
    }

    /** [items] は、[host] の最も具体的なものと一致します (並列のものは保持され、順序は変更されません)。一致しない場合は空になります。 */
    fun <T> best(host: String, items: List<T>, domains: (T) -> List<String>): List<T> {
        val scored = items.mapNotNull { i -> score(host, domains(i))?.let { i to it } }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
