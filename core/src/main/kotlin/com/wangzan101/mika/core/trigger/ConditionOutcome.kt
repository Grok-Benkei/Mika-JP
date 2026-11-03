package com.wangzan101.mika.core.trigger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** マシンの状態チェックの結果。解析の失敗と実行の失敗は、条件の失敗とみなされません。 */
sealed interface ConditionOutcome {
    data class Checked(val met: Boolean, val evidence: String?) : ConditionOutcome
    data class Error(val reason: String) : ConditionOutcome

    companion object {
        fun parse(answer: String): ConditionOutcome {
            val raw = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return Error("条件の確認から読み取り可能な結果が返されませんでした")
            val met = (body["met"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return Error("条件の確認結果にmetの状態がありません")
            val evidence = (body["evidence"] as? JsonPrimitive)?.content?.take(500)
            return Checked(met, evidence)
        }
    }
}
