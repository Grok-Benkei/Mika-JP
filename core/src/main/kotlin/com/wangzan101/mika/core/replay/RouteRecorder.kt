package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * 完了した走行をルートとして記録します。 [RunEvent.ActionTarget] と、乗っ取り、尋問、確認、障害、エンディングなどのイベントのみを見てください。
 * いずれかの項目が今回​​は記録すべきではないことを示している場合 (ユーザー アクション、支払い、パスワード ボックス、スクリーンショット対策、ブラウザ ツールまたはサンドボックス ツールの使用が必要)、null が返されます。
 */
object RouteRecorder {
    /** が使用されており、この方法はユーザーや他のチャンネルから分離できず、これに従って再生するのは安全ではありません。 */
    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET = Regex("\"secret\"\\s*:\\s*false")

    /** スロット値は少なくともこの長さです。短すぎる単語は、元の単語に誤って出現する傾向があります。 */
    private const val MIN_SLOT = 2

    /**
     * 入力できる単語は、元の文に 1 回だけ出現する断片のみです (スロットとして記録されます)。そうでない場合（固定語、質問、他の場所からのコピー）、文全体は記録されません。
     * ルートには、ユーザーが元の単語で発言していない単語が含まれることはありません。ターゲット要素の名前も、元のエピソードに表示されるときにスロットに関連付けられる必要があり、再生中に新しいオリジナル エピソードとして認識されます。
     * が完了したかどうかは、[com.wangzan101.mika.core.agent.LearnGate] を押した後に発信者によって記録されます。ここでは、ルート自体を記憶できるかどうかだけを確認します。
     */
    fun record(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex): Route? {
        if (events.lastOrNull { it is RunEvent.TaskEnded }?.let { (it as RunEvent.TaskEnded).outcome } != "succeeded") return null
        if (events.any { spoils(it, tools) }) return null
        if (events.any { it is RunEvent.ActionTarget && it.incomplete }) return null

        val acts = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective && tools[it.tool]?.routable == true }
        if (acts.isEmpty() || acts.size > MAX_ROUTE_STEPS || acts.any { it.protected }) return null
        val results = events.filterIsInstance<RunEvent.ToolResult>()

        // スロット値は最初に使用された順に番号が付けられます
        val slots = mutableListOf<String>()
        fun bind(v: String): Boolean {
            if (v in slots) return true
            if (v.trim().length < MIN_SLOT || occurrences(goal, v) != 1) return false
            slots += v
            return true
        }
        val args = mutableListOf<JsonObject>()
        val targets = mutableListOf<Selector?>()
        for (a in acts) {
            val o = parse(a.argsJson) ?: return null
            var cleaned = o.filterKeys { it != "ref" && it != "snapshotId" && it != "target" }
            if (tools.verb(a.tool) == Verb.TypeText) {
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                // 入力がパスワード ボックスにないことをポートが明確に示していない場合、その入力は記憶されません。
                if (text == Redactor.MASK) return null
                val r = a.callId?.let { id -> results.lastOrNull { it.callId == id } } ?: return null
                if (!NOT_SECRET.containsMatchIn(r.resultJson)) return null
                if (!bind(text)) return null
                cleaned = cleaned + ("text" to JsonPrimitive("{${slots.indexOf(text) + 1}}"))
            }
            args += JsonObject(cleaned)
            targets += a.target?.let { t ->
                val n = t.name
                when {
                    n.isEmpty() -> t
                    n in goal -> if (bind(n)) t.copy(name = "{${slots.indexOf(n) + 1}}") else return null
                    // には今回のパラメータ（「Zhang Sanに送信」など）が含まれています：名前では識別されません
                    slots.any { it in n } -> t.copy(name = "", uniqueAtRecord = false)
                    else -> t
                }
            }
        }
        // 原作のスロットの位置は重複できません（短いスロットが長いスロットに飲み込まれた場合、個別に置き換えることはできません）
        val ranges = slots.map { v -> goal.indexOf(v).let { it until it + v.length } }
        if (ranges.indices.any { i -> ranges.indices.any { j -> i < j && ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last } }) return null
        var template = goal
        ranges.withIndex().sortedByDescending { it.value.first }.forEach { (i, r) -> template = template.replaceRange(r, "{${i + 1}}") }

        fun anchors(a: Anchors, target: Selector?) = Anchors(a.packageName, a.texts.map(String::trim).filter { s ->
            RouteAnchors.stable(s) && s != target?.name?.trim() && slots.none { it.trim() in s }
        }.distinct().take(RouteAnchors.MAX))

        val steps = acts.mapIndexed { i, a -> RouteStep(a.tool, args[i].toString(), targets[i], anchors(a.before, a.target), anchors(a.after, null)) }
        val packages = (acts.mapNotNull { a -> if (tools.verb(a.tool) == Verb.LaunchApp) parse(a.argsJson)?.get("package")?.let { (it as? JsonPrimitive)?.contentOrNull } else null } +
            acts.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }).distinct()
        val route = Route(UUID.randomUUID().toString(), template, slots.size, packages, emptyMap(), steps, lastUsedMs = nowMs)
        return route.takeIf { RouteCodec.encode(it).toByteArray().size <= MAX_ROUTE_BYTES }
    }

    private fun occurrences(s: String, v: String): Int {
        var n = 0; var i = s.indexOf(v)
        while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
        return n
    }

    private fun spoils(e: RunEvent, tools: ToolIndex): Boolean = when (e) {
        is RunEvent.Takeover, is RunEvent.Confirmation -> true
        is RunEvent.State -> e.name in WAITING_STATES
        is RunEvent.Barrier -> e.kind == "payment"
        is RunEvent.ModelReply -> e.tool?.let { barred(it, tools) } == true
        is RunEvent.ToolResult -> barred(e.tool, tools) || PROTECTED.containsMatchIn(e.resultJson)
        is RunEvent.ActionTarget -> barred(e.tool, tools)
        else -> false
    }

    /** ルートは、再生できるインターフェイス上のメソッドのみを記憶します。他の機能 (Web ページ、サンドボックス) を持つツールを使用したため、再生は不完全です。 */
    private fun barred(tool: String, tools: ToolIndex): Boolean {
        val t = tools[tool] ?: return false
        if (t.verb in NO_ROUTE_VERBS) return true
        val routed = tools.names.mapNotNull { tools[it]?.takeIf { i -> i.routable }?.capability }.toSet()
        return t.capability != null && t.capability !in routed
    }

    private fun parse(s: String): JsonObject? = try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }
}
