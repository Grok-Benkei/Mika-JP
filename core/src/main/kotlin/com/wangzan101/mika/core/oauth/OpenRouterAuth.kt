package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenRouter 認証のランダム値: 毎回新しく生成されます。 [callbackUrl] は、このマシンによって監視されるコールバック アドレスです。 */
data class OpenRouterAttempt(val state: String, val verifier: String, val callbackUrl: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * OpenRouter アカウントでログインします: サードパーティ アプリケーション用の OpenRouter 公式 OAuth PKCE。ブラウザで認証ページを開き、ユーザーが同意した後、`code`を持参してください
 * はローカル コールバックに戻り、`code` とチェック文字列を使用して、このユーザーに属する API キーを交換します。置き換えられたのは通常のOpenRouter Keyですが、
 * は、キーを入力する OpenRouter サービス プロバイダーと同じ方法で使用されます (リスト モデルと呼び出しは [API_BASE] に送信されます)。
 * キーはログに記録されません。例外テキストには、カテゴリとエラー コードのみが含まれます。
 */
class OpenRouterAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val keysUrl: String = KEYS_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt(callbackUrl: String) = OpenRouterAttempt(Pkce.state(), Pkce.verifier(), callbackUrl)

    /** 認証ページのアドレス: コールバック アドレス、S256 検証、キー名 (事前入力、ユーザーは変更可能)、状態。 今回は、 */
    fun authorizeUrl(attempt: OpenRouterAttempt): String = authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", attempt.callbackUrl)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", KEY_LABEL)
        .addQueryParameter("state", attempt.state)
        .build().toString()

    /**
     * のローカル コールバックが収集されますか。状態が適切であれば、収集されます。状態、コードがなく、`error` (ユーザーが認証ページで拒否した場合、OpenRouter には状態がありません) の場合も収集されます。
     * は同意されていないものとみなします。その他 (コードはあるが、状態が一貫していない、または何もない) は受け入れられません。今回は開始されていないため、400 を返して待機します。
     */
    fun accepts(params: Map<String, String>, attempt: OpenRouterAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** コールバックから `code` を取り出します。そうでない場合、ユーザーは同意しませんでした (または認証ページがエラーを報告しました)。 */
    fun readCallback(params: Map<String, String>, attempt: OpenRouterAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /**
     * は、`code` とチェックサム交換キー (`POST /api/v1/auth/keys`) を使用します。 400 は検証方法が一致しないことを意味し、403 はコードまたは検証文字列が間違っているか、コードの有効期限が切れている (10 分) ことを意味します。
     * エラーが発生すると、応答テキストなしでステータス コードのみが返されます。
     */
    suspend fun exchange(code: String, attempt: OpenRouterAttempt): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", attempt.verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody(JSON_TYPE)
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(keysUrl).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "http", e)
        }
        when {
            raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_403")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        return (root?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** に切り替えられたキーによって呼び出されるインターフェイス ルートは、OpenRouter のデフォルトと同じです。 */
        const val API_BASE = "https://openrouter.ai/api/v1"
        /** OpenRouterのKeyを管理(削除)するためのユーザーページです。 */
        const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
        /** 認証ページに事前に入力されているキー名。ユーザーはこれを使用して、キー ページでどのキーであるかを識別できます。 */
        const val KEY_LABEL = "Mika"
        /** は、コールバック アドレスとして localhost を使用します (OpenRouter は、このマシン上の任意のコールバック ポートを使用できることを示します)。リスニング アドレスは 127.0.0.1 にのみ関連付けられます。 */
        const val CALLBACK_HOST = "localhost"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * OpenRouter ログインを実行します。ローカル コールバックを開く → [openBrowser] 認証ページを開く → コールバックを待つ → キーを変更します。
 * キーの変更 このステップ ([finishTimeoutMs] まで) は、結果の Web ページをブラウザーに返す前に完了します。 [page] null を受け取った場合は成功を意味し、それ以外の場合は失敗の原因となります。
 * は失敗し、[AccountSignInException] をスローします。いつものようにキャンセルして捨てます。変更されたAPIキーを返します。
 */
suspend fun signInWithOpenRouter(
    auth: OpenRouterAuth,
    openBrowser: (String) -> Unit,
    page: (AccountSignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): String {
    var outcome: Result<String>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri(OpenRouterAuth.CALLBACK_HOST))
        openBrowser(auth.authorizeUrl(attempt))
        cb.await(timeoutMs, { auth.accepts(it, attempt) }) { params ->
            val r = try {
                Result.success(withTimeout(finishTimeoutMs) { auth.exchange(auth.readCallback(params, attempt), attempt) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSignInException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
            }
            outcome = r
            page(r.exceptionOrNull() as AccountSignInException?)
        } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}
