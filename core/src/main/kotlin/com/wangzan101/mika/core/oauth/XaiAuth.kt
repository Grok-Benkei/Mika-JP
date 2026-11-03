package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * xAI / Grok サブスクリプションを使用してログインし、xAI 独自の応答インターフェイス (`https://api.x.ai/v1/responses`) を直接調整します。
 *
 * クライアント ID の再利用 xAI 公式コマンド ライン ツールのパブリック ログイン入口 (パブリック client_id、Mika 用に xAI によって登録されたアプリケーションではありません)。 xAI はこの使用法をサポートしていない可能性があります。
 * アカウントが制限されるリスクがあります。インターフェイスにはログイン前にこれが表示されますが、それをクリックしない限り表示されません。ドキュメント内のアドレスは https である必要があり、ホストは [HOST] であることがわかります。
 * が一致しない場合は、拒否して接続しないでください。リフレッシュ トークンは同じトークン エンドポイントを通過します。
 */
class XaiAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val clientId: String = CLIENT_ID,
    /** トークン、ディスカバリー、デバイス コード エンドポイントがホストを許可しました。デフォルトでは auth.x.ai のみがあります。テスト中は、ローカル マシンに切り替えます。 */
    private val issuerHosts: Set<String> = setOf(HOST),
    /** は、ユーザーが開いた検証ページで許可されたホストを示します。 */
    private val verifyHosts: Set<String> = setOf(HOST, "accounts.x.ai"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** は OIDC 検出ドキュメントを読み取り、ホストによって検証されたデバイス コードとトークン アドレスのみを残します。 */
    suspend fun discover(): XaiEndpoints {
        val docUrl = OauthUrls.check(issuer.trimEnd('/') + "/.well-known/openid-configuration", issuerHosts)
        val raw = get(docUrl.toString())
        if (raw.status != 200) throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
        val root = jsonObject(raw.body) ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "discovery")
        val found = root.str("issuer")?.trimEnd('/')
        if (found != issuer.trimEnd('/')) throw AccountSignInException(AccountSignInFailure.BadIssuer, "issuer")
        val device = root.str("device_authorization_endpoint")
            ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "no_device")
        val token = root.str("token_endpoint") ?: throw AccountSignInException(AccountSignInFailure.BadIssuer, "no_token")
        OauthUrls.check(device, issuerHosts)
        OauthUrls.check(token, issuerHosts)
        val userinfo = root.str("userinfo_endpoint")?.takeIf { OauthUrls.allowed(it, issuerHosts) }
        return XaiEndpoints(device, token, userinfo)
    }

    suspend fun signIn(
        openBrowser: (String) -> Unit,
        onChallenge: (DeviceChallenge) -> Unit,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
        clock: () -> Long = now,
    ): AccountSession {
        val endpoints = discover()
        val client = DeviceCodeClient(http, endpoints.deviceUrl, endpoints.tokenUrl, clientId, SCOPES, issuerHosts)
        val grant = client.start().also { published ->
            val challenge = published.challenge.withVerifiedHosts()
            onChallenge(challenge)
            openBrowser(challenge.verificationUriComplete ?: challenge.verificationUri)
        }
        val tokens = awaitDeviceGrant(grant, clock, sleep) { client.poll(it) }
        if (tokens.scopes.isNotEmpty() && tokens.scopes.none { it == SCOPE_CLI || it == SCOPE_API }) {
            throw AccountSignInException(AccountSignInFailure.NoSubscription, "scope")
        }
        val who = identity(endpoints.userinfoUrl, tokens)
        return AccountSession(
            provider = PROVIDER,
            accountId = who.first.ifBlank { "xai" },
            label = who.second,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            expiresAtMs = clock() + tokens.expiresInSec * 1000,
            scopes = tokens.scopes,
            apiBase = API_BASE,
            clientId = clientId,
            issuer = issuer.trimEnd('/'),
            tokenUrl = endpoints.tokenUrl,
        )
    }

    /** リフレッシュ トークンを新しいセットと交換します。このアドレスはログイン時に記録された発行者であり、ホストはまだ許可リストに含まれている必要があります。 */
    suspend fun refresh(session: AccountSession): OauthTokenResult {
        if (session.refreshToken.isEmpty()) return OauthTokenResult.Rejected("no_refresh_token", terminal = true)
        val issuer = session.issuer.ifBlank { this.issuer }.trimEnd('/')
        val tokenUrl = session.tokenUrl.ifBlank { "$issuer/oauth2/token" }
        val url = try {
            OauthUrls.check(tokenUrl, issuerHosts)
        } catch (e: AccountSignInException) {
            return OauthTokenResult.Rejected(e.code ?: "host", terminal = true)
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", session.refreshToken)
            .add("client_id", session.clientId.ifBlank { clientId })
            .build()
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return OauthTokenResult.Failed(e)
        }
        if (raw.status in 500..599) return OauthTokenResult.Failed(ModelError.Server(raw.status))
        val root = jsonObject(raw.body)
        val err = root?.str("error")
        if (raw.status != 200 || err != null || root == null) {
            val code = err ?: "http_${raw.status}"
            return OauthTokenResult.Rejected(code, terminal = code in TERMINAL_REFRESH || raw.status == 401)
        }
        val access = root.str("access_token") ?: return OauthTokenResult.Rejected("no_access_token", terminal = false)
        return OauthTokenResult.Ok(
            OauthTokens(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: 3600,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
                apiBase = API_BASE,
            ),
        )
    }

    private fun DeviceChallenge.withVerifiedHosts(): DeviceChallenge {
        val verify = if (OauthUrls.allowed(verificationUri, verifyHosts)) verificationUri else FALLBACK_VERIFY
        if (!OauthUrls.allowed(verify, verifyHosts)) throw AccountSignInException(AccountSignInFailure.BadIssuer, "verify")
        val complete = verificationUriComplete?.takeIf { OauthUrls.allowed(it, verifyHosts) }
        return copy(verificationUri = verify, verificationUriComplete = complete)
    }

    /** 他の人に示される ID: 最初に userinfo を使用します。失敗した場合は、ID トークンのステートメントから読み取ります (表示のみに使用され、承認には使用されません)。読み取れない場合は空白のままにしてください。 */
    private suspend fun identity(userinfoUrl: String?, tokens: OauthTokens): Pair<String, String> {
        if (userinfoUrl != null) {
            val raw = try {
                HttpSupport.execute(
                    http,
                    Request.Builder().url(userinfoUrl).header("Accept", "application/json")
                        .header("Authorization", "Bearer ${tokens.accessToken}").get().build(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val root = raw?.takeIf { it.status == 200 }?.let { jsonObject(it.body) }
            if (root != null) {
                val sub = root.str("sub").orEmpty()
                val email = root.str("email") ?: root.str("preferred_username") ?: ""
                if (sub.isNotEmpty() || email.isNotEmpty()) return sub to email
            }
        }
        return idClaims(tokens.idToken)
    }

    private suspend fun get(url: String) = try {
        HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").get().build())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelError) {
        throw AccountSignInException(AccountSignInFailure.Network, "http", e)
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    companion object {
        const val PROVIDER = "xai"
        const val HOST = "auth.x.ai"
        const val ISSUER = "https://auth.x.ai"
        /** xAI 公式コマンドラインツールのパブリッククライアント。ミカさんは登録されていません。再利用にはリスクが伴います。インターフェースの説明を参照してください。 */
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE_CLI = "grok-cli:access"
        const val SCOPE_API = "api:access"
        const val SCOPES = "openid profile email offline_access $SCOPE_CLI $SCOPE_API"
        /** サブスクリプション ログイン呼び出しのインターフェイス ルート: xAI 独自の応答。他のプロトコルには変換されません。 */
        const val API_BASE = "https://api.x.ai/v1"
        private const val FALLBACK_VERIFY = "https://accounts.x.ai/oauth2/device"

        /** ID はトークンの途中にあるステートメントです。デコードできない場合は空になります。署名検証なし: 電子メール アドレスを表示するためにのみ使用されます。 */
        fun idClaims(idToken: String?): Pair<String, String> {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return "" to ""
            val bytes = runCatching {
                java.util.Base64.getUrlDecoder().decode(payload.trimEnd('='))
            }.getOrNull() ?: return "" to ""
            val obj = runCatching {
                Json { ignoreUnknownKeys = true }.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return "" to ""
            val email = obj.str("email") ?: (obj["https://api.x.ai/profile"] as? JsonObject)?.str("email") ?: ""
            return obj.str("sub").orEmpty() to email
        }
    }
}

data class XaiEndpoints(val deviceUrl: String, val tokenUrl: String, val userinfoUrl: String?)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
