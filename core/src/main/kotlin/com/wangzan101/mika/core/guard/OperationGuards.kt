package com.wangzan101.mika.core.guard

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

enum class Severity(val wire: String) { Warn("warn"), Stop("stop") }

/**
 * 衛兵からの結論。リマインダー ([Severity.Warn]) と [nudge]: 詰まりの場所を説明し、いくつかの解決方法を提供する段落 (モデルに送信されます)。停止はありません。
 */
data class GuardVerdict(val code: String, val severity: Severity, val evidence: JsonObject, val nudge: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code); put("severity", severity.wire); put("evidence", evidence)
        nudge?.let { put("nudge", it) }
    }
}

/** パラメーターの順序が署名に影響しないように、キーのソート後の安定したシリアル化。 */
private fun stable(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${stable(e[it]!!)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { stable(it) }
    else -> e.toString()
}

private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val SWIPE_KEYS = listOf("startX", "startY", "endX", "endY")

/** アクション署名: 理由を無視し、座標は 50 に丸められ、スライド方向のみが記録されます。 */
fun actionSignature(tool: String, args: JsonObject): String {
    val n = args.toMutableMap()
    n.remove("reason")
    val x = num(n["x"]); val y = num(n["y"])
    if (x != null && y != null) {
        n["x"] = JsonPrimitive(floor(x / 50).toLong())
        n["y"] = JsonPrimitive(floor(y / 50).toLong())
    }
    val s = SWIPE_KEYS.map { num(n[it]) }
    if (s.all { it != null }) {
        val dx = s[2]!! - s[0]!!; val dy = s[3]!! - s[1]!!
        val vertical = abs(dy) >= abs(dx)
        n["swipeDirection"] = JsonPrimitive("${if (vertical) "V" else "H"}${if ((if (vertical) dy else dx) < 0) "-" else "+"}")
        SWIPE_KEYS.forEach { n.remove(it) }
    }
    return "$tool|${stable(JsonObject(n))}"
}

/**
 * タスクごとに 1 つのオペレーション ガード。アクションが本当に効果がない場合、新しいコンテンツなしで繰り返し読み込まれた場合、またはツールが継続的に失敗した場合にのみ、アクションが通知され、最終的に停止されます。
 * 同じクリック/スクロールや、すでに表示されているインターフェイスの指紋自体は停滞を示しているわけではありません。新しいページまたは読み取りにより、未進行カウントがクリアされます。
 */
class OperationGuards(private val stepBudget: Int? = null) {

    private var frameId: String? = null
    private var frameHash: Long? = null
    private var acted = false
    /** 以前のアクションは、報告された実際の効果に応じて減点されました。 「何か影響はありますか?」: 指紋に従って次のインターフェイスが繰り返されることはありません。 */
    private var effectNoted = false

    /** 効果のない連続アクションの数。 */
    private var unchanged = 0
    private var unchangedWarnedAt = 0

    /** が訪問する各インターフェイスの状態 (要素テーブルのフィンガープリント)。 */
    private val seenStates = HashSet<Long>()
    /** 各状態に戻る回数と、最後に新しい状態に到達してから通知されるしきい値。 */
    private val visits = HashMap<Long, Int>()
    private val visitsWarnedAt = HashMap<Long, Int>()
    /** 次々に見られる状態（隣り合うものは異なります）、最新のもの。 */
    private val trail = ArrayDeque<Long>()
    private var cycleWarnedAt = 0
    /** は、最後に新しい状態になってから、いくつかのアクションを実行しました。 */
    private var sinceNewState = 0
    private var sinceNewWarnedAt = 0

    /** タスク数。 [epoch] は画面が切り替わるたびに 1 ずつ増加し、各セグメントで読み取られたカウントデータは無効になります。 */
    private val reads = GuardScope(canStop = true)
    /** ページ全体で読んだ内容を覚えておいてください。同じページに戻ったときに、古いコンテンツを新しいタスクの進行状況として扱わないでください。 */
    private val taskReads = HashSet<Long>()
    private var epoch = 0

    private var consecutiveFailures = 0
    private var unrecoverableFailures = 0
    private val recentFailures = ArrayDeque<JsonObject>()

    var stop: GuardVerdict? = null
        private set
    val unchangedFrames: Int get() = unchanged
    var progressVersion: Long = 0
        private set

    /** は、カードの取り付け場所 (guard.<code> の場合は [count] と入力) と出口を通知します。止めたい場合は取り付けないでください。 */
    private fun verdict(code: String, severity: Severity, evidence: JsonObject, count: Int, waysOut: Boolean = true, period: Int? = null): List<GuardVerdict> {
        val nudge = if (severity == Severity.Warn) listOfNotNull(
            if (period != null) Feedback.format(LEADS.getValue(code), "count" to count, "states" to period) else Feedback.format(LEADS.getValue(code), "count" to count),
            Feedback["guard.ways_out"].takeIf { waysOut },
        ).joinToString(" ") else null
        val v = GuardVerdict(code, severity, evidence, nudge)
        if (severity == Severity.Stop) stop = v
        return listOf(v)
    }

    private fun <T> ArrayDeque<T>.pushCapped(v: T, cap: Int = 10) { addLast(v); if (size > cap) removeFirst() }

    /** は通話エントリを保持します。アクションのシグネチャは停滞の判定には関与しません。 */
    fun beforeAction(tool: String, args: JsonObject, diagnostic: Boolean = false, scope: GuardScope = reads): List<GuardVerdict> {
        // 同じアクションで新しいコンテンツをページごとに読み取ることができます。タスクの停滞は、アクションのサインだけでは判断できません。
        return emptyList()
    }

    /** インターフェースを変更するアクションが実行されます。 */
    fun noteAction() {
        acted = true
        effectNoted = false
        sinceNewState += 1
    }

    /**
     * アクションによって報告される実際の効果 (効果の種類、core.uistate.EffectKind を参照): no_effect は「効果なし」を 1 回記録します。
     * 明らかに変わった場合、アプリを変更した場合、ページを変更した場合は最初からやり直してください。局所的な小さな変更はカウントされません。
     */
    fun effect(kind: String): List<GuardVerdict> {
        effectNoted = true
        when (kind) {
            NO_EFFECT -> unchanged += 1
            else -> { unchanged = 0; unchangedWarnedAt = 0; return emptyList() }
        }
        return noProgress()
    }

    private fun noProgress(): List<GuardVerdict> {
        val rung = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, canStop = true) ?: return emptyList()
        unchangedWarnedAt = rung.threshold
        return verdict("no_progress", rung.severity, buildJsonObject { put("unchanged", unchanged) }, unchanged)
    }

    /**
     * モデルはインターフェイス (読み取りインターフェイス、またはアクション結果に含まれる) を受信して​​おり、[hash] はそのフィンガープリントです。 「変更を加えてもインターフェースが変わらない」場合にのみ、効果がないとみなされます。
     * (アクションがすでに効果を報告している場合、それは繰り返されません);繰り返し見るだけではカウントされません。アクションの後にどの状態に到達したかは、同じ状態に戻るためにも使用され、循環して新しい状態に到達することはありません。
     */
    fun frame(id: String, hash: Long): List<GuardVerdict> {
        if (frameId == id) return emptyList()
        val same = frameHash == hash
        val didAct = acted
        val noted = effectNoted
        acted = false
        effectNoted = false
        frameId = id
        frameHash = hash
        val out = mutableListOf<GuardVerdict>()
        if (!same) {
            pageChanged()
            if (seenStates.add(hash)) progress()
        }
        if (didAct && !noted) {
            if (same) { unchanged += 1; out += noProgress() } else { unchanged = 0; unchangedWarnedAt = 0 }
        }
        // 古いインターフェイス自体は失敗を証明するものではなく、新しいコンテンツやルートの変更があるかどうかを評価するようモデルに通知するだけです。
        if (didAct) out += noNewState()
        return out
    }

    /** は、アクション後に到達した状態に戻ります。ぐるぐる回りながら[cycle]が言っていたのでここでは繰り返さない。 */
    private fun arrived(hash: Long): List<GuardVerdict> {
        val n = (visits[hash] ?: 1) + 1
        visits[hash] = n
        if (GuardRules.cycle(trail.toList()) != null) return emptyList()
        val rung = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), visitsWarnedAt[hash] ?: 0, canStop = true) ?: return emptyList()
        visitsWarnedAt[hash] = rung.threshold
        return verdict("same_state", rung.severity, buildJsonObject { put("visits", n) }, n)
    }

    private fun cycle(): List<GuardVerdict> {
        val c = GuardRules.cycle(trail.toList())
        if (c == null) { cycleWarnedAt = 0; return emptyList() }
        val rung = GuardRules.climb(c.rounds, GuardRules.cycleLadder(stepBudget), cycleWarnedAt, canStop = true) ?: return emptyList()
        cycleWarnedAt = rung.threshold
        return verdict("state_cycle", rung.severity, buildJsonObject { put("states", c.period); put("rounds", c.rounds) }, c.rounds, period = c.period)
    }

    private fun noNewState(): List<GuardVerdict> {
        val rung = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, canStop = false) ?: return emptyList()
        sinceNewWarnedAt = rung.threshold
        return verdict("no_new_state", rung.severity, buildJsonObject { put("actions", sinceNewState) }, sinceNewState)
    }

    /**
     * 画面の読み取り/Webページ/Webページのスクリプトの一度の読み取り: 画面が変化せず、有効な操作が実行されず、新しい内容を読み取ることができない場合に繰り返し読み取ります(探しているものはこのページにありません)。
     * [fresh] は true です。これは、以前に読んだことのないコンテンツ (ページ全体を読んだり、開始位置を変更したりするなど) を読んだことを意味し、再読み込みとしてカウントされません。
     * ツールを変更するかスクリプトを読むかは問題ではありません。ページは変更されていません。答えはページ上に存在するものです。
     * [window] 指定された場合、個別にカウントされ (ページ スクリプトの 1 回の呼び出しで読み取られます)、タスクのカウントは移動されません。
     */
    fun read(tool: String, fresh: Boolean, window: GuardScope = reads, fingerprint: Long? = null): List<GuardVerdict> {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress()
            return emptyList()
        }
        window.sync(epoch)
        window.rereads += 1
        val n = window.rereads
        val rung = GuardRules.climb(n, GuardRules.REREAD_LADDER, window.warnedAt, window.canStop) ?: return emptyList()
        window.warnedAt = rung.threshold
        return verdict("read_loop", rung.severity, buildJsonObject {
            put("tool", tool); put("rereadsOfUnchangedPage", n)
        }, n)
    }

    /** 最後に画面が変わってからこの結果を読むのは初めてですか? */
    fun firstSeen(fingerprint: Long, window: GuardScope = reads): Boolean {
        window.sync(epoch)
        return window.seen.add(fingerprint)
    }

    /** 画面が変更されました (または有効な操作が実行されました)。以前に読み取られたカウントはカウントされなくなります (すべての読み取りカウントがまとめて無効になります)。 */
    fun pageChanged() {
        epoch += 1
    }

    private fun progress() {
        progressVersion += 1
        unchanged = 0
        unchangedWarnedAt = 0
        sinceNewState = 0
        sinceNewWarnedAt = 0
        if (stop?.code == "no_progress" || stop?.code == "read_loop") stop = null
    }

    fun result(tool: String, content: JsonObject, neutral: Boolean = false): List<GuardVerdict> {
        val ok = (content["ok"] as? JsonPrimitive)?.booleanOrNull
        val countsAsFailure = (content["counts_as_failure"] as? JsonPrimitive)?.booleanOrNull
        if (neutral || countsAsFailure == false || ok != false) {
            consecutiveFailures = 0
            unrecoverableFailures = 0
            return emptyList()
        }
        val recoverable = (content["recoverable"] as? JsonPrimitive)?.booleanOrNull != false
        consecutiveFailures += 1
        if (!recoverable) unrecoverableFailures += 1
        recentFailures.pushCapped(buildJsonObject {
            put("tool", tool); put("recoverable", recoverable)
            (content["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("error", it.content) }
        })
        val severity = GuardRules.failures(consecutiveFailures, unrecoverableFailures) ?: return emptyList()
        return verdict("too_many_failures", severity, buildJsonObject {
            put("consecutive", consecutiveFailures)
            put("unrecoverable", unrecoverableFailures)
            put("recent", JsonArray(recentFailures.toList().takeLast(5)))
        }, consecutiveFailures, waysOut = consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0)
    }

    private companion object {
        const val NO_EFFECT = "no_effect"
        /** は、最近のステータスを記録します (最長のサークル セーフティ ネットが表示されるのに十分です)。 */
        const val TRAIL_KEEP = 64

        /** 各リマインダーの冒頭の文には、カードの場所 (prompts/フィードバック/tools.md) が示されています。 */
        val LEADS = mapOf(
            "repeating_action" to "guard.repeating_action", "no_progress" to "guard.no_progress", "same_state" to "guard.same_state",
            "state_cycle" to "guard.state_cycle", "no_new_state" to "guard.no_new_state", "read_loop" to "guard.read_loop",
            "too_many_failures" to "guard.too_many_failures",
        )
    }
}

/**
 * セクション数: 最新のアクション署名 (繰り返し操作)、読み取られた結果のフィンガープリント、および変更されていないページが再度読み取られた回数。
 * [canStop] が false の場合、リマインダーの最大数はタスクが停止されません。読み取り回数は画面遷移時に無効化され再計算されます([OperationGuards.pageChanged])。
 */
class GuardScope(val canStop: Boolean) {
    internal val loop = ArrayDeque<String>()
    internal var loopWarnedAt = 0
    internal val seen = HashSet<Long>()
    internal var rereads = 0
    internal var warnedAt = 0
    private var epoch = 0

    internal fun sync(now: Int) {
        if (now == epoch) return
        epoch = now
        seen.clear(); rereads = 0; warnedAt = 0
    }
}
