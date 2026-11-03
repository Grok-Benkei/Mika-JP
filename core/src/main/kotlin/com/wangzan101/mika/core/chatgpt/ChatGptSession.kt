package com.wangzan101.mika.core.chatgpt

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.oauth.LoopbackCallback
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ChatGPT アカウントにログインします: [clientId]、検証済みの ID ([sub]、[email])、および登録時に付与されたトークン。
 * 全体が暗号化され、API キーの形式で保存されます。ログアウト後、トークンはクリアされ ([signedIn] は false)、登録と ID は保持され、再度ログインするときに同じ client_id が使用されます。
 */
@Serializable
data class ChatGptSession(
    val clientId: String,
    val sub: String,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** ユーザーは、パッケージ クォータを使用して呼び出すことに同意しました。 */
    val planUsage: Boolean get() = ChatGptAuth.PLAN_SCOPE in scopes

    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): ChatGptSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * を呼び出すときに使用されるアクセス トークン: 期限切れが近づいている場合 ([refreshAheadMs] 未満)、または拒否された場合 ([BearerToken.get] 以降)、リフレッシュ トークンを使用して新しいトークンと交換します。
 * 新しいリフレッシュ トークンはアクセス トークン ([save]) とともに保存されます。同時に交換されるのは 1 つだけです (リフレッシュ トークンは交換されると無効になり、同時交換は互いにオーバーライドされます)。
 * リフレッシュ トークンは使用できなくなりました。トークンをクリアしてスローします。 [ModelError.SignInExpired] (再ログインが必要です)。ネットワークまたはサーバーのエラーが発生した場合は、通常どおり破棄し、トークンを保管してください。
 */
class ChatGptTokens(
    private val auth: ChatGptAuth,
    private val load: () -> ChatGptSession?,
    private val save: (ChatGptSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ModelError.SignInExpired()
        }
        when (val r = auth.refresh(s.clientId, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    idToken = t.idToken ?: s.idToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                )
                save(next)
                if (!next.planUsage) throw ModelError.ChatGptNotEligible()
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired()
                }
                // クライアント構成が等しくありません: 再試行は役に立ちませんが、トークンが無効であるという意味ではありません。不明瞭です
                throw ModelError.Auth()
            }
            is TokenResult.Failed -> throw r.error
        }
    }
}

/**
 * ログイン プロセスを実行します。ローカル コールバックを開く → [openBrowser] 認証ページを開く → コールバックを待つ → チェック → トークンの変更 → ID トークンの検証 → パッケージ クォータが付与されているかどうかを確認します。
 * は、結果の Web ページをブラウザーに返す前に、次のステップ (最大 [finishTimeoutMs]) をコールバックします: [page]。 null を受け取った場合は成功を意味し、それ以外の場合は失敗の原因となります。
 * [clientId] は以前に登録されています (初回は null で、登録成功後に [onRegistered] によって引き渡され、トークンを変更する前に保存する必要があります)。
 * [previous] は最後のログインです (ID トークンと電子メールを取得するために使用され、アカウントを選択する必要がなくなります)。失敗した場合は、[SignInException] をスローします。
 */
suspend fun signInWithChatGpt(
    auth: ChatGptAuth,
    hostId: String,
    clientId: String?,
    previous: ChatGptSession?,
    openBrowser: (String) -> Unit,
    onRegistered: (String) -> Unit,
    page: (SignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
    now: () -> Long = System::currentTimeMillis,
): ChatGptSession {
    var outcome: Result<ChatGptSession>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        val hint = previous?.takeIf { it.signedIn && it.clientId == clientId }
        openBrowser(auth.authorizeUrl(attempt, clientId, hostId, hint?.idToken, previous?.email?.takeIf { clientId != null }))
        cb.await(timeoutMs, { it["state"] == attempt.state }) { params ->
            val r = finishSignIn(finishTimeoutMs) { completeSignIn(auth, params, attempt, clientId, onRegistered, now) }
            outcome = r
            page(r.exceptionOrNull() as SignInException?)
        } ?: throw SignInException(SignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}

/** コールバック完了後の数ステップ、最大でも [timeoutMs]: タイムアウトは接続されたとみなされません。他の例外は [SignInException] として分類されます。キャンセルされた場合は、通常どおり破棄されます。 */
private suspend fun finishSignIn(timeoutMs: Long, block: suspend () -> ChatGptSession): Result<ChatGptSession> = try {
    Result.success(withTimeout(timeoutMs) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(SignInException(SignInFailure.Network, "no answer in ${timeoutMs / 1000}s", e))
} catch (e: CancellationException) {
    throw e
} catch (e: SignInException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(SignInException(SignInFailure.Network, e.message.orEmpty(), e))
}

private suspend fun completeSignIn(
    auth: ChatGptAuth,
    params: Map<String, String>,
    attempt: SignInAttempt,
    clientId: String?,
    onRegistered: (String) -> Unit,
    now: () -> Long,
): ChatGptSession {
    val (code, issued) = auth.readCallback(params, attempt, clientId)
    if (issued != clientId) onRegistered(issued)
    val tokens = when (val r = auth.exchange(issued, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw ChatGptAuth.rejected(r)
        is TokenResult.Failed -> throw SignInException(SignInFailure.Network, r.error.message.orEmpty(), r.error)
    }
    val idToken = tokens.idToken ?: throw SignInException(SignInFailure.IdToken, "no id_token")
    val who = auth.verifyIdToken(idToken, issued, attempt.nonce)
    if (ChatGptAuth.PLAN_SCOPE !in tokens.scopes) throw SignInException(SignInFailure.NoPlanScope, "scope", code = "no_plan_scope")
    return ChatGptSession(
        clientId = issued, sub = who.sub, email = who.email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(), idToken = idToken,
        expiresAtMs = now() + tokens.expiresInSec * 1000, scopes = tokens.scopes,
    )
}
