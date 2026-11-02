package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** モデル定義を送信するツール。パラメーター テーブルは、スキーマの生成と不明なフィールドの拒否の両方に使用されます。 */
internal class ToolDef(
    val name: String,
    val description: String,
    val props: Map<String, JsonObject> = emptyMap(),
    val required: List<String> = emptyList(),
) {
    val allowed: Set<String> = props.keys + "reason"
    /** パラメーターの理由は、追加の目的ステートメントではなく、ツール自体のフィールド (テイクオーバーの理由など) です。 */
    val ownReason = "reason" in props

    fun spec() = ToolSpec(name, description, buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (k, v) -> put(k, v) }
            if (!ownReason) put("reason", str(REASON_NOTE))
        })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }.toString())
}

internal fun str(d: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "string"); put("description", d)
    enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

internal fun int(d: String, min: Int? = null, max: Int? = null) = buildJsonObject {
    put("type", "integer"); put("description", d)
    min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
}

internal fun bool(d: String) = buildJsonObject { put("type", "boolean"); put("description", d) }

internal fun strArray(d: String, max: Int) = buildJsonObject {
    put("type", "array"); put("description", d); put("maxItems", max)
    put("items", buildJsonObject { put("type", "string") })
}

/**
 * 各ツールに添付する理由。画面にステップの説明として表示するため、入力言語にかかわらず日本語で書く。
 */
internal const val REASON_NOTE = "このツールを呼ぶ理由を日本語で短く書けます。ユーザーにステップの説明として表示されます。入力言語にかかわらず日本語で書いてください。"

internal const val COORD = "Normalized 0-999 (0,0 is top-left, 999,999 is bottom-right) over the latest screen."
internal const val REF = "Element ref such as @e3 from the latest read of this screen; must be paired with its snapshotId."
internal const val SNAP = "snapshotId of the screen read that produced the ref. A mismatch returns ref_expired."
