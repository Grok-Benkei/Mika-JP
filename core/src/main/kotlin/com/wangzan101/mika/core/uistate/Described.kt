package com.wangzan101.mika.core.uistate

import kotlin.math.hypot

/**
 * は番号を与えず、説明に従って要素を参照します。 [text] は表示される単語またはアクセス可能な名前 (入力時の入力ボックスのプロンプトになることもあります)、[role] はオプションです。
 * [field] が true の場合、入力ボックス (入力用) のみが見つかります。
 */
data class ElementQuery(val text: String, val role: String? = null, val field: Boolean = false)

sealed interface DescribedMatch {
    /** [exact]: 名前 (またはプロンプト) と説明はまったく同じです。それ以外の場合は、で始まるか、またはを含むことによって検索され、その結果によって実際に誰が一致するかが明らかになるはずです。 */
    data class Found(val element: UiElement, val exact: Boolean) : DescribedMatch
    /** は複数の用途に適しています。最初に操作できるのは[candidates]で、残りは画面読み上げ順となります。 */
    data class Ambiguous(val candidates: List<UiElement>) : DescribedMatch
    data object None : DescribedMatch
}

/**
 * 説明によって画面内の唯一の要素を検索します。比較の前に空白を削除し、大文字と小文字を区別しません。厳密なレイヤーから広範なレイヤーまで検索し、最初に結果が得られたレイヤーを使用します。
 * 名前 (またはプロンプト) がまったく同じ → 名前が説明で始まる → 名前に説明が含まれます (テキストに誤ってこれらの単語が含まれることを避けるために、このレイヤーは操作可能な要素のみを認識します)。
 * 同じレイヤーで操作できるもの（入力できるかどうかをタイプするもの、クリックできるかどうかを確認するもの、使用できないものはカウントされません）が優先されます。
 * が複数ある、もしくはどれも操作できないが一致するものが複数ある。それは [DescribedMatch.Ambiguous] です、推測ではありません。 [role] このキャラクターが与えられた場合のみ見てください。
 */
object DescribedTarget {
    fun match(elements: List<UiElement>, q: ElementQuery): DescribedMatch {
        val want = norm(q.text)
        if (want.isEmpty()) return DescribedMatch.None
        val pool = elements.filter { e ->
            (!q.field || e.editable) && (q.role == null || e.role.equals(q.role.trim(), ignoreCase = true))
        }
        val tiers: List<Pair<(String) -> Boolean, Boolean>> = listOf(
            { s: String -> s == want } to false, { s: String -> s.startsWith(want) } to false, { s: String -> want in s } to true,
        )
        for ((i, tier) in tiers.withIndex()) {
            val (test, actionableOnly) = tier
            val hits = pool.filter { e -> labels(e, q.field).any(test) && (!actionableOnly || usable(e, q.field)) }
            if (hits.isEmpty()) continue
            val usable = hits.filter { usable(it, q.field) }
            return when {
                usable.size == 1 -> DescribedMatch.Found(usable[0], exact = i == 0)
                usable.isEmpty() && hits.size == 1 -> DescribedMatch.Found(hits[0], exact = i == 0)
                else -> DescribedMatch.Ambiguous(usable + (hits - usable.toSet()))
            }
        }
        return DescribedMatch.None
    }

    /** の操作は、タイプで入力できるかどうかを確認し、その他の項目をクリックまたは入力できるかどうかを確認します。利用不可としてマークされたページはカウントされません。 */
    fun usable(e: UiElement, field: Boolean): Boolean =
        UiStates.DISABLED !in e.states && (if (field) e.editable else e.clickable || e.editable)

    /** は、[e] に最も近い名前を持つ別の文字です (名前は異なります)。これは、候補が同じ名前を持つ場合にどの文字であるかを識別するために使用できます。 */
    fun neighbour(e: UiElement, elements: List<UiElement>): String? = elements
        .filter { it !== e && it.name.isNotBlank() && norm(it.name) != norm(e.name) }
        .minByOrNull { hypot((it.centerX - e.centerX).toDouble(), (it.centerY - e.centerY).toDouble()) }?.name

    /** 要素を説明できる単語: 名前;入力ボックスにも独自のプロンプトがあります。 */
    private fun labels(e: UiElement, field: Boolean): List<String> =
        listOfNotNull(e.name, e.placeholder.takeIf { field || e.editable }).map(::norm).filter { it.isNotEmpty() }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}
