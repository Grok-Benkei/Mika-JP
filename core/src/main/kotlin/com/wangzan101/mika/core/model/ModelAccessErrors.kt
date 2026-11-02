package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 全アダプタ共通：HTTP 400 / 403 / 404のエラー内容は「このモデルは無効です」となっており、キーやリクエスト自体が間違っている場合ではなく、どれであるかを特定します。
 * エラー報告の形式や表現は各社で異なります。ここでは、コンテンツ内にこれらのタイプのステートメントがあるかどうかのみを確認します (大文字と小文字は関係ありません)。
 * - ツールの呼び出しはサポートされていません (「ツールをサポートしていません」「関数呼び出しはサポートされていません」「ツールをサポートしていません」...) → [ModelError.ToolsUnsupported];
 * - このモデルは現在のインターフェイスを使用できません (サービスプロバイダーは、「モデル...は /messages 経由で呼び出す必要があります」など、別のインターフェイス形状のセットを必要とします)
 *   "unsupported_model"...、[ENDPOINT_MISMATCH])→ [ModelError.ModelNotAvailable] を参照、サーバーの元の言葉を使用します。
 *   モデルまたはインターフェイスの種類を変更するだけです。 「このアカウントは使用できません」と同じように扱い、次のアカウントを試してください。
 * - このアカウントはこのモデルを使用できません (「アクティブ化されていません」「このモデルへのアクセス権限がありません」「モデルにアクセスできません」
 *   「Model.AccessDenied」「アクティブ化されていません」「許可がありません」...)、またはこのモデルはオフラインになっています (「もう利用できません」「廃止されました」)
 *   "モデル... は非推奨です" "オフラインになっています"...)→ [ModelError.ModelNotAvailable]、サーバーの元の言葉を持ち込んでください。
 * - そのようなモデルはありません (「model_not_found」「モデルが見つかりません」「モデルが存在しません」「API バージョンが見つかりません」「モデルが存在しません」...)
 *   → [ModelError.ModelNotFound]、元の単語を JSON ([jsonDetail]) で持ってきます。
 * 404 最初の 2 つのカテゴリが指定されていない場合でも、モデルが見つからないため、呼び出し元は引き続きそれを処理します ([jsonDetail] ももたらします)。認識されない場合は null になり、呼び出し元はそれをステータス コードとして処理します。 401 は常に重要な問題なので、見ないでください。
 *
 * HTTP 429 は通常、制限されているか、クォータが使い果たされており、呼び出し元によって処理されます。このモデルのこのアカウントのクォータがゼロ (「limit: 0」) であると記載されているだけです。
 * 「無料クォータ層がありません」...、[QUOTA_ZERO] を参照)、このモデルは使用できないと見なされます。 [ModelError.ModelNotAvailable]: 長時間待っても改善されません。別のモデルに変更するだけです。 モデル
 */
object ModelAccessErrors {
    private val json = Json { ignoreUnknownKeys = true }

    private val TOOLS_UNSUPPORTED = listOf(
        "does not support tools", "does not support tool", "doesn't support tools", "tools is not supported", "tools are not supported",
        "tool use is not supported", "tool calling is not supported", "function calling is not supported",
        "does not support function calling", "不支持工具", "不支持函数调用", "不支持 function call",
    )

    private val NOT_AVAILABLE = listOf(
        "not activated", "not been activated", "model.accessdenied", "model access denied", "accessdenied.unpurchased",
        "not authorized to access this model", "not authorized to use this model", "not authorized to access the model",
        "do not have access to the model", "does not have access to the model", "does not have access to model",
        "do not have access to model", "no access to model", "no permission to access the model", "no permission to use the model",
        "model_not_available", "model is not available", "model not available", "not available for your account",
        "未开通", "没有开通", "尚未开通", "无权限", "没有权限使用", "无权访问", "无权使用",
        // はオフラインになりました
        "no longer available", "has been retired", "has been discontinued", "has been shut down",
        "已下线", "已停用", "已下架", "已停止服务",
    )

    /**
     * は「このモデルはこのインターフェイスを使用できません」と言いました。サービス プロバイダーはインターフェイスの形状に従ってモデルを分けています (たとえば、特定のシリーズのモデルは `/messages` にのみハング可能です。
     * OpenAI 互換パスで呼び出すと、常に 400「/provider/v1/messages 経由で呼び出す必要があります」を取得し、インターフェイス タイプを変更します。
     * または以下のモデルに変更してください。これはキー、アドレス、ネットワークの問題ではありません。このモデルが機能しない場合は、次のモデルを試してください。
     * は、エラー レポートがモデルを参照する場合にのみカウントされます ([aboutTheModel] を参照)。「お使いの API キーはこのエンドポイントではサポートされていません」はキーを指します。
     * は、ステータス コードに従って処理するために呼び出し元に渡されます (403 はキーの問題であり、保存全体はまだブロックされています)。
     */
    private val ENDPOINT_MISMATCH = listOf(
        "unsupported_model",
        "must be called via",
        "only supported in v1/",
        "not supported for this endpoint",
        "unsupported for this endpoint",
    )

    /** は、「モデル... は非推奨になっています/オフラインです」と言いました。非推奨と言うだけで、特定のパラメータが非推奨になったことを意味する場合があり、モデルと組み合わせて言及する必要があります。 */
    private val MODEL_RETIRED = Regex("""\bmodels?\b[^;,\n]{0,80}?\b(?:is|has been|was)\s+(?:deprecated|retired)|\bdeprecated\s+models?\b""")

    /** 429 は、このモデルのアカウントの割り当てがゼロであることを示しています: `limit: 0`、`"quotaValue": "0"` はカウントされるか、無料の割り当てがないことが明確に述べられています。 */
    private val QUOTA_ZERO = Regex(
        """\blimit\s*:\s*0(?![\d.])|"quotavalue"\s*:\s*"?0"?\s*[,}]|(?:doesn't|does not|don't|do not) have (?:a |any )?free quota tier|has no free quota tier""",
    )

    private val NOT_FOUND = listOf(
        "model_not_found", "model not found", "model does not exist", "model not exist", "no such model", "unknown model",
        "not found for api version", "模型不存在", "找不到模型",
    )

    /** [model] は、要求されたモデル名です。 */
    fun of(status: Int, body: String, model: String): ModelError? {
        val lower = body.lowercase()
        if (status == 429) return if (QUOTA_ZERO.containsMatchIn(lower)) ModelError.ModelNotAvailable(model, detail(body)) else null
        if (status != 400 && status != 403 && status != 404) return null
        return when {
            TOOLS_UNSUPPORTED.any { it in lower } -> ModelError.ToolsUnsupported(model)
            ENDPOINT_MISMATCH.any { it in lower } && aboutTheModel(lower, body, model) -> ModelError.ModelNotAvailable(model, detail(body))
            NOT_AVAILABLE.any { it in lower } || MODEL_RETIRED.containsMatchIn(lower) -> ModelError.ModelNotAvailable(model, detail(body))
            status != 404 && NOT_FOUND.any { it in lower } -> ModelError.ModelNotFound(model, jsonDetail(body))
            else -> null
        }
    }

    /** エラーはモデル自体を指します。モデル (`unsupported_model`、`"param":"model"` を含む) に言及するか、要求されたモデル名を元の単語で直接クリックしてください。 */
    private fun aboutTheModel(lower: String, body: String, model: String): Boolean =
        "model" in lower || (model.isNotBlank() && model.lowercase() in (said(body) ?: body).lowercase())

    /** サーバーからの元の単語: JSON の ([said]) は、JSON または段落全体です。長すぎる場合は切り捨てられます ([clip])。 */
    internal fun detail(body: String): String = clip(said(body) ?: body)

    /** は [detail] と同じですが、JSON: の元の単語が JSON ではない限り (アドレスが正しくない場合にリバース プロキシによって返される HTML ページと空のコンテンツ) は空になります。 */
    internal fun jsonDetail(body: String): String = said(body)?.let(::clip).orEmpty()

    /** は、先頭と末尾の空白を削除します。 [DETAIL_MAX] 文字を超える場合は切り捨てられ、末尾にさらにあることを示すために「...」が末尾に追加されます。 */
    internal fun clip(text: String): String =
        text.trim().let { if (it.length <= DETAIL_MAX) it else it.take(DETAIL_MAX).trimEnd() + "…" }

    /** `error.message`、`error` (文字列の場合)、`message`、`msg`、`detail`; JSON オブジェクト (一部のサービスは配列を返し、最初の配列を取得します) は null ではありません。 */
    private fun said(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val root = parsed as? JsonObject ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject
        val err = root?.get("error")
        return (err as? JsonObject)?.str("message") ?: (err as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: root?.str("message") ?: root?.str("msg") ?: root?.str("detail")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val DETAIL_MAX = 160
}
