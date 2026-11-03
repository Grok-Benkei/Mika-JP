package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * サービスプロバイダーがリストしたモデル。機能フィールドが null の場合、インターフェイスが提供されていないことを意味し、呼び出し元は独自の初期値を使用します。ここでは推測しません。
 *
 * 一部のサービス プロバイダーは、1 つのキーのモデルに異なるインターフェイス プロトコルを使用します。プロトコルとアドレスはモデルごとにリスト（`api`テーブル）に記載されています。
 * [endpoint] はミカが言うものです。このモデルを呼び出すときは、それに応じてアダプターとアドレスを選択します。 null の場合は、サービス プロバイダーのインターフェイス タイプを選択します。
 * [unsupportedApis] は明確に記載されているプロトコル名ですが、Mika はそれを言うことができません。そのようなモデル呼び出しは失敗する必要があり、選択されません ([usable] を参照)。
 * [chat] はインターフェイスであり、対話モデルであるとは言えません (`type` はチャット/埋め込み/再ランク...、または出力モーダルにテキストがあるかどうか)。言われなければ無効です。
 */
data class ListedModel(
    val id: String,
    val displayName: String = "",
    val toolCalls: Boolean? = null,
    val vision: Boolean? = null,
    val contextTokens: Int? = null,
    val reasoning: Boolean? = null,
    val endpoint: ModelEndpoint? = null,
    val unsupportedApis: List<String> = emptyList(),
    val chat: Boolean? = null,
    /** サービスプロバイダーによって明確に与えられた思​​考装置。 null は不明を意味し、空のリストはオプションのギアがないことを意味します。サービスプロバイダーの順序を守ります。 */
    val reasoningEfforts: List<String>? = null,
    /**
     * サービスプロバイダーは、このモデルで受け入れられる思考方法を示します (人間リスト `capabilities.thinking.types` でサポートされているもの、
     * (`adaptive`、`enabled`、`disabled` など); null は言及されていないことを意味します。
     */
    val thinkingTypes: List<String>? = null,
    /**
     * インターフェイスは、このモデルが採用できるプロトコルを指定します (一部の集約サービスは、`/chat/completions` など、`supported_endpoints` によって提供されます)。
     * `/messages`); null は言われていないことを意味します。これらに含まれないインターフェイス タイプでの呼び出しは失敗する必要があります。[ModelProtocol.ofPath] を参照してください。 モデル リストの
     */
    val protocols: List<ModelProtocol>? = null,
) {
    val usable: Boolean get() = endpoint != null || unsupportedApis.isEmpty()
}

/** `api` は、認識されたインターフェイス プロトコルを表します。 [wire] はテーブル内のキー名です。 */
enum class ModelProtocol(val wire: String) {
    /** OpenAI Chat Completions。[OpenAiCompatModel] によって呼び出されます。 */
    ChatCompletions("chat-completions"),
    /** Anthropic メッセージ。[AnthropicModel] によって呼び出されます。 */
    Messages("messages"),
    /** GeminigenerateContent。[GeminiModel] によって呼び出されます。 */
    GenerateContent("generate-content"),
    /** OpenAI レスポンス。[ResponsesModel] によって呼び出されます。最下位: 同じモデルが最初の数個も書き込んでいるため、最初の数個はまだ使用されています。 */
    Responses("responses");

    companion object {
        fun of(wire: String?): ModelProtocol? = entries.firstOrNull { it.wire == wire }

        /**
         * は、エンドポイント パスによってプロトコルを識別します。一部の集約サービスは、`supported_endpoints` を使用して、各モデルがどのエンドポイントに到達できるかを決定します (`/chat/completions`、
         * `/messages`、`/responses`、`/v1beta/models/{模型}:generateContent`)、パスのみを書き込み、プロトコル名は書き込みません。認識されない場合は null です。
         *
         * は、Gemini が生成したエンドポイントである `…:generateContent` のみを認識します: ベア `/models` (または `/v1/models`、`/provider/v1/models`)
         * は、リストされているモデルのエンドポイントです。リストに掲載されているからといって、調整できるわけではありません。 「生成可能」と判定された場合は、試すべきではないモデルを一度試行してしまうことになるので、認識しないほうが良いでしょう。
         */
        fun ofPath(path: String?): ModelProtocol? {
            val p = path?.trim()?.trimEnd('/')?.lowercase() ?: return null
            return when {
                p.endsWith("/chat/completions") -> ChatCompletions
                p.endsWith("/messages") -> Messages
                p.endsWith("/responses") -> Responses
                p.endsWith(":generatecontent") -> GenerateContent
                else -> null
            }
        }
    }
}

/**
 * は、モデル: プロトコルとリストで指定されたアドレス (`…/anthropic/v1` など、このプロトコルのルートである [baseUrl]) によって個別に指定されるインターフェイスです。
 * および互換インターフェイスは、思考コンテンツをフィールドに配置します ([reasoningField]、`reasoning_content` など。null として言及されていません)。
 */
data class ModelEndpoint(val protocol: ModelProtocol, val baseUrl: String, val reasoningField: String? = null) {
    /**
     * アダプターに渡されるベース URL。アダプター独自のバージョン パス: [AnthropicModel] は `/v1/messages` に接続し、[GeminiModel] は接続します
     * `/v1beta/models/…` したがって、重複を避けるためにリスト アドレスの末尾にある段落を削除します。チャット完了は `/chat/completions` のみを受け入れます。
     * 応答は `/responses` にのみ接続され、そのまま使用されます。
     */
    val adapterBaseUrl: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            return when (protocol) {
                ModelProtocol.ChatCompletions, ModelProtocol.Responses -> url
                ModelProtocol.Messages -> url.removeSuffix("/v1")
                ModelProtocol.GenerateContent -> url.removeSuffix("/v1beta")
            }
        }
}

/** モデル一覧の取得が失敗した理由。[text] はユーザー向けの説明。 */
enum class ListFailure(val text: String) {
    BadUrl("APIのアドレスが正しくありません。http:// または https:// で始まる必要があります"),
    Auth("APIキーが無効か、権限がありません"),
    Quota("アカウントの利用上限に達しました"),
    RateLimited("リクエストが頻繁すぎます。しばらく待ってから再試行してください"),
    Network("このアドレスに接続できません。ネットワークとAPIアドレスを確認してください"),
    ProxyUnreachable("ネットワークプロキシに接続できないか、プロキシがこのリクエストを転送しませんでした。「設定 › ネットワークプロキシ」を確認してください"),
    ProxyAuth("ネットワークプロキシがアカウントまたはパスワードを拒否しました。「設定 › ネットワークプロキシ」を確認してください"),
    NotSupported("このサービスはモデル一覧を提供していません"),
    Server("サービスで一時的なエラーが発生しました。しばらく待ってから再試行してください"),
    BadResponse("返信の内容を解析できませんでした。APIアドレスとAPIの種類を確認してください"),
    ;

    companion object {
        /** リクエストは送信されませんでした: 正直に言うと、プロキシ セクションの問題があり、他のセクションは接続できません。 */
        fun of(e: Throwable): ListFailure = when ((e as? ModelError.Proxy)?.failure) {
            ProxyFailure.Unreachable -> ProxyUnreachable
            ProxyFailure.AuthFailed -> ProxyAuth
            null -> Network
        }
    }
}

sealed interface ModelListResult {
    data class Ok(val models: List<ListedModel>) : ModelListResult
    data class Failed(val reason: ListFailure, val detail: String = "") : ModelListResult {
        val message: String get() = if (detail.isEmpty()) reason.text else "${reason.text}($detail)"
    }
}

/**
 * 各社が公開している「リスト モデル」インターフェイスを使用して、利用可能なモデルを取得します。
 * - OpenAI 互換: `GET {base}/models` (ベアラー)。 ID のみが真実に返されます。 `context_length`では、
 *   、`architecture.input_modalities`、`supported_parameters` およびその他のフィールド (集約サービスの一部が提供します) のみを入力できます。
 *   は別の書き方も認識します: `capabilities.tools.supported`、`capabilities.reasoning.supported`、`limit.context`、
 *   `modalities.input` (`image/…` をお持ちの場合は画像を見ることができます)、およびプロトコルに従って指定されたアドレスを含む `api` テーブル ([ListedModel.endpoint] を参照)。
 *   は ChatGPT アカウントを使用して `models` テーブルをリストします。名前は `slug` にあり、表示名は `display_name` にあり、`visibility` のみが `list` として残され、サーバーの順序は維持されます。
 *   は `input_modalities`、`context_window`、`supported_reasoning_levels` の写真を提供しました。
 * - Anthropic:`GET {base}/v1/models`、`after_id` を押してページをめくってください。 `max_input_tokens` はコンテキストです。
 *   `capabilities.image_input` / `capabilities.thinking` は、写真を見て推理することです。ツール呼び出し情報を提供しません。
 *   思考装置は、`capabilities.effort` および `capabilities.thinking.types` に従って項目ごとに入力されます ([anthropicEfforts] を参照)。
 * - ジェミニ: `GET {base}/v1beta/models`、`pageToken` を押してページをめくってください。残っているのは`generateContent`だけです。
 *   `inputTokenLimit` はコンテキスト、`thinking` は推論です。ツールの呼び出しや画像の表示に関する情報は提供されず、思考装置も提供されません。 各メソッドの
 */
class ModelCatalog(private val http: OkHttpClient) {
    // ヘッダー: 各リクエストによって伝送されるヘッダー ([ClientHeaders] を参照)。 Key ヘッダーがここに追加されます。
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openAiCompat(baseUrl: String, apiKey: String?, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val url = endpoint(baseUrl, "/models") ?: return@run badUrl()
        val req = Request.Builder().url(url).get().withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }.build()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        ModelListResult.Ok(data.mapNotNull { (it as? JsonObject)?.let(::openAiModel) }.distinctBy { it.id })
    }

    suspend fun anthropic(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("limit", "1000")
                .apply { if (after != null) addQueryParameter("after_id", after) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers)
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["data"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
            all += data.mapNotNull { (it as? JsonObject)?.let(::anthropicModel) }
            after = root.str("last_id")
            if (root.bool("has_more") != true || after == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun gemini(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1beta/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("pageSize", "1000")
                .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers).header("x-goog-api-key", apiKey).build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["models"] as? JsonArray ?: JsonArray(emptyList())
            all += data.mapNotNull { (it as? JsonObject)?.let(::geminiModel) }
            token = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    private fun openAiModel(o: JsonObject): ListedModel? {
        // ChatGPT アカウント列モデル (`models` テーブル) を使用する場合、名前は `slug` にあり、表示名は `display_name` にあり、リストには `visibility` のみが残ります。
        o.str("visibility")?.let { if (it != "list") return null }
        val id = (o.str("id") ?: o.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val arch = o["architecture"] as? JsonObject
        val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val modality = arch?.str("modality")?.substringBefore("->")
        val outputs = ((arch?.get("output_modalities") ?: o.obj("modalities")?.get("output")) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: arch?.str("modality")?.takeIf { "->" in it }?.substringAfter("->")?.split('+')
        val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val caps = o["capabilities"] as? JsonObject
        val modalIn = ((o.obj("modalities")?.get("input") ?: o["input_modalities"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val reasoningLevels = (o["supported_reasoning_levels"] ?: caps?.obj("reasoning")?.get("effort")) as? JsonArray
        val efforts = reasoningLevels?.mapNotNull { level ->
            (((level as? JsonObject)?.get("effort") ?: level) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()
        val api = o.obj("api")
        // 一部の集約サービスは、`api` テーブルを提供しません。代わりに、`supported_endpoints` を使用してください。このモデルの作成にはどのエンドポイントを使用できますか?
        val protocols = (o["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ModelProtocol::ofPath) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        // モデルは複数のプロトコルを記述することができます: チャット完了、メッセージ、generateContent、および応答の順序で最初のものを取り上げます。
        val endpoint = api?.let { a ->
            ModelProtocol.entries.firstNotNullOfOrNull { p ->
                val e = a.obj(p.wire) ?: return@firstNotNullOfOrNull null
                val url = e.str("base_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                ModelEndpoint(p, url, e.obj("compatibility")?.str("reasoning_field"))
            }
        }
        return ListedModel(
            id = id,
            displayName = (o.str("name") ?: o.str("display_name")).orEmpty(),
            toolCalls = params?.let { "tools" in it } ?: caps?.obj("tools")?.bool("supported")
                ?: caps?.bool("function_calling") ?: caps?.bool("tool_calls"),
            vision = inputs?.let { "image" in it } ?: modalIn?.let { m -> m.any { it == "image" || it.startsWith("image/") } }
                ?: modality?.let { "image" in it } ?: o.bool("supports_image_in") ?: caps?.bool("vision"),
            contextTokens = o.int("context_length") ?: o.int("context_window") ?: o.int("max_context_length")
                ?: o.obj("limit")?.int("context"),
            reasoning = params?.let { "reasoning" in it || "include_reasoning" in it } ?: caps?.obj("reasoning")?.bool("supported")
                ?: o.bool("supports_reasoning") ?: reasoningLevels?.let { it.isNotEmpty() },
            endpoint = endpoint,
            reasoningEfforts = efforts,
            unsupportedApis = if (endpoint == null) api?.keys?.toList().orEmpty() else emptyList(),
            protocols = protocols,
            chat = when (o.str("type")?.lowercase()) {
                in CHAT_TYPES -> true
                in NON_CHAT_TYPES -> false
                // 画像と音声のみを生成するものは対話モデルではありません。テキストを生成するものは個別に記録されません。
                else -> outputs?.takeIf { "text" !in it }?.let { false }
            },
        )
    }

    private fun anthropicModel(o: JsonObject): ListedModel? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = o["capabilities"] as? JsonObject
        val thinking = caps?.obj("thinking")
        val types = thinking?.obj("types")?.supportedKeys()
        return ListedModel(
            id = id,
            displayName = o.str("display_name").orEmpty(),
            vision = (caps?.get("image_input") as? JsonObject)?.bool("supported"),
            contextTokens = o.int("max_input_tokens"),
            reasoning = thinking?.bool("supported"),
            reasoningEfforts = types?.let { anthropicEfforts(caps?.obj("effort"), it) },
            thinkingTypes = types,
        )
    }

    /**
     * 人間モデル用のオプションの思考ギア: 適応的思考を受け入れる場合にのみ与えられます (`adaptive`、ギアは `output_config.effort`)。
     * ギアには、`capabilities.effort` でサポートされているレベルがマークされています。思考をオフにして (`disabled`)、ギア `none` を前に追加することもできます。
     * は予算重視（`enabled`+`budget_tokens`）またはグレード問わず受け付けており、オプショングレードはございません。
     */
    private fun anthropicEfforts(effort: JsonObject?, types: List<String>): List<String> {
        val levels = effort?.takeIf { it.bool("supported") == true }?.supportedKeys().orEmpty()
            .sortedBy { EFFORT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: EFFORT_ORDER.size }
        if ("adaptive" !in types || levels.isEmpty()) return emptyList()
        return listOfNotNull("none".takeIf { "disabled" in types }) + levels
    }

    /** `{"a":{"supported":true},"b":{"supported":false},"supported":true}` には、サポートされるサブ項目 `[a]` がマークされています。 */
    private fun JsonObject.supportedKeys(): List<String> = keys.filter { (this[it] as? JsonObject)?.bool("supported") == true }

    private fun geminiModel(o: JsonObject): ListedModel? {
        val id = o.str("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "generateContent" !in methods) return null
        return ListedModel(
            id = id,
            displayName = o.str("displayName").orEmpty(),
            contextTokens = o.int("inputTokenLimit"),
            reasoning = o.bool("thinking"),
        )
    }

    private class Failure(val result: ModelListResult.Failed) : Exception(result.message)

    private fun Throwable.asFailure(): ModelListResult.Failed =
        (this as? Failure)?.result ?: ModelListResult.Failed(ListFailure.Network)

    private suspend fun fetch(req: Request): Result<JsonObject> {
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
        }
        if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
        val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) { null }
            ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
        return Result.success(root)
    }

    private fun failureFor(status: Int, body: String): ModelListResult.Failed = when {
        status == 401 || status == 403 -> ModelListResult.Failed(ListFailure.Auth)
        // Gemini は無効なキーに対して 400 INVALID_ARGUMENT を返します。理由はコンテンツに記載されています
        status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> ModelListResult.Failed(ListFailure.Auth)
        status == 402 || body.contains("insufficient_quota") -> ModelListResult.Failed(ListFailure.Quota)
        status == 429 -> ModelListResult.Failed(ListFailure.RateLimited)
        status == 404 || status == 405 || status == 501 -> ModelListResult.Failed(ListFailure.NotSupported)
        status in 500..599 -> ModelListResult.Failed(ListFailure.Server, "HTTP $status")
        else -> ModelListResult.Failed(ListFailure.BadResponse, "HTTP $status")
    }

    private fun badUrl() = ModelListResult.Failed(ListFailure.BadUrl)

    private fun endpoint(baseUrl: String, path: String) = (baseUrl.trim().trimEnd('/') + path).toHttpUrlOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private companion object {
        /** ページめくりの上限: サーバーが常に次のページを表示しないようにします。 */
        const val MAX_PAGES = 10
        /** Anthropic の `capabilities.effort` はアルファベット順に並べられています。インターフェースは昇順に配置されており、認識できないインターフェースが最後になります。 */
        val EFFORT_ORDER = listOf("low", "medium", "high", "xhigh", "max")
        /** リスト内の項目 `type` の書き方: これは対話モデルですが、そうではありません (ベクトル、再配置、描画、スピーチ、レビュー)。 */
        val CHAT_TYPES = setOf("chat", "llm", "language", "text", "vlm", "code")
        val NON_CHAT_TYPES = setOf(
            "embedding", "embeddings", "rerank", "reranker", "image", "images", "video", "audio", "tts", "stt", "asr",
            "speech", "transcription", "moderation",
        )
    }
}
