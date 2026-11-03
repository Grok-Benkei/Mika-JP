package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** この元の会話で選択されたルートとスロット値 (`{1}`、`{2}` の順)。 */
data class RouteChoice(val route: Route, val slots: List<String>)

/**
 * タスクの開始時に、過去に成功したルートを検索します。まず、アプリとリテラル間の類似性に基づいて候補を選択し、次にモデル呼び出し (候補がない場合は調整なし) を使用してスロット値を選択して抽出します。
 * コードをもう一度確認してください。スロット値の数は正しい必要があり、それぞれが元のストーリーにそのまま表示される段落である必要があります。 [store] は、ルート数を周期的に更新するためにも使用されます。
 */
class RouteMatcher(private val model: ChatModel, val store: RouteStore, private val knowledge: KnowledgeStore?) {

    fun candidates(goal: String, limit: Int = 5): List<Route> {
        val apps = knowledge?.matchTask(goal, Int.MAX_VALUE)?.flatMap { it.packages }?.toSet().orEmpty()
        val g = bigrams(goal)
        return store.all().filter { !it.disabled }
            .map { it to jaccard(g, bigrams(SLOT.replace(it.template, "\u0000"))) }
            .filter { (r, sim) -> sim >= MIN_SIMILARITY || r.packages.any(apps::contains) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun choose(goal: String): RouteChoice? {
        val list = candidates(goal)
        if (list.isEmpty()) return null
        val text = list.withIndex().joinToString("\n", "ユーザーの依頼（原文）:$goal\n\n手順のテンプレート:\n") { (i, r) -> "${i + 1}. ${r.template}" }
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) { model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(text)), Author.Mika)), emptyList()) } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, list, goal)
    }

    companion object {
        const val MIN_SIMILARITY = 0.3
        const val TIMEOUT_MS = 20_000L
        private val SLOT = Regex("\\{\\d+\\}")
        private val SLOT_N = Regex("\\{(\\d+)\\}")

        private fun squash(s: String) = s.filterNot { it.isWhitespace() }

        private fun occurrences(s: String, v: String): Int {
            var n = 0; var i = s.indexOf(v)
            while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
            return n
        }

        val PROMPT: String by lazy { Prompts.role("route_match") }

        internal fun parse(text: String, list: List<Route>, goal: String): RouteChoice? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject } catch (e: Exception) { null } ?: return null
            val pick = o["route"]
            if (pick == null || pick is JsonNull) return null
            val idx = (pick as? JsonPrimitive)?.intOrNull ?: return null
            val route = list.getOrNull(idx - 1) ?: return null
            val slots = (o["slots"] as? JsonArray ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
            if (slots.size != route.slots) return null
            // 各スロット値は元の文に 1 回だけ出現し、スロット値はテンプレートに埋め戻されて元の文が正確に取得されます。スロットの境界はテンプレートの固定テキストと位置合わせされます。
            // (「Zhang Sanfeng」から「Zhang San」を抽出することはできません)
            if (slots.any { it.isBlank() || occurrences(goal, it) != 1 }) return null
            var unknown = false
            val filled = SLOT_N.replace(route.template) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { unknown = true; "" } }
            if (unknown || squash(filled) != squash(goal)) return null
            return RouteChoice(route, slots)
        }

        /** 文字タプル。切り取る前後のプレースホルダー (\u0000)。 */
        internal fun bigrams(s: String): Set<String> =
            s.lowercase().filterNot { it.isWhitespace() }.windowed(2).filter { '\u0000' !in it }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
    }
}
