package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * サブスクリプション アカウントのログインが失敗した理由。インターフェイスはそれに応じて人間の言語を話します。例外テキストには、カテゴリとエラー コードのみが含まれ、トークン、認証コード、デバイス コードは含まれません。
 * [NoSubscription] は、アカウントにこのサブスクリプションがないことを意味します。 [BadIssuer] は、見つかったアドレスが許可されたホスト上にないため、リクエストは送信されないことを意味します。
 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, NoSubscription, Network, BadIssuer }

/** [code] はサーバーのエラー コードです (機密ではありません)。 */
class AccountSignInException(
    val reason: AccountSignInFailure,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(buildString {
    append(reason.name)
    if (!code.isNullOrBlank()) append(": ").append(code)
}, cause)

/** トークンを交換して取得したトークンのセット。リフレッシュによって提供されないフィールドは null のままで、呼び出し元は古いフィールドを使用します。 */
data class OauthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSec: Long = 3600,
    val scopes: List<String> = emptyList(),
    /** は、変更されたインターフェイス ルートです。指定されない場合は null になります。 */
    val apiBase: String? = null,
)

/** トークン交換の結果。拒否された場合、[terminal] は true になります。これは、この認証が使用できなくなるため、認証をクリアして再度ログインする必要があることを意味します。 */
sealed interface OauthTokenResult {
    data class Ok(val tokens: OauthTokens) : OauthTokenResult
    data class Rejected(val code: String, val terminal: Boolean) : OauthTokenResult
    data class Failed(val error: ModelError) : OauthTokenResult
}

/**
 * 1 つのアカウント (現在は xAI) でログインすると、ファイル全体が暗号化され、API キーの形式で保存されます。
 * [accessToken] はモデルを呼び出すために使用されるアクセス トークンであり、[refreshToken] はモデルを新しいものに置き換えるために使用されます。
 * ログアウトすると、トークンはクリアされ、アカウント名は他の人が閲覧できるようになります。
 */
@Serializable
data class AccountSession(
    val provider: String,
    val accountId: String = "",
    val label: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val apiBase: String = "",
    val clientId: String = "",
    val issuer: String = "",
    /** ログイン時に、ホストのトークン アドレスが検出ドキュメントから読み取られて検証されました。リフレッシュに使ってください。古いバージョンは保存されず、更新時に発行者に従ってプッシュされます。 */
    val tokenUrl: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    fun signedOut() = copy(accessToken = "", refreshToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AccountSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * を呼び出すときに使用されるアクセス トークン。有効期限が近づいている場合、または以前のものが拒否された場合は、[refresh] に連絡して新しいものと交換してください。同時に交換できるのは 1 つだけです。
 * 交換されるトークンは最初に [save] で、その後使用されます。 [save] は交換前に同時にトークンを受信し、ストレージはこの期間中にユーザーがログアウトしたか再ログインしたかを判断します (この場合、書き戻されるべきではありません)。認証が利用できなくなった場合は、トークンをクリアして [ModelError.SignInExpired] をスローします。ネットワークまたはサーバーのエラーが発生した場合は、通常どおりトークンを破棄し、トークンを保管してください。
 */
class AccountTokens(
    /** ログインが失敗した場合、どの会社にログインするか (インターフェイス上の名前) がユーザーに通知されます。 */
    private val account: String,
    private val refresh: suspend (AccountSession) -> OauthTokenResult,
    private val load: () -> AccountSession?,
    private val save: (before: AccountSession, after: AccountSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired(account)
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        when (val r = refresh(s)) {
            is OauthTokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                    apiBase = t.apiBase ?: s.apiBase,
                )
                save(s, next)
                next.accessToken
            }
            is OauthTokenResult.Rejected -> {
                if (r.terminal) {
                    save(s, s.signedOut())
                    throw ModelError.SignInExpired(account)
                }
                throw ModelError.Auth()
            }
            is OauthTokenResult.Failed -> throw r.error
        }
    }
}
