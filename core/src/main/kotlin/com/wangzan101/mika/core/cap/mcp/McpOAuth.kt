package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.oauth.Pkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** OAuth が失敗しました。エラー カテゴリ/コードのみが含まれ、トークンや応答本文が含まれていないメッセージ。 */
class McpOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** OAuth ログインのランダムな値。 [redirectUri] はローカル コールバック アドレスです。 */
data class McpOAuthAttempt(
    val state: String,
    val verifier: String,
    val redirectUri: String,
) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/** 認可サーバーのエンドポイント (RFC 8414 / RFC 9728 検出結果)。 */
data class McpOAuthServer(
    val authorizationEndpoint: HttpUrl,
    val tokenEndpoint: HttpUrl,
    val registrationEndpoint: HttpUrl?,
    val resource: HttpUrl?,
    val scope: String? = null,
)

/** 動的に登録されたパブリック クライアント (RFC 7591)。 */
data class McpOAuthRegistration(val clientId: String, val clientSecret: String?)

/** は保存されたトークンを暗号化します。更新時に必要なのは、tokenEndpoint、clientId、およびrefreshTokenのみです。 */
@Serializable
data class McpOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpoint: String,
    val resource: String? = null,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs == null || nowMs < expiresAtMs - 60_000
}

/**
 * MCP の OAuth 2.1 クライアント (RFC 9728 リソース メタデータ → RFC 8414 認可サーバー メタデータ → RFC 7591 動的登録 → PKCE 認可コード)。
 *
 * はパブリック クライアントのみです。`token_endpoint_auth_method=none` は登録時にキーを保存しません (一部のサーバーでは client_secret がそのまま提供されます)。
 * すべてのエンドポイントは HTTPS である必要があります。ネイティブ/プライベート アドレスの HTTP は、テストとローカルホスト開発の場合にのみ受け入れられます。トークンはメモリ/暗号化ストレージにのみ入力され、ログには入力されません。
 */
class McpOAuth(
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = http.newBuilder()
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** は、MCP サーバー アドレスから認可サーバーを検出します。リソースのメタデータがない場合は、サーバーのオリジンに戻ります。 */
    suspend fun discover(serverUrl: HttpUrl): McpOAuthServer {
        val resourceMetadata = fetchMetadata(
            listOfNotNull(
                serverUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource").query(null).build(),
                serverUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource" + serverUrl.encodedPath).query(null).build()
                    .takeIf { serverUrl.encodedPath.isNotEmpty() && serverUrl.encodedPath != "/" },
            ),
        ) ?: probeResourceMetadata(serverUrl)
        val authServer = (resourceMetadata?.get("authorization_servers") as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?.toHttpUrlOrNull()
            ?: serverUrl.newBuilder().encodedPath("/").query(null).build()
        val metadata = fetchMetadata(
            listOfNotNull(
                authServer.newBuilder().encodedPath("/.well-known/oauth-authorization-server").query(null).build(),
                authServer.newBuilder().encodedPath("/.well-known/openid-configuration").query(null).build(),
            ),
        ) ?: throw McpOAuthException("oauth_server_metadata_missing")
        val authorization = metadata["authorization_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw McpOAuthException("oauth_authorization_endpoint_missing")
        val token = metadata["token_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw McpOAuthException("oauth_token_endpoint_missing")
        requireSafeEndpoint(authorization)
        requireSafeEndpoint(token)
        val registration = metadata["registration_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()?.also(::requireSafeEndpoint)
        val resource = (resourceMetadata?.get("resource") as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() ?: serverUrl
        val scopes = (resourceMetadata?.get("scopes_supported") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotEmpty() }
        return McpOAuthServer(authorization, token, registration, resource, scopes)
    }

    /** はパブリック クライアントを動的に登録します。リダイレクト URI はネイティブ コールバックを使用します。 */
    suspend fun register(server: McpOAuthServer, redirectUri: String, clientName: String = "Mika"): McpOAuthRegistration {
        val endpoint = server.registrationEndpoint ?: throw McpOAuthException("oauth_registration_unsupported")
        requireSafeEndpoint(endpoint)
        val body = JsonObject(
            mapOf(
                "client_name" to JsonPrimitive(clientName),
                "redirect_uris" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(redirectUri))),
                "grant_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))),
                "response_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
                "token_endpoint_auth_method" to JsonPrimitive("none"),
            ),
        )
        val (status, text) = postJson(endpoint, body.toString(), "application/json")
        if (status !in 200..299) throw McpOAuthException("oauth_registration_http_$status")
        val root = parseObject(text) ?: throw McpOAuthException("oauth_registration_invalid")
        val clientId = root["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpOAuthException("oauth_registration_no_client")
        return McpOAuthRegistration(clientId, root["client_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
    }

    /** 認証ページ アドレス (PKCE S256 + 状態 + RFC 8707 リソース)。 */
    fun authorizeUrl(
        server: McpOAuthServer,
        registration: McpOAuthRegistration,
        attempt: McpOAuthAttempt,
        resource: HttpUrl?,
    ): String = server.authorizationEndpoint.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", registration.clientId)
        .addQueryParameter("redirect_uri", attempt.redirectUri)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", attempt.state)
        .apply { resource?.let { addQueryParameter("resource", it.toString()) } }
        .apply { server.scope?.let { addQueryParameter("scope", it) } }
        .build().toString()

    /** は、認証コードをトークンと交換します。 */
    suspend fun exchange(
        server: McpOAuthServer,
        registration: McpOAuthRegistration,
        code: String,
        attempt: McpOAuthAttempt,
        resource: HttpUrl?,
    ): McpOAuthTokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", attempt.redirectUri)
            .add("client_id", registration.clientId)
            .add("code_verifier", attempt.verifier)
            .apply { resource?.let { add("resource", it.toString()) } }
            .build()
        return readTokens(postForm(server.tokenEndpoint, form), registration, server.tokenEndpoint, resource)
    }

    /** リフレッシュ トークンを使用して新しいアクセス トークンを交換します。サーバーが新しいリフレッシュ トークンを提供しない場合は、古いリフレッシュ トークンが使用されます。 */
    suspend fun refresh(tokens: McpOAuthTokens): McpOAuthTokens {
        val refresh = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: throw McpOAuthException("oauth_refresh_missing")
        val endpoint = tokens.tokenEndpoint.toHttpUrlOrNull()?.also(::requireSafeEndpoint)
            ?: throw McpOAuthException("oauth_token_endpoint_invalid")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", tokens.clientId)
            .apply { tokens.clientSecret?.let { add("client_secret", it) } }
            .apply { tokens.resource?.let { add("resource", it) } }
            .build()
        val registration = McpOAuthRegistration(tokens.clientId, tokens.clientSecret)
        val next = readTokens(postForm(endpoint, form), registration, endpoint, tokens.resource?.toHttpUrlOrNull())
        return next.copy(refreshToken = next.refreshToken ?: refresh)
    }

    /** ユーザーが拒否したか、コールバックでエラーが発生しました。状態に一致する状態のみが認識されます。 */
    fun readCallbackError(params: Map<String, String>, attempt: McpOAuthAttempt): McpOAuthException? {
        val state = params["state"]
        if (state != attempt.state) return McpOAuthException("oauth_state_mismatch")
        val code = params["code"]?.takeIf { it.isNotBlank() }
        if (code != null) return null
        return McpOAuthException("oauth_denied_" + (params["error"]?.take(40) ?: "no_code"))
    }

    /** コールバックで認証コードを取得します。状態が一致する必要があります。 */
    fun readCode(params: Map<String, String>, attempt: McpOAuthAttempt): String {
        if (params["state"] != attempt.state) throw McpOAuthException("oauth_state_mismatch")
        return params["code"]?.takeIf { it.isNotBlank() } ?: throw McpOAuthException("oauth_no_code")
    }

    fun newAttempt(redirectUri: String) =
        McpOAuthAttempt(Pkce.state(), Pkce.verifier(), redirectUri)

    private suspend fun readTokens(
        response: OAuthHttp,
        registration: McpOAuthRegistration,
        tokenEndpoint: HttpUrl,
        resource: HttpUrl?,
    ): McpOAuthTokens {
        if (response.code !in 200..299) {
            val error = runCatching { parseObject(response.body)?.get("error")?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw McpOAuthException("oauth_token_http_${response.code}" + (error?.let { "_$it" } ?: ""))
        }
        val root = parseObject(response.body) ?: throw McpOAuthException("oauth_token_invalid")
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpOAuthException("oauth_token_missing")
        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull
        return McpOAuthTokens(
            accessToken = access,
            refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            expiresAtMs = expiresIn?.takeIf { it > 0 }?.let { now() + it * 1000 - 30_000 },
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            tokenEndpoint = tokenEndpoint.toString(),
            resource = resource?.toString(),
        )
    }

    private suspend fun fetchMetadata(urls: List<HttpUrl>): JsonObject? {
        for (url in urls) {
            if (url.isHttps) Unit
            else if (isLoopbackHost(url.host)) Unit
            else throw McpOAuthException("oauth_insecure_endpoint")
            val response = try {
                get(url)
            } catch (e: IOException) {
                continue
            }
            if (response.code !in 200..299) continue
            return parseObject(response.body)
        }
        return null
    }

    /** リソース メタデータがよく知られていない場合は、MCP 仕様に従って 401 WWW-Authenticate から `resource_metadata` を検索します。 */
    private suspend fun probeResourceMetadata(serverUrl: HttpUrl): JsonObject? {
        val response = try {
            executeRaw(
                Request.Builder().url(serverUrl)
                    .header("Accept", "application/json, text/event-stream").get().build(),
            )
        } catch (e: IOException) {
            return null
        }
        if (response.code != 401) return null
        val header = response.headers["WWW-Authenticate"] ?: return null
        val match = Regex("""resource_metadata=(?:"([^"]+)"|([^,\s]+))""").find(header) ?: return null
        val url = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toHttpUrlOrNull() ?: return null
        return fetchMetadata(listOf(url))
    }

    private fun requireSafeEndpoint(url: HttpUrl) {
        if (url.isHttps || isLoopbackHost(url.host)) return
        throw McpOAuthException("oauth_insecure_endpoint")
    }

    private suspend fun get(url: HttpUrl): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json").get().build(),
    )

    private suspend fun postForm(url: HttpUrl, form: FormBody): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json").post(form).build(),
    )

    private suspend fun postJson(url: HttpUrl, body: String, type: String): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody(type.toMediaType())).build(),
    )

    private suspend fun executeRaw(request: Request): OAuthHttp = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { OAuthHttp(it.code, it.body?.string().orEmpty(), it.headers) }
    }

    private data class OAuthHttp(val code: Int, val body: String, val headers: okhttp3.Headers)

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

}
