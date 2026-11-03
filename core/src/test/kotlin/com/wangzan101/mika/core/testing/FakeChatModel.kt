package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 偽モデルが受信したリクエスト（メッセージリストはその時のコピーです）。 */
data class FakeRequest(val system: String, val messages: List<Message>, val tools: List<ToolSpec>) {
    val toolNames: List<String> get() = tools.map { it.name }
}

/**
 * スクリプトに従って応答するモデル: 各リクエストはスクリプト内の次の項目を取得し、応答を返すか、[ModelError] をスローします。受信したすべてのリクエストは [requests] に記録されます。
 * スクリプトが終了後に呼び出されるとき、アサーション エラーが報告されます (ループがスクリプトより 1 回多く要求することを示します)。 [fakeModel] を使用してスクリプトを作成します。
 */
class FakeChatModel(
    override val name: String,
    override val capabilities: Capabilities,
    script: List<(FakeRequest) -> ModelReply>,
) : ChatModel {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<FakeRequest>()
    /** スクリプト内のまだ使用されていない項目の数。シーンの終わりでは 0 になるはずです。 */
    val remaining: Int get() = queue.size

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val req = FakeRequest(system, messages.toList(), tools.toList())
        requests += req
        val next = queue.removeFirstOrNull() ?: throw AssertionError("$name の用意した応答が尽きました。${requests.size}回目のリクエストに対応する応答がありません")
        return next(req)
    }
}

fun fakeModel(
    name: String = "fake",
    contextTokens: Int = 128_000,
    vision: Boolean = false,
    toolCalls: Boolean = true,
    script: FakeScript.() -> Unit,
): FakeChatModel = FakeChatModel(
    name, Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = contextTokens, reasoning = false),
    FakeScript(name).apply(script).items.toList(),
)

/** スクリプト: 1 つの項目が 1 つのリクエストに対応します。 */
class FakeScript internal constructor(private val prefix: String) {
    internal val items = mutableListOf<(FakeRequest) -> ModelReply>()
    private var seq = 0
    private fun nextId() = "${prefix}_${++seq}"

    /** は 1 回限りの応答です。 [stop] が書かれていない場合、ツールは ToolUse と呼ばれ、それ以外の場合は EndTurn と呼ばれます。 */
    fun reply(stop: StopReason? = null, build: ReplyBuilder.() -> Unit) {
        items += { ReplyBuilder(::nextId).apply(build).build(stop) }
    }

    /** は、受信したリクエストに従って即座に応答します (たとえば、最後の結果の snapshotId を使用します)。 */
    fun replyTo(stop: StopReason? = null, build: ReplyBuilder.(FakeRequest) -> Unit) {
        items += { req -> ReplyBuilder(::nextId).apply { build(req) }.build(stop) }
    }

    fun call(name: String, vararg args: Pair<String, Any?>) = reply { call(name, *args) }
    fun text(vararg chunks: String) = reply { text(*chunks) }
    /** 電話なしの返信: ユーザーに与えられる最終的な回答。 */
    fun answer(text: String) = text(text)
    fun reportFailure(message: String) = call("end_not_done", "message" to message)

    /** 出力は上限に達したため切り詰められます: 半分のテキストのみ、理由 MaxTokens を停止します。 */
    fun truncated(partial: String) = reply(StopReason.MaxTokens) { text(partial) }

    /** パラメーターは有効な JSON ツール呼び出しではありません。 */
    fun malformedCall(name: String, rawArgs: String = "{\"ref\":") = reply { callJson(name, rawArgs) }

    fun error(e: ModelError) { items += { throw e } }
    fun serverError(status: Int = 503) = error(ModelError.Server(status))
    fun rateLimited(retryAfterMs: Long? = 0) = error(ModelError.RateLimited(retryAfterMs))
    fun networkError() = error(ModelError.Network())
    fun contextOverflow() = error(ModelError.ContextTooLong())

    /** 補助的な役割: 検証の結論; [met] は、画面上の引用を備えた段階的な標準です。 */
    fun verdict(verdict: String, reason: String, vararg met: Pair<String, String?>) = text(buildJsonObject {
        put("checks", buildJsonArray {
            met.forEach { (c, q) -> add(buildJsonObject { put("criterion", c); put("status", if (verdict == "fail") "unmet" else "met"); put("quote", q) }) }
        })
        put("verdict", verdict); put("reason", reason)
    }.toString())

    fun repeat(times: Int, block: FakeScript.() -> Unit) { kotlin.repeat(times) { block() } }
}

class ReplyBuilder internal constructor(private val nextId: () -> String) {
    private val parts = mutableListOf<Part>()
    private var usage: Usage? = null

    /** 各ブロックにはテキスト フラグメントがあり、ブロックに到着するテキストをシミュレートします (ループ内で順番に結合されます)。 */
    fun text(vararg chunks: String) { chunks.forEach { parts += Part.Text(it) } }
    fun thinking(text: String) { parts += Part.Thinking(text) }
    fun call(name: String, vararg args: Pair<String, Any?>) { callJson(name, JsonObject(args.associate { (k, v) -> k to json(v) }).toString()) }
    fun callJson(name: String, argsJson: String, opaque: String? = null) { parts += Part.ToolCall(nextId(), name, argsJson, opaque) }
    fun usage(input: Int, output: Int, cached: Int = 0) { usage = Usage(input, output, cached) }

    internal fun build(stop: StopReason?) =
        ModelReply(parts.toList(), stop ?: if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, usage)
}

private fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::json))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    else -> error("未対応の引数型です: ${v::class}")
}
