package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Anthropic Messages API。
 * - 履歴内のシステム メッセージはシステム テキストにマージされます (Anthropic にはシステム ロール メッセージがありません)。
 * - 思考の強度が選択されているときに適応的思考 (`thinking.type = adaptive`) をオンにし、ギアを `output_config.effort` に入れます。 `none` を選択した場合はオフにしてください。
 *   リストは、どの考え方 ([thinkingTypes]) がモデルによって受け入れられるかを示し、受け入れられない考え方は送信されません (何も送信されず、サーバーのデフォルト設定になります)。
 *   思考ブロック `signature` は、それを受け取ったアドレスとともに [Part.Thinking.opaque] ([SignedThinking]) に格納され、
 *   ツール呼び出しによるラウンドはそのまま同じアドレスに返され (返されない場合はサーバーによって拒否されます)、ブロックの順序は受信したままになります。
 *   署名は、生成時にシステム プロンプト ワード、ツール、および以前のメッセージにもバインドされます。これらの変更 (ツール セットの拡張、履歴の圧縮など) の後、Claude 5.1 以降のモデルは
 *   は、返された思考ブロック ([STALE_THINKING]) を拒否します。この時点で、思考ブロックを削除し、ドキュメントの回復方法に従って再送信します（ミカはこれらのモデルに対してのみ適応的思考を使用します、とドキュメントには記載されています）
 *   適応的思考では、アシスタント ラウンドが思考ブロックから始める必要はありません。ここでは、今回持ち込まれたものはすべて削除されます ([dropStaleThinking] を参照)。その後のリクエストではそれらは持ち込まれなくなります。
 *   ([staleThinking])。
 * - 同じ役割の隣接するメッセージがマージされます (Anthropic ではユーザーとアシスタントの交代が必要です)。
 */
class AnthropicModel(
    baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 応答の上限 (`max_tokens`、必須)。 null デフォルトでは: [THINKING_MAX_TOKENS] が有効になり、それ以外の場合は [DEFAULT_MAX_TOKENS] になります。 */
    maxTokens: Int? = null,
    /** 各リクエストにはヘッダーが含まれます ([ClientHeaders] を参照)。 Key ヘッダーはアダプター自体によって追加されます。 */
    private val headers: Map<String, String> = emptyMap(),
    /** ユーザーが選択した思考の強度。 null は送信されず、`none` は思考を停止し、残りは `output_config.effort` として送信されます。 */
    private val reasoningEffort: String? = null,
    /**
     * リストは、このモデルで受け入れられる考え方を示します ([ListedModel.thinkingTypes] を参照)。 `adaptive` を含まないギアは発行されず、`disabled` を含まないギアは `none` を発行しません。
     * null リストには記載されていないので（ギアは他のリスト記述方法から来ています）、ギアに従って送信されます。
     */
    private val thinkingTypes: List<String>? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"
    /** `thinking.type` 実際に送信されました: `adaptive`、`disabled`、または null (送信されません)。 */
    private val thinkingType: String? = when (reasoningEffort) {
        null -> null
        "none" -> "disabled"
        else -> "adaptive"
    }?.takeIf { thinkingTypes == null || it in thinkingTypes }
    private val maxTokens = maxTokens ?: if (thinkingType == "adaptive") THINKING_MAX_TOKENS else DEFAULT_MAX_TOKENS
    private val json = Json { ignoreUnknownKeys = true }
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }
    /**
     * 拒否されたリクエストに含まれていた思考ブロック ([Part.Thinking.opaque]) は履歴にまだ存在しており、後続のリクエストには含まれません。
     * 各タスクはモデルを構築し、サブタスクはそれを共有するため、複数のコルーチンが同時に使用される可能性があります。
     */
    private val staleThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.execute(http, request(system, messages, tools, stream = false))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = AnthropicStream(endpoint, onText, ::parseUsage)
        // が拒否されたときにイベントはありませんでした (HttpSupport.stream は 2xx ではない場合に受け入れを調整しません)。再送信してからこのストリームを使用します
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    /**
     * は 1 回送信されます。サーバーが、返された思考ブロックのプレフィックスが一致しない([STALE_THINKING])と言うと、今回持ち込まれたすべての思考ブロックが[staleThinking]に記録され、再度(1回だけ)送信されます。
     * ドキュメントに記載されている方法では、エラーが報告された部分とその後の部分を削除します。ここでそれらをすべて削除すると、場所を見つけるために `messages.N` を押す必要はありません。その代償として、以前から効果的な思考を行う必要がなくなりました。
     * 今回思考ブロックがない場合は再送されず、通常通りエラーが報告されます。
     */
    private suspend fun dropStaleThinking(messages: List<Message>, send: suspend () -> RawResponse): RawResponse {
        val sent = messages.filter { it.role == Role.Assistant }.flatMap { it.parts }.filterIsInstance<Part.Thinking>()
            .filter(::replays).mapNotNull { it.opaque }
        val first = send()
        if (first.status != 400 || STALE_THINKING !in first.body || !staleThinking.addAll(sent)) return first
        return send()
    }

    /** このブロックは返されません。思考がオフになっている場合は送信されません。他のアドレスから受信した場合には送信されません (署名は送信元のサービスによってのみ認識されます)。拒否されたリクエスト ([staleThinking]) では送信されません。 */
    private fun replays(p: Part.Thinking): Boolean {
        val opaque = p.opaque ?: return false
        return thinkingType != "disabled" && opaque !in staleThinking && SignedThinking.block(p, endpoint) != null
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)
            // Anthropic は空のテキスト ブロックを拒否し、システムが空の場合はフィールド全体が省略されます。
            if (systemText.isNotBlank()) put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text"); put("text", systemText); put("cache_control", ephemeral)
                })
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEachIndexed { i, t -> add(toolJson(t, last = i == tools.lastIndex)) }
            })
            put("messages", buildMessages(messages))
            when (thinkingType) {
                "disabled" -> putJsonObject("thinking") { put("type", "disabled") }
                "adaptive" -> {
                    putJsonObject("thinking") {
                        put("type", "adaptive")
                        put("display", "summarized")
                    }
                    putJsonObject("output_config") { put("effort", reasoningEffort) }
                }
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .postInference(InferenceProtocol.Anthropic, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 || status == 403 -> ModelError.Auth()
        status == 402 || body.contains("credit balance is too low") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 413 || body.contains("prompt is too long") -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
        status == 529 || body.contains("overloaded_error") -> ModelError.Server(529)
        status in 500..599 -> ModelError.Server(status)
        else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec, last: Boolean): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        put("input_schema", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("ツール ${t.name} のパラメータ schema が正しいJSONではありません", e)
        })
        if (last) put("cache_control", ephemeral)
    }

    private fun buildMessages(messages: List<Message>): JsonArray {
        // (ロール、ブロック) 隣接する同じロールに従ってマージしてから出力します
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val blocks = if (role == "assistant") assistantBlocks(m) else userBlocks(m)
            if (blocks.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += blocks else merged += role to blocks.toMutableList()
        }
        // 会話は最後のユーザー メッセージまでキャッシュされます。次のステップの開始がそれと同じである場合、そのステップはキャッシュからフェッチされます。アシスタント メッセージは思考ブロックで終わっている可能性があり、マークされていません
        val lastUser = merged.indexOfLast { it.first == "user" }
        return buildJsonArray {
            merged.forEachIndexed { i, (role, blocks) ->
                val content = if (i == lastUser) blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to ephemeral)) else blocks
                add(buildJsonObject { put("role", role); put("content", JsonArray(content)) })
            }
        }
    }

    // tool_result は、同じユーザー メッセージの他のブロックよりも前にある必要があります
    private fun userBlocks(m: Message): List<JsonObject> {
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", r.callId)
                put("content", r.content); put("is_error", r.isError)
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64"); put("media_type", p.mime); put("data", p.pngBase64)
                    }
                }
                p is Part.Image -> buildJsonObject {
                    put("type", "text"); put("text", "[screenshot omitted: the current model cannot see images]")
                }
                else -> null
            }
        }
        return results + rest
    }

    // ツール呼び出しによるラウンドでは、思考を開始する際に、前回のアシスタントメッセージの思考ブロックをそのまま署名とともに持ち帰らなければなりません。ブロックの順序は受け取ったとおりです。
    // 新しいモデルに対してツールを呼び出す前に書かれた進行状況の説明は、テキストの後ろに配置された別個の思考ブロックです。先頭に移動すると、サーバーは前のコンテンツが変更されたものとして扱います。 現在のリクエストの思考構成と競合しないように、思考を明示的にオフにする場合、
    // は含まれません。また、他のアドレスから受信して拒否されたリクエストにも含まれません ([replays])。
    private fun assistantBlocks(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
            is Part.Thinking -> if (replays(p)) SignedThinking.block(p, endpoint) else null
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("type", "text"); put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                put("type", "tool_use"); put("id", p.id); put("name", p.name)
                put("input", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
            }
            else -> null
        }
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["content"]?.jsonArray ?: throw ModelError.BadResponse("contentがありません")
            val parts = mutableListOf<Part>()
            content.forEach { el ->
                val b = el.jsonObject
                when (b["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                    "thinking" -> {
                        val text = (b["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val signature = (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        if (text.isNotEmpty() || signature != null) parts += Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) })
                    }
                    "redacted_thinking" -> (b["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { parts += Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                    "tool_use" -> parts += Part.ToolCall(
                        b["id"]!!.jsonPrimitive.content,
                        b["name"]!!.jsonPrimitive.content,
                        (b["input"] ?: JsonObject(emptyMap())).toString(),
                    )
                }
            }
            val stop = when ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull) {
                "end_turn", "stop_sequence" -> StopReason.EndTurn
                "tool_use" -> StopReason.ToolUse
                "max_tokens" -> StopReason.MaxTokens
                else -> StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usage"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("input_tokens") ?: return null
        val cached = n("cache_read_input_tokens") ?: 0
        // Anthropic の input_tokens にはキャッシュ部分が含まれていません。総入力口径に統一されます。 Output_tokens には思考が含まれますが、個別にレポートされません。reasoningTokens は 0 として記録されます。
        return Usage(inT + cached + (n("cache_creation_input_tokens") ?: 0), n("output_tokens") ?: 0, cached)
    }
}

private class AnthropicStream(
    private val endpoint: String,
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Block(
        val type: String, val id: String = "", val name: String = "",
        val text: StringBuilder = StringBuilder(), val input: StringBuilder = StringBuilder(), val initialInput: String = "{}",
        val signature: StringBuilder = StringBuilder(), val data: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, Block>()
    private val visible = StringBuilder()
    private var usage: JsonObject? = null
    private var reason: String? = null
    private var stopped = false

    fun accept(data: String) {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("ストリーミング応答が正しいJSONではありません", e) }
        when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "message_start" -> usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
            "content_block_start" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val b = root["content_block"] as? JsonObject ?: return
                val type = (b["type"] as? JsonPrimitive)?.contentOrNull ?: return
                blocks[index] = Block(type,
                    id = (b["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = (b["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    initialInput = b["input"]?.toString() ?: "{}",
                    data = (b["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                ).also { block ->
                    (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let(block.signature::append)
                    (b["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                }
            }
            "content_block_delta" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val block = blocks[index] ?: return
                val delta = root["delta"] as? JsonObject ?: return
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { block.text.append(it) }
                    "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature.append(it) }
                    "input_json_delta" -> (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let { block.input.append(it) }
                }
            }
            "message_delta" -> {
                reason = ((root["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull ?: reason
                // の使用量は累積値です。整数を含むフィールド (出力、場合によっては入力、キャッシュ) は message_start をカバーし、値のないフィールドと null は使用されます。それらは最後に解析されます。
                (root["usage"] as? JsonObject)?.let { delta ->
                    usage = JsonObject(usage.orEmpty() + delta.filterValues { (it as? JsonPrimitive)?.intOrNull != null })
                }
            }
            "message_stop" -> stopped = true
            "error" -> throw ModelError.BadResponse(root["error"].toString().take(200))
        }
    }

    fun finish(): ModelReply {
        // message_delta が stop_reason をもたらすと、すべてのコンテンツ ブロックが収集され、message_stop だけが欠落します。それは完了したものとみなされます。どちらも接続が途中で切断されたことを意味します。
        if (!stopped && reason == null) throw ModelError.StreamCut()
        val parts = blocks.values.mapNotNull { block ->
            when (block.type) {
                "text" -> block.text.toString().takeIf(String::isNotEmpty)?.let(Part::Text)
                "thinking" -> {
                    val text = block.text.toString()
                    val signature = block.signature.toString().takeIf(String::isNotEmpty)
                    if (text.isNotEmpty() || signature != null) Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) }) else null
                }
                "redacted_thinking" -> block.data.takeIf(String::isNotEmpty)?.let { Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                "tool_use" -> {
                    if (block.id.isEmpty() || block.name.isEmpty()) throw ModelError.BadResponse("ツール呼び出しのストリームに名前またはIDがありません")
                    Part.ToolCall(block.id, block.name, block.input.toString().ifEmpty { block.initialInput })
                }
                else -> null
            }
        }
        val stop = when (reason) {
            "end_turn", "stop_sequence" -> StopReason.EndTurn
            "tool_use" -> StopReason.ToolUse
            "max_tokens" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usageOf(usage))
    }
}

/** はデフォルトの応答制限です。思考がオンになると、思考によってこの割り当ても考慮されます。 4K は答えが出る前に使い果たされて、[THINKING_MAX_TOKENS] に与えられることがよくあります。 */
private const val DEFAULT_MAX_TOKENS = 4096
private const val THINKING_MAX_TOKENS = 16_000

/**
 * 返された思考ブロックのプレフィックスが、エラー メッセージ内の文 (人類文書の「保存された思考」セクションの原文) と一致しません。
 * には、「`thinking` ブロック内の `signature` が無効です」という文の前半だけがあります。この文がなければ、署名自体が壊れています。再送信はされず、通常どおりエラーが報告されます。
 */
private const val STALE_THINKING = "bound to a different conversation"

/**
 * は、[Part.Thinking.opaque] の人間的思考ブロックに記録されます。署名された思考は `{"endpoint":…,"signature":…}` です。
 * redacted_ Thinking は `{"endpoint":…,"redacted":…}` です。署名は、それを送信したサービスによってのみ認識され、再生中に 1 つのアドレスによってのみ認識されます。
 * 他のアドレス (別の Anthropic 互換サービス) および他のアダプターは返されません。
 */
internal object SignedThinking {
    fun signed(endpoint: String, signature: String): String =
        buildJsonObject { put("endpoint", endpoint); put("signature", signature) }.toString()

    fun redacted(endpoint: String, data: String): String =
        buildJsonObject { put("endpoint", endpoint); put("redacted", data) }.toString()

    /** [p] は、思考ブロックが [endpoint] から受信されたときに再生に使用されるものです。他の場所から来たもので認識できない場合は、null になります。 */
    fun block(p: Part.Thinking, endpoint: String): JsonObject? {
        val o = p.opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
        (o["redacted"] as? JsonPrimitive)?.contentOrNull?.let { data ->
            return buildJsonObject { put("type", "redacted_thinking"); put("data", data) }
        }
        val signature = (o["signature"] as? JsonPrimitive)?.contentOrNull ?: return null
        return buildJsonObject { put("type", "thinking"); put("thinking", p.text); put("signature", signature) }
    }
}
