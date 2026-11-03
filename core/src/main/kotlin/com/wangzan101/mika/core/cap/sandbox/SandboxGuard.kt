package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.guard.actionSignature
import com.wangzan101.mika.core.guard.OffScreenGuard

/** タスク内のサンドボックス コマンドは、最大でこの時間実行できます。 */
const val SANDBOX_BUDGET_MS = 30 * 60_000L
private val SANDBOX_COMMANDS = setOf(RunCommand.name, RunBundledScript.name)
private const val SANDBOX_LOOP_WARN = 3
private const val SANDBOX_LOOP_STOP = 5
private const val SANDBOX_LOOP_HINT = "This exact command has failed several times in a row. Do not repeat it unchanged: read the error, then try a different approach."

/** サンドボックス コマンド ガード (タスクごとに 1 つのコピー): 同じコマンドが繰り返されて失敗した回数、および累積実行時間。スクリーン ガードはサンドボックス ツールを参照しません。 */
class SandboxGuard(private val budgetMs: Long = SANDBOX_BUDGET_MS) : OffScreenGuard {
    private var usedMs = 0L
    private var lastSignature: String? = null
    private var repeats = 0

    override var stop: GuardVerdict? = null
        private set

    /** 時間がなくなった場合の置換実行の結果。そうでない場合は null です。 */
    override fun exhausted(tool: String): JsonObject? {
        if (tool !in SANDBOX_COMMANDS || usedMs < budgetMs) return null
        return buildJsonObject {
            put("ok", false); put("error", "sandbox_budget_exhausted")
            put("message", "Sandbox commands in this task have used their 30 minutes. Finish with what you have, or end_not_done.")
            put("recoverable", false); put("usedMs", usedMs)
        }
    }

    /** コマンドの制限時間は残り時間を超えず、コマンドは累積時間の上限を超えません。パラメータが正しくない場合は、拒否するためにツール層に任せます。 */
    override fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall {
        if (call.name !in SANDBOX_COMMANDS || args.isEmpty()) return call
        val leftSec = ((budgetMs - usedMs) / 1000).coerceAtLeast(1)
        val given = args["timeoutSec"]
        val requested = if (given == null) DEFAULT_TIMEOUT_SEC.toLong()
        else (given as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.toLong() ?: return call
        if (requested !in 1..600 || requested <= leftSec) return call
        return call.copy(argsJson = JsonObject(args + ("timeoutSec" to JsonPrimitive(leftSec))).toString())
    }

    override fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict> {
        if (tool !in SANDBOX_COMMANDS) return emptyList()
        (content["durationMs"] as? JsonPrimitive)?.longOrNull?.let { usedMs += it }
        if ((content["ok"] as? JsonPrimitive)?.booleanOrNull != false) {
            lastSignature = null; repeats = 0
            return emptyList()
        }
        val sig = actionSignature(tool, args)
        repeats = if (sig == lastSignature) repeats + 1 else 1
        lastSignature = sig
        if (repeats < SANDBOX_LOOP_WARN) return emptyList()
        val v = GuardVerdict("sandbox_loop", if (repeats >= SANDBOX_LOOP_STOP) Severity.Stop else Severity.Warn, buildJsonObject {
            put("tool", tool); put("repeatCount", repeats); put("hint", SANDBOX_LOOP_HINT)
        })
        if (v.severity == Severity.Stop) stop = v
        return listOf(v)
    }
}
