package com.wangzan101.mika.settings

import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.core.chatgpt.SignInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 最新のログインが失敗しました。時刻、原因の種類、サーバー エラー コード、および短い説明 (感度が解除されています) の診断情報のみが提供されます。 */
data class ChatGptSignInFailureRecord(val atMs: Long, val kind: String, val code: String?, val detail: String)

/** 今回ログインする会社はどこですか？他の人のログインが ChatGPT として表示されるのを避けるために、フロントエンド サービスとインターフェイスを再構築するときにこれを利用してください。 */
enum class SignInTarget { None, ChatGpt, Xai, OpenRouter }

/** デバイス コード ログインには、ユーザー コード、確認ページ、有効期限が表示されます。デバイスコード自体はここにはありません。 */
data class DevicePrompt(val userCode: String, val openUrl: String, val expiresAtMs: Long = 0)

/**
 * アカウント ログインの結果 (xAI、OpenRouter): 成功は保存されたサービス プロバイダー ID、失敗はユーザーに表示される理由です。
 * は、インターフェイス ステータスの代わりに [ChatGptSignIns] に配置されます。インターフェイスはログイン中に再構築される可能性があり、結果はその時点で存在していたインターフェイスに渡されます。
 */
data class AccountOutcome(val target: SignInTarget, val providerId: String? = null, val failure: String? = null)

/**
 * アカウント (ChatGPT、xAI、OpenRouter) でログインするプロセス中のもの。ログインするには、ユーザーが外部ブラウザにパスワードを入力し、2 段階認証に合格する必要があります。この間、ミカはバックグラウンドで動作します。
 * は、アプリ スコープ [scope] で実行されます (インターフェイスは再構築され、継続的にバックグラウンドに後退されます)。最初に、プロセスをサポートするために [keepAlive] が呼び出されます (フォアグラウンド サービス、
 * プロセスがフリーズしてローカル コールバックに応答できなくなるのを防ぎます)。立ち上がらない場合は、一文を思い出して、通常どおりログインしてください。フロント デスク サービスは [running] を調べ、false に戻るとサービスを停止します。
 * したがって、成功、失敗、キャンセル、タイムアウトで停止します。同時にログインできるのは 1 つだけです。 [cancel] はあきらめます (通知の「キャンセル」もここに表示されます)。
 */
class ChatGptSignIns(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** が最後に失敗しました ([recordFailure] を参照)。記憶の中だけで。 */
    @Volatile var lastFailure: ChatGptSignInFailureRecord? = null
        private set

    /** 他のアカウント (xAI、OpenRouter) での最新のログインが失敗しました。それはまた、鈍感化されたことを意味します。 */
    @Volatile var lastAccountFailure: ChatGptSignInFailureRecord? = null
        private set

    /** が取り組んでいるのはどれですか。ログイン時は[SignInTarget.None]ではありません。 */
    @Volatile var target: SignInTarget = SignInTarget.None
        private set

    /** フロントデスク通知のテキスト。空の場合は、ChatGPT の 2 つの文を使用します。 */
    @Volatile var noticeTitle: String? = null
        private set

    @Volatile var noticeBody: String? = null
        private set

    private val _device = MutableStateFlow<DevicePrompt?>(null)
    /** デバイス コード ログインにユーザー コードが表示されます。ログインが完了するとクリアされます。インターフェイスが再構築された後も表示され続けます。 */
    val device: StateFlow<DevicePrompt?> = _device.asStateFlow()
    val devicePrompt: DevicePrompt? get() = _device.value

    fun showDevice(prompt: DevicePrompt) {
        _device.value = prompt
    }

    private val _outcome = MutableStateFlow<AccountOutcome?>(null)
    /** 最新のアカウント ログインの結果は、インターフェイスに [consumeOutcome] として表示されます。 */
    val outcome: StateFlow<AccountOutcome?> = _outcome.asStateFlow()

    fun finishAccount(o: AccountOutcome) {
        _outcome.value = o
    }

    fun consumeOutcome(o: AccountOutcome) {
        _outcome.compareAndSet(o, null)
    }

    /** はログインを開始し、[block] を実行します。実行中にすでに開くことができず、false を返します。 */
    fun start(
        target: SignInTarget = SignInTarget.ChatGpt,
        noticeTitle: String? = null,
        noticeBody: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean = synchronized(this) {
        if (_running.value) return false
        this.target = target
        this.noticeTitle = noticeTitle
        this.noticeBody = noticeBody
        _device.value = null
        _outcome.value = null
        _running.value = true
        runCatching(keepAlive).onFailure { log("sign-in keep-alive could not start", it) }
        // アプリのスコープには例外処理はありません。リークされた例外は、プロセスのクラッシュを防ぐためにのみ記録されます。
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sign-in ended with an unexpected error", e)
            }
        }
        job = j
        j.invokeOnCompletion {
            synchronized(this) {
                if (job === j) {
                    job = null
                    _running.value = false
                    this.target = SignInTarget.None
                    _device.value = null
                    this.noticeTitle = null
                    this.noticeBody = null
                }
            }
        }
        true
    }

    fun cancel() {
        synchronized(this) { job }?.cancel()
    }

    fun recordFailure(e: SignInException) {
        val detail = Diagnostics.sanitize(e.message.orEmpty()).take(MAX_DETAIL)
        lastFailure = ChatGptSignInFailureRecord(now(), e.reason.name, e.code, detail)
    }

    /** サブスクリプション アカウントのログインに失敗しました。カテゴリとエラーコードを書き留めて、再度感度解除を行ってください。 */
    fun recordAccountFailure(provider: String, reason: String, code: String?) {
        val detail = Diagnostics.sanitize(listOfNotNull(reason, code).joinToString(": ")).take(MAX_DETAIL)
        lastAccountFailure = ChatGptSignInFailureRecord(now(), provider, code, detail)
    }

    private companion object {
        const val MAX_DETAIL = 160
    }
}
