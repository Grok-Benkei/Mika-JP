package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAccessErrorsTest {
    private fun notAvailable(status: Int, body: String) = ModelAccessErrors.of(status, body, "m") as ModelError.ModelNotAvailable

    /** 「このアカウントではこのモデルを使用できません」というみんなの発言: ModelNotAvailable として認識し、サーバーのオリジナルの言葉を持ってきます。 */
    @Test fun accountCannotUseTheModel() {
        val notActivated = """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"x"}"""
        val e = notAvailable(400, notActivated)
        assertEquals("m", e.model)
        assertTrue(e.detail, e.detail.startsWith("The product is not activated"))
        assertEquals("Model access denied.", notAvailable(403, """{"error":{"code":"Model.AccessDenied","message":"Model access denied."}}""").detail)
        notAvailable(403, """{"error":{"message":"You are not authorized to access this model","type":"permission_error"}}""")
        notAvailable(403, """{"error":{"message":"Project does not have access to model gpt-x","code":"model_not_found"}}""")
        notAvailable(400, """{"error":{"code":"1220","message":"您当前使用该 API 的权限未开通"}}""")
        notAvailable(403, """{"error":{"message":"无权限访问该模型"}}""")
        // 404 もこう言うかもしれません
        notAvailable(404, """{"error":{"message":"Model not activated for this account"}}""")
        assertEquals("plain text not activated", notAvailable(400, "plain text not activated").detail)
    }

    @Test fun modelDoesNotSupportTools() {
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"registry.ollama.ai/library/gemma:2b does not support tools"}}""", "gemma") is ModelError.ToolsUnsupported)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"Function calling is not supported for this model"}}""", "x") is ModelError.ToolsUnsupported)
    }

    /** 400 / 403 は、そのようなモデルは存在しないと述べています。モデルが見つからないと考えられ、元の単語が提供されます。 404 が他に何も言わない場合、呼び出し側は通常どおり処理します。 */
    @Test fun modelNotFoundInsideOtherStatuses() {
        val nf = ModelAccessErrors.of(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""", "m") as ModelError.ModelNotFound
        assertEquals("m", nf.model)
        assertEquals("Model Not Exist", nf.detail)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"code":"model_not_found"}}""", "m") is ModelError.ModelNotFound)
        assertNull(ModelAccessErrors.of(404, """{"error":{"code":"model_not_found","message":"The model `x` does not exist or you do not have access to it."}}""", "m"))
    }

    /** モデルが見つからない場合は、JSON の元のテキストのみが必要です。アドレスが間違っている場合、リバース プロキシによって返される HTML ページと空のコンテンツは元のテキストではありません。 */
    @Test fun jsonDetailTakesOnlyWhatJsonSays() {
        assertEquals("none of group/rpa's models is ready", ModelAccessErrors.jsonDetail("""{"error":{"type":"not_found_error","message":"none of group/rpa's models is ready"}}"""))
        assertEquals("no route", ModelAccessErrors.jsonDetail("""{"error":"no route"}"""))
        assertEquals("Not Found", ModelAccessErrors.jsonDetail("""{"detail":"Not Found"}"""))
        assertEquals("models/x not found", ModelAccessErrors.jsonDetail("""[{"error":{"code":404,"message":"models/x not found"}}]"""))
        assertEquals("", ModelAccessErrors.jsonDetail("<html><head><title>404 Not Found</title></head></html>"))
        assertEquals("", ModelAccessErrors.jsonDetail(""))
        assertEquals("<html><head><title>404 Not Found</title></head></html>", ModelAccessErrors.detail("<html><head><title>404 Not Found</title></head></html>"))
    }

    /** 元の単語から先頭と末尾のスペースを削除します。長すぎる場合は短くし、最後に「...」を追加します。 */
    @Test fun longDetailIsClippedWithAnEllipsis() {
        assertEquals("short", ModelAccessErrors.jsonDetail("""{"error":{"message":"  short \n"}}"""))
        val long = "models/x is not found. " + "y".repeat(200)
        val clipped = ModelAccessErrors.jsonDetail("""{"error":{"message":"$long"}}""")
        assertEquals(161, clipped.length)
        assertTrue(clipped, clipped.startsWith("models/x is not found. yyy") && clipped.endsWith("y…"))
        assertEquals(clipped, ModelAccessErrors.detail("""{"error":{"message":"$long"}}"""))
    }

    /** 429 は、このモデルのアカウントのクォータがゼロであることを示しています。変更できるのはモデルのみであり、使用できないとみなされます。残りの 429 (実際の現在の制限、使用されたクォータ) は呼び出し元に渡されます。 */
    @Test fun quotaZeroForThisModelIsNotAvailable() {
        val freeTierZero = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details. """ +
            """* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-x-pro",""" +
            """"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generate_content_free_tier_requests"}]}]}}"""
        val e = notAvailable(429, freeTierZero)
        assertTrue(e.detail, e.detail.startsWith("You exceeded your current quota"))
        notAvailable(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaValue":"0"}]}]}}""")
        notAvailable(429, """{"error":{"code":429,"message":"This model doesn't have a free quota tier. Please enable billing.","status":"RESOURCE_EXHAUSTED"}}""")
        // OpenAI 互換エントリは配列を返します
        val inArray = notAvailable(429, """[{"error":{"code":429,"message":"Quota exceeded for metric: free_tier_input_token_count, limit: 0","status":"RESOURCE_EXHAUSTED"}}]""")
        assertEquals("Quota exceeded for metric: free_tier_input_token_count, limit: 0", inArray.detail)
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"Quota exceeded for metric: requests_per_minute, limit: 10. Please retry in 7s.","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"limit: 0.5 rps","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, "", "m"))
    }

    /** モデルはオフラインです: 404 / 400 は、もう利用できません、廃止されました、モデル...非推奨と言います。元の言葉では、使用できないことを意味します。 Gemini は、API バージョンが見つからないことを意味します。 */
    @Test fun retiredModels() {
        assertEquals(
            "Gemini 1.5 Flash is no longer available. Please switch to a newer model.",
            notAvailable(404, """{"error":{"code":404,"message":"Gemini 1.5 Flash is no longer available. Please switch to a newer model.","status":"NOT_FOUND"}}""").detail,
        )
        notAvailable(400, """{"error":{"message":"The model gemini-1.0-pro has been retired."}}""")
        notAvailable(400, """{"error":{"message":"models/gemini-1.5-pro-001 is deprecated and no longer served"}}""")
        notAvailable(400, """{"error":{"message":"This model is deprecated."}}""")
        notAvailable(400, """{"error":{"message":"该模型已下线"}}""")
        assertTrue(
            ModelAccessErrors.of(400, """{"error":{"code":400,"message":"models/gemini-x is not found for API version v1beta, or is not supported for generateContent.","status":"INVALID_ARGUMENT"}}""", "m")
                is ModelError.ModelNotFound,
        )
        // は、特定のパラメーターが非推奨であることを示すだけで、それを認識しません。
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"The parameter `max_tokens` is deprecated, use `max_completion_tokens`."}}""", "m"))
    }

    /** 400 は、このモデルは現在のインターフェイスを使用できないと言っています (サービス プロバイダーには別の形状のセットが必要です)。このモデルが使用できない場合は、次のモデルを試して、サーバーの元の言葉を持ってきてください。 */
    @Test fun modelCannotGoThroughThisEndpoint() {
        val shape = """{"error":{"message":"Model claude-sonnet-5-5 must be called via /provider/v1/messages (Anthropic Messages shape).","type":"invalid_request_error","param":"model","code":"unsupported_model"}}"""
        val e = notAvailable(400, shape)
        assertEquals("m", e.model)
        assertTrue(e.detail, e.detail.startsWith("Model claude-sonnet-5-5 must be called via /provider/v1/messages"))
        // はエラーコードのみを表示します
        notAvailable(400, """{"error":{"type":"invalid_request_error","param":"model","code":"unsupported_model"}}""")
        notAvailable(400, """{"error":{"message":"This model is only supported in v1/responses and not in v1/chat/completions.","param":"model"}}""")
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"gpt-4o is not supported for this endpoint"}}""", "gpt-4o") is ModelError.ModelNotAvailable)
        // リクエスト自体が正しくない場合、リクエストは認識されず、ステータス コードに従って呼び出し元に渡されます。
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"Invalid value for 'temperature': 3"}}""", "m"))
        // は、モデルではなく、キーがこのインターフェイスを使用できないという問題について話しています。認識されません、403、呼び出し元はまだキーです
        assertNull(ModelAccessErrors.of(403, """{"error":{"message":"Your API key is not supported for this endpoint"}}""", "gpt-x"))
        assertTrue(ModelHttp.openAiError(403, null, """{"error":{"message":"Your API key is not supported for this endpoint"}}""", "gpt-x") is ModelError.Auth)
    }

    /** キー、クォータ、またはリクエスト自体が間違っている場合、認識されません。 401は表示されません。 */
    @Test fun otherErrorsAreLeftToTheAdapter() {
        assertNull(ModelAccessErrors.of(401, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"Invalid parameter: messages"}}""", "m"))
        assertNull(ModelAccessErrors.of(403, """{"error":{"message":"Incorrect API key provided"}}""", "m"))
        assertNull(ModelAccessErrors.of(500, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, "context_length_exceeded", "m"))
    }
}
