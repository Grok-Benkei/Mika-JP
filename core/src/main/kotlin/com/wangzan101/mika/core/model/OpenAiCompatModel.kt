package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI Chat Completions 互換エンドポイント (DeepSeek/Qwen/Kimi/GLM/OpenRouter/ローカル サービスなど)。差分は[capabilities]にのみ配置されます。
 *
 * の思考コンテンツは、さまざまな企業によってさまざまな場所に配置されます。`reasoning_content`、`reasoning`、または `content` はブロック配列であり、思考は `thinking` ブロック内にあります。
 * 受信した思考は、その発信元のアドレスと場所 ([ReasoningVia]、[Part.Thinking.opaque] が存在します) を記録し、ツールを使用してリクエストを元の場所に戻します。
 * 一部のサービス プロバイダーでは、ツールが呼び出しの前後で考えてから送り返すことが必要です。そうでない場合は拒否されます。このアドレスから受け取った応答のみが返送され、他のソースから受け取った考えは送信されません。
 * (このフィールドを認識しないサービス プロバイダーはこのフィールドを受信できません)。 [reasoningField] は、モデル リストで指定された考慮事項フィールド (`reasoning_content` または
 * `reasoning`): 明確に書き込まれた出所不明の思考(旧バージョンで保存された履歴)もこれに準じて返されます。
 * は、思考強度が選択されている場合に個別に送信されます。 `reasoning_effort`;サービスプロバイダーがリストにギアを書き込まない場合、値はここで受信されません。
 */
class OpenAiCompatModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    reasoningField: String? = null,
    /** 各リクエストにはヘッダー (クライアント ID、サービス プロバイダーが必要とするセッション番号など。[ClientHeaders] を参照) が含まれます。 Key ヘッダーはアダプター自体によって追加されます。 */
    private val headers: Map<String, String> = emptyMap(),
    /** ユーザーが選択した思考の強度 (`reasoning_effort`)。 nullは送信されません。 */
    private val reasoningEffort: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val declaredVia = ReasoningVia.ofField(reasoningField)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val MARKABLE_ROLES = setOf("system", "user", "tool")
        val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        /** SSE の最後の部分: 受け取るまでは完成しません。 */
        const val STREAM_DONE = "[DONE]"
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val response = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        val (status, retryAfter, text) = response
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        // 一部の互換性のあるエンドポイントは OpenAI のデフォルト値に準拠していません。ストリーミングが有効になっていない場合でも、依然として SSE に戻ります (ローカルおよびローカル エリア ネットワーク上の自己構築サービスに共通)。流れに従って読んでから綴ってください。 `data:` を不正な JSON とみなさないでください。
        if (!looksLikeEvents(text)) return parse(text)
        val stream = OpenAiStream({}, ::parseUsage, ::thinking)
        response.replay(eventsOf(text), stream::accept)
        return stream.finish()
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = OpenAiStream(onText, ::parseUsage, ::thinking)
        var response = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (response.status == 400 && response.body.contains("stream_options")) {
            // 一部の互換性のあるエンドポイントはストリーミングできますが、使用方法のオプションはありません。
            stream = OpenAiStream(onText, ::parseUsage, ::thinking)
            response = HttpSupport.stream(http, request(system, messages, tools, stream = true, includeUsage = false), stream::accept)
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) {
            // ストリーミングをサポートしていない互換性のあるエンドポイントでも、元のインターフェイスに従ってタスクを完了できます。
            if (status == 400 && text.contains("stream", ignoreCase = true)) return complete(system, messages, tools).also {
                it.parts.filterIsInstance<Part.Text>().joinToString("") { part -> part.text }.takeIf(String::isNotEmpty)?.let(onText)
            }
            throw mapError(status, retryAfter, text)
        }
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, includeUsage: Boolean = true): Request {
        val payload = buildJsonObject {
            put("model", model)
            reasoningEffort?.let { put("reasoning_effort", it) }
            // 思考は、ツールを使用したリクエストでのみ返されます (ツールが段階的に呼び出されるときに思考を続ける必要があります)。ツールを使用しないバイパスコールは不要です
            val replayReasoning = tools.isNotEmpty()
            val wire = listOf(buildJsonObject { put("role", "system"); put("content", system) }) +
                messages.flatMap { serialize(it, replayReasoning) }
            put("messages", JsonArray(if (capabilities.promptCaching == PromptCaching.Explicit) withCacheMarks(wire) else wire))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            } else {
                // はストリーミングしないことを指定します。この項目が省略された場合、一部の互換性のあるエンドポイントはデフォルトで SSE によって返されます。
                put("stream", false)
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .postInference(InferenceProtocol.Chat, payload)
            .build()
    }

    /**
     * 明示的キャッシュ: システム プロンプト ワードにマークを付けて (毎回同じ)、次にマークできる最後のメッセージにマークを付けます (この時点までの会話全体をキャッシュし、
     * は、次のリクエストの先頭がそれと同じ場合にキャッシュからフェッチされます)。サーバーはマークから同じ先頭まで遡るため、以前のメッセージを上書きすることはできません。
     * コンテンツが文字列の場合は、テキスト ブロックに置き換えてマークします。アシスタント メッセージ (ツール呼び出しのみの場合もあります) はマークされません。
     */
    private fun withCacheMarks(wire: List<JsonObject>): List<JsonObject> {
        val last = wire.indexOfLast { (it["role"] as? JsonPrimitive)?.contentOrNull in MARKABLE_ROLES && it["content"] !is JsonNull }
        return wire.mapIndexed { i, m -> if (i == 0 || i == last) marked(m) else m }
    }

    private fun marked(m: JsonObject): JsonObject {
        val blocks = when (val c = m["content"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.content) })
            is JsonArray -> c.map { it.jsonObject }
            else -> return m
        }
        if (blocks.isEmpty()) return m
        val withMark = blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to EPHEMERAL))
        return JsonObject(m + ("content" to JsonArray(withMark)))
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.openAiError(status, retryAfter, body, model)

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
                throw IllegalArgumentException("ツール ${t.name} のパラメータ schema が正しいJSONではありません", e)
            })
        }
    }

    /** 内部メッセージは複数のオンライン メッセージに展開できます (ツールの結果はそれぞれ 1 つを占め、画像にはユーザー メッセージのみを含めることができます)。 */
    private fun serialize(m: Message, replayReasoning: Boolean): List<JsonObject> {
        if (m.role == Role.Assistant) return listOf(assistantJson(m, replayReasoning))
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content)
            }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) {
            val role = if (m.role == Role.System) "system" else "user"
            val hasImage = rest.any { it is Part.Image } && capabilities.vision
            out += buildJsonObject {
                put("role", role)
                if (!hasImage) {
                    put("content", rest.joinToString("\n") { textOf(it) })
                } else {
                    putJsonArray("content") {
                        rest.forEach { p ->
                            add(buildJsonObject {
                                if (p is Part.Image) {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${p.mime};base64,${p.pngBase64}") }
                                } else {
                                    put("type", "text"); put("text", textOf(p))
                                }
                            })
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textOf(p: Part): String = when (p) {
        is Part.Text -> p.text
        is Part.Image -> "[screenshot omitted: the current model cannot see images]"
        else -> ""
    }

    /** は、このアドレスと受信場所を書き留めて、再生中に認識できるようにするという考えを受け取りました。 */
    private fun thinking(text: String, via: ReasoningVia) = Part.Thinking(text, via.tag(endpoint))

    /** このアシスタント メッセージは、考えを返してこのアドレスに戻すことができます。ここから受信した場合は、受信した位置を押します。ソースが不明な場合は、リストで指定されたフィールドを押します。どれも null ではありません。 */
    private fun replayable(m: Message): Pair<String, ReasoningVia>? {
        val parts = m.parts.filterIsInstance<Part.Thinking>()
        val own = parts.mapNotNull { p -> ReasoningVia.of(p.opaque, endpoint)?.let { p.text to it } }
        if (own.isNotEmpty()) return own.joinToString("") { it.first } to own.first().second
        val declared = declaredVia ?: return null
        val untagged = parts.filter { it.opaque == null }.joinToString("") { it.text }
        return if (untagged.isEmpty()) null else untagged to declared
    }

    private fun assistantJson(m: Message, replayReasoning: Boolean): JsonObject {
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val replay = if (replayReasoning) replayable(m) else null
        return buildJsonObject {
            put("role", "assistant")
            when {
                // ブロックに分割されているものは、前に思考ブロック、後ろにテキストブロックをそのまま元に戻してください。
                replay?.second == ReasoningVia.ThinkingChunks -> putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        putJsonArray("thinking") { addJsonObject { put("type", "text"); put("text", replay.first) } }
                    }
                    if (text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", text) }
                }
                text.isEmpty() && calls.isNotEmpty() -> put("content", JsonNull)
                else -> put("content", text)
            }
            replay?.second?.field?.let { put(it, replay.first) }
            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        putJsonObject("function") { put("name", c.name); put("arguments", c.argsJson) }
                    })
                }
            }
        }
    }

    /** 返信全体の SSE (`data: …` を 1 つずつ) または JSON です。 */
    private fun looksLikeEvents(body: String): Boolean {
        val head = body.trimStart()
        return head.startsWith("data:") || head.startsWith("event:")
    }

    /** SSE 応答全体を 1 つのイベントに分割します。コンテンツは収集されており、不足している終了マークがある場合は追加して通常どおり扱います。 */
    private fun eventsOf(body: String): List<String> {
        val out = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun dispatch() {
            if (data.isNotEmpty()) {
                out += data.joinToString("\n")
                data.clear()
            }
        }
        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        dispatch()
        if (out.lastOrNull() != STREAM_DONE) out += STREAM_DONE
        return out
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("choicesがありません")
            val msg = choice["message"]?.jsonObject ?: throw ModelError.BadResponse("messageがありません")
            val parts = mutableListOf<Part>()
            ReasoningVia.fields.firstNotNullOfOrNull { via ->
                (msg[via.field!!] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { it to via }
            }?.let { (t, via) -> parts += thinking(t, via) }
            when (val content = msg["content"]) {
                is JsonArray -> {
                    val chunks = ContentChunks.of(content)
                    if (chunks.thinking.isNotEmpty() && parts.isEmpty()) parts += thinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                    if (chunks.text.isNotEmpty()) parts += Part.Text(chunks.text)
                }
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                else -> {}
            }
            (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                val c = el.jsonObject
                val fn = c["function"]!!.jsonObject
                parts += Part.ToolCall(
                    c["id"]!!.jsonPrimitive.content,
                    fn["name"]!!.jsonPrimitive.content,
                    // 一部のアダプターはパラメーターなしでツールに空の文字列を与えます。それを `{}` として扱います。そうしないと、呼び出し元は JSON に従って解析できません。
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
            val stop = when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                "stop" -> StopReason.EndTurn
                "tool_calls", "function_call" -> StopReason.ToolUse
                "length" -> StopReason.MaxTokens
                else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.Other
            }
            // 一部のモデルはツール呼び出しマークをテキストに直接吐き出します。最初に呼び出しマークを復元し、復元できない場合は剥がします。文字化けしたコードをインターフェイスに残さないでください (LeakedToolMarkup を参照)
            return LeakedToolMarkup.repair(ModelReply(parts, stop, parseUsage(root["usage"])))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        // 互換インターフェイスには 2 つのヒット フィールド名があります。値はフィールドが存在するかどうかによって決まります。
        val hit = int(u, "prompt_cache_hit_tokens")
        val miss = int(u, "prompt_cache_miss_tokens")
        val inT = int(u, "prompt_tokens") ?: if (hit != null && miss != null) hit + miss else return null
        val outT = int(u, "completion_tokens") ?: 0
        val cached = maxOf(int(u["prompt_tokens_details"] as? JsonObject, "cached_tokens") ?: 0, hit ?: 0).coerceAtMost(inT)
        // completed_tokens には思考が含まれます。一部のサービスプロバイダーは、complete_tokens_details.reasoning_tokens で個別に報告します。
        val reasoning = (int(u["completion_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, outT)
        return Usage(inT, outT, cached, reasoning)
    }
}

/** チャット補完の差分により、同じツールの名前とパラメーターが複数のフレームに分割されることがあります。 */
private class OpenAiStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
    private val thinkingOf: (String, ReasoningVia) -> Part.Thinking,
) {
    private data class Call(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder())

    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private var thinkingVia: ReasoningVia? = null
    private val calls = sortedMapOf<Int, Call>()
    private var usage: Usage? = null
    private var reason: String? = null
    private var done = false
    private var chunks = 0

    fun accept(data: String) {
        if (data == "[DONE]") { done = true; return }
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("ストリーミング応答が正しいJSONではありません", e) }
        if (root["error"] != null) throw ModelError.BadResponse(root["error"].toString().take(200))
        chunks++
        usageOf(root["usage"])?.let { usage = it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { reason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        ReasoningVia.fields.forEach { via ->
            (delta[via.field!!] as? JsonPrimitive)?.contentOrNull?.let { appendThinking(it, via) }
        }
        when (val content = delta["content"]) {
            is JsonArray -> {
                val chunks = ContentChunks.of(content)
                if (chunks.thinking.isNotEmpty()) appendThinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                appendVisible(chunks.text)
            }
            is JsonPrimitive -> content.contentOrNull?.let(::appendVisible)
            else -> {}
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val call = calls.getOrPut(index, ::Call)
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id.append(it) }
            val fn = obj["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.args.append(it) }
        }
    }

    /** 返信を考えるときは、最初に受信した場所のみが記録されます（同じ会社の場合、場所は変わりません。両方のフィールドが指定された場合、コピーは 1 つだけ記録されます）。 */
    private fun appendThinking(t: String, via: ReasoningVia) {
        if (t.isEmpty()) return
        if (thinkingVia == null) thinkingVia = via
        if (via == thinkingVia) thinking.append(t)
    }

    private fun appendVisible(t: String) {
        if (t.isEmpty()) return
        visible.append(t)
        // また、最初にプレビューを剥がします。モデルがツール呼び出しマークをテキストに吐き出すときに、文字化けしたコード フローがユーザーに見られないようにしてください。
        onText(LeakedToolMarkup.stripMarkup(visible.toString()))
    }

    fun finish(): ModelReply {
        // は、応答が完了している場合でも、finish_reason を受信します。一部の互換性のあるエンドポイントは、[DONE] を送信しません。どちらも不可能です。途中で接続が切れてしまいました。
        if (!done && reason == null) throw ModelError.StreamCut()
        if (chunks == 0) throw ModelError.BadResponse("ストリーミング応答に内容がありません")
        val parts = buildList<Part> {
            thinkingVia?.takeIf { thinking.isNotEmpty() }?.let { add(thinkingOf(thinking.toString(), it)) }
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            calls.values.forEach { c ->
                if (c.name.isEmpty() || c.id.isEmpty()) throw ModelError.BadResponse("ツール呼び出しのストリームに名前またはIDがありません")
                add(Part.ToolCall(c.id.toString(), c.name.toString(), c.args.toString().ifEmpty { "{}" }))
            }
        }
        val stop = when (reason) {
            "stop" -> StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Other
        }
        return LeakedToolMarkup.repair(ModelReply(parts, stop, usage))
    }
}

/**
 * 互換インターフェイスでは、次のような考え方が採用されています。[field] はアシスタント メッセージのフィールド名です。 [ThinkingChunks] はブロック配列、`content` はブロック配列で、思考は `thinking` ブロック内にあります。
 * は [Part.Thinking.opaque] に `{"endpoint":…,"via":…}` として記録されます。 メッセージフィールドには
 */
internal enum class ReasoningVia(val wire: String, val field: String?) {
    ReasoningContent("reasoning_content", "reasoning_content"),
    Reasoning("reasoning", "reasoning"),
    ThinkingChunks("thinking_chunks", null);

    fun tag(endpoint: String): String = buildJsonObject { put("endpoint", endpoint); put("via", wire) }.toString()

    companion object {
        /** が配置され、この順序で認識されます (両方が指定された場合は、`reasoning_content` が取得されます)。 */
        val fields = listOf(ReasoningContent, Reasoning)

        fun ofField(name: String?): ReasoningVia? = fields.firstOrNull { it.field == name }

        /** [opaque] は、[endpoint] からの反射を受信したときにその位置を示します。他の場所から来たもので認識できない場合は、null になります。 */
        fun of(opaque: String?, endpoint: String): ReasoningVia? {
            val o = opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
            val via = (o["via"] as? JsonPrimitive)?.contentOrNull
            return entries.firstOrNull { it.wire == via }
        }
    }
}

/** `content` がブロック配列の場合: `text` ブロックは答えに結合され、`thinking` ブロック (`text` ブロックが内部にある) は思考に結合されます。他のブロックが認識されません。 */
private class ContentChunks(val text: String, val thinking: String) {
    companion object {
        fun of(content: JsonArray): ContentChunks {
            val text = StringBuilder()
            val thinking = StringBuilder()
            content.forEach { el ->
                val c = el as? JsonObject ?: return@forEach
                when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
                    "thinking" -> when (val t = c["thinking"]) {
                        is JsonArray -> t.forEach { inner ->
                            ((inner as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(thinking::append)
                        }
                        is JsonPrimitive -> t.contentOrNull?.let(thinking::append)
                        else -> {}
                    }
                }
            }
            return ContentChunks(text.toString(), thinking.toString())
        }
    }
}
