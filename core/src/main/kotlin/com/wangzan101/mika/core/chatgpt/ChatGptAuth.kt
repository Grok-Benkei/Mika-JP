package com.wangzan101.mika.core.chatgpt

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.oauth.Pkce
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** ログイン試行のランダム値: 毎回新たに生成され、コールバックおよびトークン交換中にチェックされます。 [redirectUri] は、この監視のコールバック アドレスです。 */
data class SignInAttempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String) {
    /** PKCE S256 ([Pkce.challenge] を参照)。 */
    val challenge: String get() = Pkce.challenge(verifier)
}

/** は、(認可コードまたはリフレッシュ トークンを使用して) トークンを交換することによって取得されたトークンのセットです。 [scopes] は、実際に付与された権限です。 */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSec: Long,
    val scopes: List<String>,
)

/** トークン交換の結果: 取得、拒否 (サーバー エラー コードあり。[terminal] は true、リフレッシュ トークンが使用できなくなったことを示します)、または一時的に失敗しました (ネットワーク、5xx)。 */
sealed interface TokenResult {
    data class Ok(val tokens: TokenSet) : TokenResult
    /**
     * [detail] は、サーバーによって与えられたエラーの説明 (error_description または error.message) です。これは人間が閲覧することのみを目的としており、トークンは含まれません。
     * [nonJson] が true の場合、戻り値が JSON ではないことを意味します (ほとんどの場合、保護ページの HTML)。この場合、[code] は `http_<ステータスコード>` になります。
     */
    data class Rejected(val code: String, val terminal: Boolean, val detail: String? = null, val nonJson: Boolean = false) : TokenResult
    data class Failed(val error: ModelError) : TokenResult
}

/** ID トークン内の検証済み ID: [sub] はアカウント ID、[email] は人々が見るためだけに使用されます (空の場合もあります)。 */
data class IdClaims(val sub: String, val email: String)

/**
 * ログインに失敗したのはなぜですか?インターフェイスに理由が表示されます ([ChatGptAuth.failureOf] を参照)。 [CodeExpired] は、認証コードの有効期限が切れているか、使用されているか、または間違っていることを意味します。
 * [Region] はこの地域ではサポートされていません。 [Blocked] は、リクエストが保護によってブロックされたことを示します (返された Web ページは JSON ではありません)。 [NoPlanScope] はサーバー側のみにあります
 * は、パッケージと権限が言及されている場合、または付与された権限にパッケージ項目がない場合に使用されます。他の拒否は [Exchange] (エラー コード付き) です。
 */
enum class SignInFailure { Denied, StateMismatch, ClientMismatch, NoClientId, TimedOut, Exchange, CodeExpired, Region, Blocked, IdToken, NoPlanScope, Network }

/** [code] は、サーバーによって与えられたエラー コードです (機密ではありません。他の人が表示したり、診断情報に書き込むことができます)。そうでない場合は null です。 */
class SignInException(val reason: SignInFailure, detail: String = "", cause: Throwable? = null, val code: String? = null) :
    Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause)

/**
 * 「ChatGPT でログイン」し、ユーザーの ChatGPT パッケージ クォータを使用してモデルを呼び出します (OpenAI は、オープン ソースでネイティブに実行されるアプリにこのパスを提供します)。
 * パブリック クライアント、キーなし、認証コード + PKCE (S256)。初めて `dynamic_agent_client` に登録すると、コールバックによってこのアカウントの一意のパスワードが与えられます。
 * client_id。後でトークンを変更し、更新し、再度ログインするときに使用します。ローカル ID ([hostId]、`urn:uuid:…`) はデバイスごとに 1 回生成され、永久に使用されます。
 * コールバック アドレスは、`http://127.0.0.1:<端口>/auth/callback` (ポート変数) のみです。
 *
 * ID トークンは、公開された JWKS (RS256) に従って検証され、発行者、対象者 (= client_id)、有効期限、およびこの nonce がチェックされます。 パッケージ クォータを使用するには、
 * によって付与される権限に [PLAN_SCOPE] が必要です。
 */
class ChatGptAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenUrl = "$issuer/api/accounts/oauth/token"
    private val revokeUrl = "$issuer/api/accounts/oauth/revoke"
    private val jwksUrl = "$issuer/.well-known/jwks.json"

    /** 最新の JWKS (kid → 公開鍵パラメータ) を取得しました。署名検証中に kid が見つからず、再度取得できませんでした (サーバーがキーを変更しました)。 */
    @Volatile private var jwks: Map<String, JsonObject> = emptyMap()

    fun newAttempt(redirectUri: String) = SignInAttempt(randomString(), randomString(), randomString(48), redirectUri)

    /**
     * 認証ページのアドレスを開きます。 [clientId] が null の場合、初めて `dynamic_agent_client` を使用して登録し、アプリ名を取得することを意味します。
     * 最後の ID トークン ([idTokenHint]、有効期限が切れている可能性があります。ログアウト後は使用されません) と電子メール アドレス ([loginHint]) を使用して再度ログインします。
     * アドレスに ID トークンが存在する可能性があるため、ログに書き込まないでください。
     */
    fun authorizeUrl(attempt: SignInAttempt, clientId: String?, hostId: String, idTokenHint: String? = null, loginHint: String? = null): String =
        "$issuer/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: REGISTER_CLIENT_ID)
            .apply { if (clientId == null) addQueryParameter("agent_name_hint", APP_NAME) }
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply {
                if (clientId != null && !idTokenHint.isNullOrEmpty()) addQueryParameter("id_token_hint", idTokenHint)
                if (clientId != null && !loginHint.isNullOrEmpty()) addQueryParameter("login_hint", loginHint)
            }
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("nonce", attempt.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", attempt.challenge)
            .build().toString()

    /**
     * はコールバックパラメータを読み込んで(認可コード、今回使用したclient_id)を取得します。まず状態を確認します。ユーザーが拒否した場合、状態が正しくない場合、client_id がこのリクエストと一致しない場合、
     * は初めて登録されましたが、client_id が指定されていなかったため、[SignInException] がスローされました。
     */
    fun readCallback(params: Map<String, String>, attempt: SignInAttempt, clientId: String?): Pair<String, String> {
        if (params["state"] != attempt.state) throw SignInException(SignInFailure.StateMismatch)
        params["error"]?.let { err ->
            // 認証ページによって返されるエラー: 地域とパッケージについて言える場合は、それを伝えるだけで、残りの部分はユーザーが同意していないかのように扱います。 スコープなしの
            val f = failureOf(err, params["error_description"])
            throw SignInException(if (f == SignInFailure.Region || f == SignInFailure.NoPlanScope) f else SignInFailure.Denied, err, code = err)
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInFailure.Exchange, "no code", code = "no_code")
        val issued = params["client_id"]?.takeIf { it.isNotEmpty() }
        if (clientId != null && issued != null && issued != clientId) throw SignInException(SignInFailure.ClientMismatch)
        val id = issued ?: clientId ?: throw SignInException(SignInFailure.NoClientId)
        return code to id
    }

    suspend fun exchange(clientId: String, code: String, attempt: SignInAttempt): TokenResult = token(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build(),
    )

    /** : 最初に付与された権限を保持します。 */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResult = token(
        FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken)
            .add("resource", RESOURCE).build(),
    )

    /** はリフレッシュ トークンをログアウトします。サーバー側は (HTTP 200、トークンはすでに無効です、これも 200) が true であることを確認します。ログアウトしても、登録されているクライアントは削除されません。 */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean = try {
        val form = FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
        HttpSupport.execute(http, Request.Builder().url(revokeUrl).post(form).build()).status == 200
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun token(form: FormBody): TokenResult {
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(tokenUrl).post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return TokenResult.Failed(e)
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        if (raw.status in 500..599) return TokenResult.Failed(ModelError.Server(raw.status))
        if (raw.status != 200) {
            val code = root?.let { (it["error"] as? JsonObject)?.str("code") ?: it.str("error") ?: it.str("code") } ?: "http_${raw.status}"
            val detail = root?.let { it.str("error_description") ?: (it["error"] as? JsonObject)?.str("message") }
            return TokenResult.Rejected(code, code in TERMINAL_REFRESH_ERRORS, detail, nonJson = root == null)
        }
        val access = root?.str("access_token") ?: return TokenResult.Failed(ModelError.BadResponse("トークン更新の応答にaccess_tokenがありません"))
        return TokenResult.Ok(
            TokenSet(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    /** 検証 ID トークン: 署名 (JWKS、RS256)、発行者、対象者、有効期限、ノンス。すべて ID を返します。それ以外の場合は、[SignInException] をスローします。 */
    suspend fun verifyIdToken(idToken: String, clientId: String, nonce: String): IdClaims {
        fun fail(why: String): Nothing = throw SignInException(SignInFailure.IdToken, why)
        val pieces = idToken.split('.')
        if (pieces.size != 3) fail("not a JWT")
        val header = decodeJson(pieces[0]) ?: fail("bad header")
        val claims = decodeJson(pieces[1]) ?: fail("bad payload")
        if (header.str("alg") != "RS256") fail("alg ${header.str("alg")}")
        val kid = header.str("kid")
        val key = jwk(kid, refresh = false) ?: jwk(kid, refresh = true) ?: fail("unknown key")
        val verified = runCatching {
            val n = BigInteger(1, b64urlDecode(key.str("n")!!))
            val e = BigInteger(1, b64urlDecode(key.str("e")!!))
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pub)
                update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
                verify(b64urlDecode(pieces[2]))
            }
        }.getOrDefault(false)
        if (!verified) fail("signature")
        if (claims.str("iss") != issuer) fail("issuer")
        val aud = claims["aud"]
        val audOk = when (aud) {
            is JsonPrimitive -> aud.contentOrNull == clientId
            is JsonArray -> aud.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audOk) fail("audience")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: fail("no exp")
        if (exp * 1000 + CLOCK_SKEW_MS < now()) fail("expired")
        if (claims.str("nonce") != nonce) fail("nonce")
        val sub = claims.str("sub")?.takeIf { it.isNotEmpty() } ?: fail("no sub")
        val email = claims.str("email") ?: (claims[PROFILE_CLAIM] as? JsonObject)?.str("email") ?: ""
        return IdClaims(sub, email)
    }

    private suspend fun jwk(kid: String?, refresh: Boolean): JsonObject? {
        if (refresh || jwks.isEmpty()) {
            val raw = try { HttpSupport.execute(http, Request.Builder().url(jwksUrl).get().build()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw SignInException(SignInFailure.Network, "jwks", e)
            }
            val keys = runCatching { (json.parseToJsonElement(raw.body) as JsonObject)["keys"] as JsonArray }.getOrNull().orEmpty()
            jwks = keys.filterIsInstance<JsonObject>().filter { it.str("kty") == "RSA" }.associateBy { it.str("kid").orEmpty() }
        }
        return if (kid == null) jwks.values.singleOrNull() else jwks[kid]
    }

    private fun decodeJson(part: String): JsonObject? = runCatching { json.parseToJsonElement(String(b64urlDecode(part))) as? JsonObject }.getOrNull()

    private fun randomString(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val REGISTER_CLIENT_ID = "dynamic_agent_client"
        const val APP_NAME = "Mika"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        const val RESOURCE = "https://api.openai.com/v1"
        /** パッケージ クォータを使用して呼び出されるインターフェイス ルート (列モデル、応答)。 */
        const val API_BASE = "https://api.openai.com/v1"
        /** ChatGPT での使用状況を表示および管理するためのユーザーのページ。 */
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private const val DEFAULT_EXPIRES_SEC = 3600L
        private const val CLOCK_SKEW_MS = 120_000L

        /** リフレッシュ トークンは使用できなくなりました。トークンをクリアし、元の client_id で再度ログインしてください。 */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        /** ユーザーが同意しないか、ログインが完了していないことを示すエラー コード。 */
        private val DENIED_ERRORS = setOf("access_denied", "consent_required", "login_required", "interaction_required")
        /** は、パッケージまたは権限のエラー コードを指します。 */
        private val PLAN_ERRORS = setOf("invalid_scope", "insufficient_scope", "subscription_sharing_user_not_eligible")
        private val REGION = Regex("""unsupported_country|region|territor""", RegexOption.IGNORE_CASE)
        private val PLAN = Regex("""(?<![a-z])(plan|subscription|scope)s?(?![a-z])""", RegexOption.IGNORE_CASE)

        /**
         * 拒否理由はどのカテゴリに分類されますか?インターフェースは人間の言葉でそれを表現しています: 保護によってブロックされており (403、429、戻り値は JSON ではありません)、その地域ではサポートされていません。
         * 認証コードは期限切れまたは使用されており (`invalid_grant`)、ユーザーはパッケージまたは権限 (コードまたは説明に記載されている) に同意せず、残りは [SignInFailure.Exchange] です。
         */
        fun failureOf(code: String, detail: String?, nonJson: Boolean = false): SignInFailure {
            val text = "$code ${detail.orEmpty()}"
            return when {
                nonJson && (code == "http_403" || code == "http_429") -> SignInFailure.Blocked
                REGION.containsMatchIn(text) -> SignInFailure.Region
                code == "invalid_grant" -> SignInFailure.CodeExpired
                code in DENIED_ERRORS -> SignInFailure.Denied
                code in PLAN_ERRORS || PLAN.containsMatchIn(text) -> SignInFailure.NoPlanScope
                else -> SignInFailure.Exchange
            }
        }

        /** トークン交換が拒否されたときにスローされる例外: 理由は [failureOf] に従って分類され、エラー コードと説明が示されます。 */
        fun rejected(r: TokenResult.Rejected): SignInException = SignInException(
            failureOf(r.code, r.detail, r.nonJson), listOfNotNull(r.code, r.detail).joinToString(" — "), code = r.code,
        )

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

        /** ネイティブ識別: UUIDv4 URN、デバイスごとに 1 回生成され、継続的に使用されます。 */
        fun newHostId(): String = "urn:uuid:${java.util.UUID.randomUUID()}"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
