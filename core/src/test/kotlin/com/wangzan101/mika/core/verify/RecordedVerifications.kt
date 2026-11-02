package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * の実行では、各検証 (検証者イベント) は検証された最終応答とペアになっています (新しいレコードは呼び出しなしの応答、古いレコードは終了呼び出し、
 * ([RunFacts.isFinalAnswer] を参照) は、この 2 つの順序には依存しません。通常の手順では、最初にチェックし、検証後に応答を記録し、最後のラウンドでは、最初に応答を記録してからチェックします。
 *
 * - ステップ付きイベント：同じステップの解答を搭載。
 * - 古いレコードにはステップがありません。最初のモデル応答は、まだ一致していない応答です。つまり、それです (最初に確認してから応答を書き留めます)。それ以外の場合は、まだ一致していないその前にある最新の回答と一致します。
 *
 * は (検証者の添字、回答の添字) を返します。不適格な検証は含まれません。
 */
internal object RecordedVerifications {
    fun pairs(events: List<RunEvent>): List<Pair<Int, Int>> {
        val used = mutableSetOf<Int>()
        fun finishAt(i: Int) = RunFacts.isFinalAnswer(events[i]) && i !in used
        val out = mutableListOf<Pair<Int, Int>>()
        events.forEachIndexed { v, e ->
            if (e !is RunEvent.Verifier) return@forEachIndexed
            val f = if (e.step != null) {
                events.indices.lastOrNull { finishAt(it) && (events[it] as RunEvent.ModelReply).step == e.step }
            } else {
                val next = (v + 1 until events.size).firstOrNull { events[it] is RunEvent.ModelReply }
                next?.takeIf(::finishAt) ?: (v - 1 downTo 0).firstOrNull(::finishAt)
            } ?: return@forEachIndexed
            used += f
            out += v to f
        }
        return out
    }

    /** 検証 [v] その際に使用できる資料：検証されたものを除く過去の出来事、自分自身の回答と結果を完成させます（回答は自分自身を証明するために使用することはできません）。 */
    fun before(events: List<RunEvent>, v: Int, f: Int): List<RunEvent> {
        val step = (events[f] as RunEvent.ModelReply).step
        return events.subList(0, v).filterIndexed { i, e ->
            i != f && !(e is RunEvent.ToolResult && e.tool == "finish" && e.step == step)
        }
    }

    /** その時点で比較できるテキスト、つまり以前のすべての画面読み上げテキストとツール結果内のすべての文字列を確認します ([before] を参照)。 */
    fun sources(events: List<RunEvent>, v: Int, f: Int): List<String> = before(events, v, f).mapNotNull { b ->
        when (b) {
            is RunEvent.Observed -> b.text.takeIf { b.kind == "read" }
            is RunEvent.ToolResult -> runCatching { strings(Json.parseToJsonElement(b.resultJson)).joinToString("\n") }.getOrDefault(b.resultJson)
            else -> null
        }
    }.filter { it.isNotBlank() }

    /**
     * 証拠台帳に従って、ランタイム検証者によって取得されたマテリアルを再構築します。終了後の画面の再読み取りは記録されず、代わりに最後に読み取られた画面が使用されます。
     * プラス元帳 ([RunFacts.ledger]) は、実行時に [Verifier] と同じ選択方法を使用して、回答によってエントリを選択します。 確認した
     */
    fun ledgerSources(events: List<RunEvent>, v: Int, f: Int, goal: String, answer: String): List<String> {
        val seen = before(events, v, f)
        val fresh = seen.filterIsInstance<RunEvent.Observed>().lastOrNull { it.kind == "read" }?.text?.takeIf { it.isNotBlank() }
        return listOfNotNull(fresh) + RunFacts.ledger(seen, RECORDED_TOOLS).forAnswer(goal, answer).map { it.text }
    }

    /** の回答: 回答がタスクの終了の場合、task_endedの全文が取得されます(回答内の回答は切り捨てられる場合があります)。 */
    fun answer(events: List<RunEvent>, f: Int): String? {
        val r = events[f] as RunEvent.ModelReply
        val last = events.indexOfLast(RunFacts::isFinalAnswer) == f
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.takeIf { last && r.tool == null && it.outcome == "succeeded" }
        return ended?.answerOrReason ?: RunFacts.answerOf(r)
    }

    fun arg(json: String?, key: String): String? = runCatching {
        ((Json.parseToJsonElement(json.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }
}
