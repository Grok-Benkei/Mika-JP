package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * は、リモート受信箱内のコメントの最終状態と宛先の保存形式です。これは受信レイヤーでのみ使用されます: フィールド タイプ ([TaskState]、[FailKind])。名前を変更したり値を追加しても、ディスク上の単語は変更されません。
 * フォーマットを読み取れない場合 (フォーマットが壊れている、カテゴリが新しいバージョンで書き込まれている場合)、例外はスローされず、読み取れない場合は失敗が返され、コンピュータは引き続き最終状態を取得します。
 */
internal object RemoteTaskRecord {
    private val json = Json { ignoreUnknownKeys = true }

    /** ディスク上の読み取りできない最終状態はすべて、次のように報告されます。 */
    val UNREADABLE: TaskState.Terminal = TaskState.Failed("The saved result of this task could not be read.", FailKind.Environment)

    fun encode(state: TaskState.Terminal): String = buildJsonObject {
        when (state) {
            is TaskState.Succeeded -> {
                put("type", "done"); put("answer", state.answer); put("verified", state.verified)
                state.note?.let { put("note", it) }
                if (state.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { state.unconfirmed.forEach { add(it) } }
                put("reviewed", state.reviewed)
            }
            is TaskState.Failed -> { put("type", "failed"); put("reason", state.reason); put("kind", wire(state.kind)) }
            TaskState.Cancelled -> put("type", "cancelled")
        }
    }.toString()

    fun decode(text: String): TaskState.Terminal = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        when (o.str("type")) {
            "done" -> TaskState.Succeeded(
                answer = o.str("answer") ?: return UNREADABLE,
                verified = o.bool("verified") ?: true, note = o.str("note"),
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                reviewed = o.bool("reviewed") ?: true,
            )
            "failed" -> TaskState.Failed(o.str("reason").orEmpty(), kind(o.str("kind")))
            "cancelled" -> TaskState.Cancelled
            else -> UNREADABLE
        }
    }.getOrDefault(UNREADABLE)

    fun encodeSteers(steers: List<RemoteSteer>): String = buildJsonArray {
        steers.forEach { s -> addJsonObject { put("messageId", s.messageId); put("state", s.state) } }
    }.toString()

    /** 壊れたエントリはスキップされ、結果全体には影響しません。 */
    fun decodeSteers(text: String): List<RemoteSteer> = runCatching {
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RemoteSteer(o.str("messageId") ?: return@mapNotNull null, o.str("state") ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /** ディスク上のカテゴリ名は個別に決定され、列挙後に名前が変更されることはありません。新しく追加されたカテゴリはここではコンパイルできないため、名前を決定する必要があります。 */
    private fun wire(kind: FailKind): String = when (kind) {
        FailKind.GaveUp -> "gave_up"
        FailKind.Model -> "model"
        FailKind.Guard -> "guard"
        FailKind.StepLimit -> "step_limit"
        FailKind.Environment -> "environment"
        FailKind.Interrupted -> "interrupted"
    }

    /** は、以前に列挙名を直接書き込んだレコード (`Interrupted` など) も認識します。認識されない場合（新しいバージョンで書き込まれた場合）、動作環境のエラーとみなされます。 */
    private fun kind(raw: String?): FailKind = FailKind.entries.firstOrNull { wire(it) == raw }
        ?: FailKind.entries.firstOrNull { it.name == raw } ?: FailKind.Environment

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
