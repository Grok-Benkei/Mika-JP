package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GeminigenerateContent ネイティブ インターフェイス。
 * - ロールにはユーザー/モデルのみが含まれます。隣接する同じロールはマージされます。履歴内のシステム メッセージは systemstruct にマージされます。
 * - Gemini はコール番号を提供しません。「gemini-<シリアル番号>-<名前>」を合成します (応答に ID がある場合はこれを使用します)。
 *   functionResponse を返すときに、callId を押して、履歴内の ToolCall で関数名を見つけます。
 * - 思考部分は思考として解釈されます。思考の強度を選択した場合、`generationConfig.thinkingConfig.thinkingLevel` を使用して深さを制御します。
 */
/** Google によって提供されたプレースホルダー署名: 呼び出しが Gemini 自体によって生成されていない場合 (元の署名を持ち出すことができない)、署名検証をスキップするために使用します。 */
internal const val SKIP_SIGNATURE = "skip_thought_signature_validator"

class GeminiModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 各リクエストにはヘッダーが含まれます ([ClientHeaders] を参照)。 Key ヘッダーはアダプター自体によって追加されます。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 応答するトークンの最大数 (`generationConfig.maxOutputTokens`)。 null 制限なし、サーバーのデフォルト。 */
    private val maxOutputTokens: Int? = null,
    /** ユーザーが選んだ思考装置。 nullは送信せず、`none`を可能な限りオフにし、残りは`thinkingConfig.thinkingLevel`として送信します。 */
    private val reasoningEffort: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val streamEndpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:streamGenerateContent?alt=sse"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = GeminiStream(onText, ::parse, ::parseUsage)
        val (status, retryAfter, text) = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val payload = buildJsonObject {
            if (systemText.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
            }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray { tools.forEach { add(toolJson(it)) } })
                })
            })
            put("contents", buildContents(messages))
            if (maxOutputTokens != null || reasoningEffort != null) putJsonObject("generationConfig") {
                maxOutputTokens?.let { put("maxOutputTokens", it) }
                reasoningEffort?.let { effort ->
                    putJsonObject("thinkingConfig") {
                        if (effort == "none") {
                            put("thinkingBudget", 0)
                        } else {
                            put("includeThoughts", true)
                            put("thinkingLevel", effort.uppercase())
                        }
                    }
                }
            }
        }
        return Request.Builder().url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .header("x-goog-api-key", apiKey)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.geminiError(status, retryAfter, body, model)

    // Gemini は、AdditionalProperties / $schema / $id などの OpenAPI サブセット外のキーを受け入れません。
    private val rejectedKeys = setOf("additionalProperties", "\$schema", "\$id", "\$defs")

    // はスキーマ位置でのみ削除されます。プロパティは名前 -> スキーマのマッピングであり、名前自体は変更されません
    private fun sanitize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.filterKeys { it !in rejectedKeys }.mapValues { (k, v) ->
            if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { sanitize(it.value) }) else sanitize(v)
        })
        is JsonArray -> JsonArray(el.map { sanitize(it) })
        else -> el
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("ツール ${t.name} のパラメータ schema が正しいJSONではありません", e)
        }
        // パラメーターなしツール: Gemini はプロパティのないオブジェクト スキーマを拒否し、パラメーターを省略します
        val clean = sanitize(schema)
        val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
        if (hasProps) put("parameters", clean)
    }

    private fun buildContents(messages: List<Message>): JsonArray {
        val nameById = HashMap<String, String>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { nameById[it.id] = it.name } }
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "model" else "user"
            val parts = if (role == "model") modelParts(m) else userParts(m, nameById)
            if (parts.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += parts else merged += role to parts.toMutableList()
        }
        return buildJsonArray {
            merged.forEach { (role, parts) ->
                add(buildJsonObject { put("role", role); put("parts", JsonArray(parts)) })
            }
        }
    }

    private fun userParts(m: Message, nameById: Map<String, String>): List<JsonObject> {
        // functionResponse は functionCall の直後に続き、他の部分より前にランクされます。
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", nameById[r.callId] ?: r.callId)
                    putJsonObject("response") {
                        put("content", r.content)
                        if (r.isError) put("is_error", true)
                    }
                }
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.pngBase64) }
                }
                p is Part.Image -> buildJsonObject { put("text", "[screenshot omitted: the current model cannot see images]") }
                else -> null
            }
        }
        return results + rest
    }

    private fun modelParts(m: Message): List<JsonObject> {
        // ラウンド内の最初の呼び出しのみに署名があります (他の並列呼び出しには署名がありません)。最初のものには署名がなく、呼び出しがこのアダプターによって生成されたものではないことを示しています
        // (モデルを変更した、または通話を再実行した)。Google の指示に従って、検証をスキップするプレースホルダー署名を送信します。
        val firstCall = m.parts.firstOrNull { it is Part.ToolCall }
        return m.parts.mapNotNull { p -> modelPart(p, first = p === firstCall) }
    }

    private fun modelPart(p: Part, first: Boolean): JsonObject? =
        when (p) {
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                (p.opaque ?: SKIP_SIGNATURE.takeIf { first })?.let { put("thoughtSignature", it) }
                putJsonObject("functionCall") {
                    put("name", p.name)
                    put("args", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
                }
            }
            else -> null
        }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) throw ModelError.ContentRefused()
            val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("candidatesがありません")
            val parts = mutableListOf<Part>()
            var calls = 0
            (cand["content"] as? JsonObject)?.get("parts")?.jsonArray?.forEach { el ->
                val p = el.jsonObject
                val fc = p["functionCall"] as? JsonObject
                val t = (p["text"] as? JsonPrimitive)?.contentOrNull
                when {
                    fc != null -> {
                        val name = (fc["name"] as? JsonPrimitive)?.contentOrNull
                            ?: throw ModelError.BadResponse("functionCallにnameがありません")
                        val id = (fc["id"] as? JsonPrimitive)?.contentOrNull ?: "gemini-$calls-$name"
                        val sig = (p["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
                        parts += Part.ToolCall(id, name, (fc["args"] ?: JsonObject(emptyMap())).toString(), sig)
                        calls++
                    }
                    t.isNullOrEmpty() -> {}
                    (p["thought"] as? JsonPrimitive)?.booleanOrNull == true -> parts += Part.Thinking(t)
                    else -> parts += Part.Text(t)
                }
            }
            val usable = parts.any { it !is Part.Thinking }
            val stop = when (val fr = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull) {
                null, "STOP" -> if (calls > 0) StopReason.ToolUse else StopReason.EndTurn
                "MAX_TOKENS" -> StopReason.MaxTokens
                "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    if (usable) StopReason.Other else throw ModelError.ContentRefused()
                else -> if (calls > 0 && fr.isNotEmpty()) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usageMetadata"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("promptTokenCount") ?: return null
        // プロンプトトークンカウントには、統合キャリバーと一致するキャッシュ ヒット部分がすでに含まれています
        val thoughts = n("thoughtsTokenCount") ?: 0
        return Usage(inT, (n("candidatesTokenCount") ?: 0) + thoughts, n("cachedContentTokenCount") ?: 0, thoughts)
    }
}

private class GeminiStream(
    private val onText: (String) -> Unit,
    private val parseChunk: (String) -> ModelReply,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = mutableListOf<Part.ToolCall>()
    private var usage: Usage? = null
    private var finishReason: String? = null

    fun accept(data: String): Boolean {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("ストリーミング応答が正しいJSONではありません", e) }
        var usageInEvent = false
        usageOf(root["usageMetadata"])?.let { usage = it; usageInEvent = true }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            // finishReason の後に使用量だけのイベントが来る場合は、それを受け取るまで接続を閉じません。
            return finishReason != null && usageInEvent
        }
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { finishReason = it }
        val reply = parseChunk(data)
        var chunkCalls = 0
        reply.parts.forEach { part ->
            when (part) {
                is Part.Text -> { visible.append(part.text); onText(visible.toString()) }
                is Part.Thinking -> thinking.append(part.text)
                is Part.ToolCall -> {
                    calls += if (part.id == "gemini-$chunkCalls-${part.name}") part.copy(id = "gemini-${calls.size}-${part.name}") else part
                    chunkCalls++
                }
                else -> Unit
            }
        }
        // 終了理由と当該イベントの使用量が揃ったら終了し、それ以外は後続の使用量か EOF を待ちます。
        return finishReason != null && usageInEvent
    }

    fun finish(): ModelReply {
        // Gemini のストリームには個別の終了マークがありません。 finishReason を持つフレームが終了です。到達する前に接続が途中で切断されます。
        if (finishReason == null) throw ModelError.StreamCut()
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            addAll(calls)
        }
        val stop = when (finishReason) {
            "STOP" -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            "MAX_TOKENS" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}
