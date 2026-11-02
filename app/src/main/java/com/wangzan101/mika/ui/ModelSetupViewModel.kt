package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProbeResult
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.AccountOutcome
import com.wangzan101.mika.settings.DevicePrompt
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.Transcriber
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.SignInException
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.oauth.AccountSignInException
import com.wangzan101.mika.core.oauth.AccountSignInFailure
import com.wangzan101.mika.core.oauth.DeviceChallenge
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.core.oauth.XaiAuth
import com.wangzan101.mika.core.oauth.signInWithOpenRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** モデルの進行状況を一覧表示します (「テストと保存」またはサービス プロバイダーの更新)。 */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** ChatGPT を使用してログインしたアカウント: [email] は検証済みの ID トークンから取得されます (空の場合があります)。ログアウト後は、[signedIn] が false になります。 */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/**
 * ChatGPT に加えて、ログインにアカウントを使用する企業がいくつかあります。xAI / Grok はデバイス コード (ブラウザーでショート コードを確認) を使用し、OpenRouter は公式の PKCE 認証 (キーを変更するためのローカル コールバック) を使用します。
 * ChatGPT 用の別のログイン セット ([ModelSetupViewModel.signInWithChatGpt] を参照) があり、これはインターフェイス上でそれらと並んでいます。
 */
enum class AccountProvider(val target: SignInTarget) {
    Xai(SignInTarget.Xai), OpenRouter(SignInTarget.OpenRouter);

    companion object {
        fun of(target: SignInTarget): AccountProvider? = entries.firstOrNull { it.target == target }
    }
}

/** は 1 つのアカウントでログインしているアカウントです。 [label] は他の人に表示されるアカウント名です (電子メールなど、空の場合もあります)。ログアウト後、[signedIn] は false になります。そのようなプロバイダーがない場合、これは null です。 */
data class SubscriptionAccount(val label: String, val signedIn: Boolean)

/**
 * アカウントのログインが進行中か、失敗しました。ログインがない場合、または失敗した場合、[provider] は null になります。
 * はログイン時のデバイス コード、[userCode] はユーザーに表示されるショート コード、[openUrl] はホストに対してチェックされた検証ページ、[expiresAtMs] はショート コードの有効期限が切れる時刻です。
 */
data class AccountSignInUi(
    val provider: AccountProvider? = null,
    val running: Boolean = false,
    val userCode: String = "",
    val openUrl: String = "",
    val expiresAtMs: Long = 0,
    val failure: String? = null,
)

/**
 * アカウントでログインするために必要なシステム機能: ブラウザーで認証ページを開き、ログイン後にミカに戻り、ローカル コールバックの受信後にブラウザーに Web ページが表示されます。
 * インターフェイスが指定されています (コンテキストが必要です)。テストで与えられない場合はログインできません。
 */
interface SignInBrowser {
    fun open(url: String)
    fun returnToApp()
    /** コールバックの処理後にブラウザに返される Web ページ: [failure] null の場合はログインが成功したことを意味し、それ以外の場合は失敗の理由です (インターフェイス上と同じ文)。 */
    fun page(failure: String?): String
}

/**
 * 「サービスプロバイダーの追加」フォーム。 [noList] が true の場合、モデルは前回のテストでリストされませんでした。最初に [model] (手動で入力されたモデル名) を入力し、それを使用して再度テストするときに呼び出しを試みる必要があります。
 * [manual] ユーザーが [モデル名を手動で入力] をクリックしたことは事実です (または、エラー後に列モデルが自動的に展開されました)。いつでも入力できます。記入したら、それを使って電話してみます。リストはありますか？
 * は記載されているかどうかは関係ありません。 [name] は、カスタマイズされたサービス プロバイダーの名前です (カスタマイズの場合のみ必須、オプション。[providerName] を参照)。
 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    val test: ListState = ListState.Idle,
    val model: String = "",
    val noList: Boolean = false,
    val name: String = "",
    val manual: Boolean = false,
) {
    /** には「モデル名」列が表示されます。モデルがリストされていないか、ユーザーが手動で入力する必要があります。 */
    val showModel: Boolean get() = noList || manual

    /** モデル名を入力します。その列が表示されていない場合はカウントされません。 */
    val typedModel: String get() = if (showModel) model.trim() else ""

    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    /** は、ユーザー自身のマシン上で実行されるサービスです ([ProviderPreset.selfHosted] を参照)。アドレスにはマシンのデフォルト ポートが事前に入力されており、そのマシンに変更できる必要があります。 */
    val isSelfHosted: Boolean get() = presetFor(preset).selfHosted

    /** アドレスはユーザーが入力または変更する必要があります。カスタマイズされ、自分で実行されるサービスです。 */
    val urlEditable: Boolean get() = isCustom || isSelfHosted

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            else -> null
        }

    /** プレーン テキスト http は、このマシンまたは LAN のアドレスではありません。注意、ブロックしないでください ([LocalHosts] を参照)。 */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** 実行するサービスとカスタマイズされた OpenAI 互換インターフェイス (主にローカル サービス) のキーを入力する必要はありませんが、残りは必須です。 */
    val keyRequired: Boolean get() = !isSelfHosted && !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    val canTest: Boolean get() =
        urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running && (!noList || model.isNotBlank())

    /** はテストせずに保存できます。アドレス形式は正しく、キーはそこにあるはずです。 [canTest] とは異なり、テストの進捗状況は確認されず、モデル名は必要ありません。 */
    val canSaveUntested: Boolean get() = urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running

    /** は、特定のサービス プロバイダーの公式アドレスを入力するようにカスタマイズされています (ホスト名は一致します。[ProviderPresets.forHost] を参照)。代わりにそのデフォルトを使用することをお勧めします。 */
    val suggestedPreset: ProviderPreset? get() = if (isCustom && urlProblem == null) ProviderPresets.forHost(baseUrl) else null

    /** のアドレスは [suggestedPreset] のアドレスですが、選択されたインターフェイス タイプはそれとは異なります。この方法では間違いなく機能しません。 */
    val presetKindMismatch: Boolean
        get() = suggestedPreset?.let { s -> ProviderPresets.forBaseUrl(baseUrl.trim()) == s && kind != s.kind } == true

    /** モデル名の例で、どのタイプのインターフェースを押すか、アドレスが特定のプリセットと一致する場合はそのプリセットを押し、それ以外の場合は選択したインターフェースのタイプを押します。 */
    val exampleKind: ProviderConfig.Kind get() = suggestedPreset?.kind ?: kind

    /** 名前が指定されていない場合に使用される名前、つまりアドレスのホスト名をカスタマイズします (アドレスが特定の会社によって事前に設定されている場合は、デフォルトの名前を使用します)。 */
    val defaultName: String get() = ProviderConnection.nameForUrl(baseUrl)

    /**
     * サービスプロバイダーとして保存するときに使用される名前: デフォルトにはデフォルト名を使用し、カスタマイズには入力された名前を使用し、入力されていない場合は [defaultName] を使用します。
     * サービス プロバイダーは、インターフェイスの種類、アドレス、名前に従ってグループ化されます。同じ住所に異なる名前がある場合、2 つの会社が存在します (たとえば、同じ乗換駅の 2 つのキー)。
     */
    val providerName: String get() = if (isCustom) name.trim().ifEmpty { defaultName } else presetFor(preset).displayLabel

    /** この会社は、セッション番号のリクエスト ヘッダー名を入力する必要があります。選択したプリセットが宣言されている場合に使用します。カスタマイズされたアドレスが特定のプリセットと一致する場合は、そのプリセットを使用します。 */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    /** 自社モデルの名前にある単語 ([ProviderPreset.familyTokens] を参照): 選択したプリセットが指定されている場合はそれを使用し、そうでない場合はアドレスが一致するプリセットを使用します。 */
    val familyTokens: List<String> get() = presetFor(preset).familyTokens.ifEmpty { ModelOptions.familyOf(baseUrl) }

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * 構成モデル (開始時) と設定 › モデルによって共有されるすべての状態: 接続されたサービス プロバイダー、サービス プロバイダー フォームの追加、3 つの目的。
 * [providers]、[configs]、および [roles] は [ProviderStore] のスナップショットであり、[keyed] はキーが保存されているサービス プロバイダー ID です。
 * [listing] は、(サービス プロバイダー ID に基づく) リストを更新している各サービス プロバイダーの進行状況です。
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val keyed: Set<String> = emptySet(),
    val listing: Map<String, ListState> = emptyMap(),
    /** ChatGPT サービスプロバイダーのアカウント。そのようなプロバイダーがない場合、null になります。 */
    val chatGptAccount: ChatGptAccount? = null,
    /** ChatGPT を使用したログインの進行状況。 */
    val chatGptSignIn: ListState = ListState.Idle,
    /** 初めてログインに成功したら、「ChatGPT パッケージを使用しています」と述べ、「了解」をクリックして閉じてください。 */
    val chatGptWelcome: Boolean = false,
    /** ログアウト時に、ChatGPT はログアウトを確認しませんでした (ローカル トークンはクリアされました)。 */
    val chatGptRevokeUnconfirmed: Boolean = false,
    /** 接続された Grok サブスクリプション。そのようなホームがない場合は null。 1 つの */
    val xaiAccount: SubscriptionAccount? = null,
    /** には、OpenRouter アカウントを使用して接続することが許可されています。そうでない場合は null です。 */
    val openRouterAccount: SubscriptionAccount? = null,
    /** xAI、OpenRouter ログインの進行状況。 */
    val accountSignIn: AccountSignInUi = AccountSignInUi(),
    /** アカウントで正常にログインしたサービス プロバイダー ID: インターフェイスで詳細ページを開き (アカウントとモデルを確認し、メイン モデルを設定します)、開いた後、ページをクリアします。 */
    val signedInProvider: String? = null,
    /**
     * サービスプロバイダーを最初に保存するときの手順: 最初のトライアルモデルを呼び出そうとしたときに失敗しました。メインモデルにどれが使用されたか、どれもパスしませんでした。ユーザーは選択する必要があります。
     * ([ModelSetupViewModel.testAndSave] を参照);または、この会社はテストをスキップして保存しました ([ModelSetupViewModel.saveWithoutTest] を参照)。
     * は、再度テストしてメインモデルを選択するとクリアされます。
     */
    val probeNote: String? = null,
    /**
     * は保存されたばかりのサービス プロバイダー ID であり、モデルはまだ渡されていません。その中でメイン モデルを選択するときは、最初にそれを呼び出してみてください ([probeBeforePick] を参照)。選択後はクリアしてください。
     * 2 つの原因: 試行されたモデルがいずれも合格しなかったか、ユーザーがテストをスキップして直接保存しました。
     */
    val pickToProbe: String? = null,
    /** 選択ページでモデル名を手動で入力した後の試行通話の進行状況 (サービスプロバイダー ID に基づく)。 */
    val manualPick: Map<String, ListState> = emptyMap(),
    /** サービス プロバイダー ページの「再テスト」の進行状況 (サービス プロバイダー ID 別)。 */
    val retest: Map<String, ListState> = emptyMap(),
    /** Speech to Text がオプションになり、この電話機には認識機能 (使用状況カードと選択ページ用) が組み込まれています。 */
    val speech: SpeechChoice = SpeechChoice.Auto,
    val systemSpeech: SystemCapability = SystemCapability(available = false, onDevice = false),
    /** 短い録音部分を転送しようとしているサービスプロバイダーの ID。利用できない場合は null です。 */
    val speechTesting: String? = null,
) {
    /** サービス プロバイダーがまだ存在しない場合、フォームは常に展開されます。 */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /**
     * [providerId] で [role] を選択するには、最初に呼び出してみる必要があります ([ModelSetupViewModel.pickManual] に移動)。保存したばかりで、どのモデルも合格しませんでした。
     * またはテストをスキップして、まだテストされていないもの ([Provider.savedUntested]、アプリを再度開いた後にカウントされます) を保存して、メイン モデルを選択します。
     */
    fun probeBeforePick(role: ModelRole, providerId: String): Boolean = role == ModelRole.Main &&
        (providerId == pickToProbe || providers.firstOrNull { it.id == providerId }?.savedUntested == true)

    /** メインモデルを選択したら、「次のステップ」に進むことができます。 */
    val canNext: Boolean get() = roles.main != null

    /** この目的で使用される実際の構成 (「メイン モデルを使用する」場合は、メイン モデル)。 */
    fun configFor(role: ModelRole): ProviderConfig? = roles.resolve(role)?.let { id -> configs.firstOrNull { it.id == id } }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** 1 つのサービス プロバイダーですべてのモデルが利用可能 (記憶されたリストと既存の構成)。 */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** サービス プロバイダーのすべてのモデルが最初にリストされ、残りは元の順序のままです。 */
    fun sortedOptions(p: Provider): List<ModelOption> = options(p).sortedBy { if (rolesOf(p.id, it.id).isEmpty()) 1 else 0 }

    /** このモデルの実際の使用方法（「メインモデルを使用する」に続くものを含む）は、メインモデル、写真を見る、軽量の順です。 */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** は実際にこのサービス プロバイダーを使用しています。 */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /**
     * は、リストを提供しないサービス プロバイダーによって使用されますが、渡されたモデル名を呼び出そうとはしません ([Provider.verifiedModels] を参照)。これらは手動で入力されるため、正しく入力されないとタスクは失敗します。
     * にはサービスプロバイダーのリストがあり、モデルはリストから選択され、カウントされません。
     */
    fun unverified(p: Provider): List<String> =
        roles.referenced.mapNotNull { id -> configs.firstOrNull { it.id == id && it.providerId == p.id }?.model }
            .distinct().filter(p::untested)

    /**
     * この会社はテストをスキップして直接保存しました ([ModelSetupViewModel.saveWithoutTest] を参照): モデルはリストされておらず、どのモデルもテストに合格していません。
     * サービス プロバイダーのページには、一般的な「モデル リストが提供されていない」ではなく、「まだテストされていない」と記載されています。後者はこれについて間違っています。
     * の後に 1 回リストされたモデルまたは 1 回合格したモデルはカウントされません ([ProviderStore.rememberModels]、[ProviderStore.markVerified] を参照)。
     */
    fun untested(p: Provider): Boolean = p.savedUntested

    /** というアカウントでログインしていた企業がログアウトしました（ChatGPTとxAIのトークンがクリアされ、OpenRouter Keyが削除されました）。 */
    fun signedOut(p: Provider): Boolean = when {
        p.kind == ProviderConfig.Kind.ChatGpt -> chatGptAccount?.signedIn != true
        p.kind == ProviderConfig.Kind.Xai -> xaiAccount?.signedIn != true
        p.signIn != null -> p.id !in keyed
        else -> false
    }

    /** この目的は、このモデルを使用するかどうかを明確に選択します (「メインモデルを使用する」ことはカウントされません)。 */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}

/**
 * は、モデルのステータスとアクションを構成します。サービスプロバイダーの追加: [デフォルト] → [キーを入力] → [テストして保存] を選択します (モデルをリストし、メインモデルとなる人に一文で返信するように依頼します。この会社とリストのみが保存されます。保存されない理由は言えません)。
 * サービスプロバイダー、アドレス、またはキーが変更されたため、進行中のテストは無効になります。目的: 接続されているすべてのサービス プロバイダーによって記憶されているモデルから選択すると、選択が有効になります。
 * は写真を見て「メインモデルを使う」「使わない」が選べます。軽量の場合は「メインモデルを使用する」ことができます。最初のモデル (メイン モデルがまだない) に接続すると、そのモデルの 1 つがメイン モデルとして自動的に選択されます。
 * 初めての開始とセットアップ › モデル共有。
 */
class ModelSetupViewModel(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val browser: SignInBrowser? = null,
    /** はどこでログインして実行しますか: インターフェイスはアプリ用です (バックグラウンドに戻り、インターフェイスは継続的に再構築され、この期間中フロントエンド サービスがオンになります)。テストでは [scope] が使用されます。 */
    private val signIns: ChatGptSignIns = ChatGptSignIns(scope),
    /** 音声テキスト変換オプション。テストおよび起動プロセスがアップロードされていない場合、カードは表示されません。 */
    private val speech: SpeechSettings? = null,
    private val systemSpeech: () -> SystemCapability = { SystemCapability(available = false, onDevice = false) },
    /** は、音声モデルを選択するときに使用されるトランスクライバーです。これは、Press and Talk と同じ構築方法を使用します (クライアント識別子ヘッダーを使用)。 */
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, store.clientHeaders(p)) },
) {
    private val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    private val _speechProbe = MutableStateFlow<Map<String, String>>(emptyMap())

    /** サービス プロバイダー ID -> 音声モデルの選択時に試用転送が失敗した理由 (既存の用語を使用)。選択または再選択するとクリアされます。 */
    val speechProbe: StateFlow<Map<String, String>> = _speechProbe.asStateFlow()

    private var testJob: Job? = null
    private val refreshJobs = mutableMapOf<String, Job>()

    init {
        // は、ストレージが他の場所 (開発者向けオプションなど) に変更されると更新されます。
        scope.launch { merge(store.providers, store.configs, store.roles).collect { sync() } }
        speech?.let { s -> scope.launch { s.choice.collect { sync() } } }
        // ログインが別の場所で終了した (通知でキャンセルされた) か、インターフェースが再構築される前に開始されました: 進行状況に従ってください
        scope.launch {
            signIns.running.collect { running ->
                val prompt = signIns.devicePrompt
                val target = signIns.target
                _state.update {
                    when {
                        running && target == SignInTarget.ChatGpt && it.chatGptSignIn == ListState.Idle ->
                            it.copy(chatGptSignIn = ListState.Running)
                        !running && it.chatGptSignIn == ListState.Running -> snapshot(it.copy(chatGptSignIn = ListState.Idle))
                        running && AccountProvider.of(target) != null -> it.copy(
                            accountSignIn = it.accountSignIn.copy(
                                provider = AccountProvider.of(target),
                                running = true,
                                userCode = prompt?.userCode ?: it.accountSignIn.userCode,
                                openUrl = prompt?.openUrl ?: it.accountSignIn.openUrl,
                                expiresAtMs = prompt?.expiresAtMs ?: it.accountSignIn.expiresAtMs,
                            ),
                        )
                        !running && it.accountSignIn.running -> snapshot(it.copy(accountSignIn = AccountSignInUi()))
                        else -> it
                    }
                }
            }
        }
        // デバイス コードのショート コードは、インターフェイスが再構築された後に提供される可能性があります。
        scope.launch {
            signIns.device.collect { prompt ->
                if (prompt == null) return@collect
                val provider = AccountProvider.of(signIns.target) ?: return@collect
                _state.update {
                    it.copy(accountSignIn = it.accountSignIn.copy(
                        provider = provider, running = true,
                        userCode = prompt.userCode, openUrl = prompt.openUrl, expiresAtMs = prompt.expiresAtMs,
                    ))
                }
            }
        }
        // アカウントのログイン結果は現在のインターフェースに引き継がれます (ログインは再構築前のインターフェースから開始される可能性があります)
        scope.launch {
            signIns.outcome.collect { o ->
                if (o == null) return@collect
                val provider = AccountProvider.of(o.target) ?: return@collect
                _state.update {
                    if (o.providerId != null) snapshot(it.copy(accountSignIn = AccountSignInUi(), formOpen = false, signedInProvider = o.providerId))
                    else it.copy(accountSignIn = AccountSignInUi(provider = provider, failure = o.failure))
                }
                signIns.consumeOutcome(o)
            }
        }
    }

    private fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
            xaiAccount = store.xaiProvider()?.let { p ->
                store.accountSession(p.id).let { SubscriptionAccount(it?.label.orEmpty(), it?.signedIn == true) }
            },
            openRouterAccount = store.openRouterAccount()?.let { p -> SubscriptionAccount("", store.hasKey(p.id)) },
            speech = speech?.choice?.value ?: SpeechChoice.Auto,
            systemSpeech = systemSpeech(),
        )
    }

    private fun sync() = _state.update(::snapshot)

    // ---------- サービスプロバイダーの追加 ----------

    fun openForm() = _state.update { it.copy(formOpen = true) }

    fun closeForm() {
        testJob?.cancel()
        _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
    }

    fun selectPreset(label: String) {
        val p = AddForm.presetFor(label)
        if (p.label == _state.value.form.preset) return
        val key = _state.value.form.apiKey
        edit { AddForm.of(p).copy(apiKey = key) }
    }

    // インターフェースのタイプまたはアドレスが変更されました。前回「リストに記載されていないモデル」はカウントされないため、再度リストに記載する必要があります。
    fun setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }
    fun setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }
    fun setApiKey(key: String) = edit { it.copy(apiKey = key) }
    fun setModel(model: String) = edit { it.copy(model = model) }

    /** 「モデル名を手動で入力します」: 開いたときにモデル名列を表示します。閉じているときはこれをクリアします (モデルがリストされていないときは、この列は常に表示されます)。 */
    fun setManual(on: Boolean) = edit { it.copy(manual = on, model = if (on || it.noList) it.model else "") }
    // 文字（コードポイント）ごとにカットします。絵文字は半分にカットしません
    fun setName(name: String) = edit {
        val n = name.codePointCount(0, name.length)
        it.copy(name = if (n <= MAX_NAME) name else name.substring(0, name.offsetByCodePoints(0, MAX_NAME)))
    }

    /** 接続情報が変更されました: 進行中のテストと最後の失敗の理由が無効です。 */
    private fun edit(change: (AddForm) -> AddForm) {
        testJob?.cancel()
        _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
    }

    /**
     * テストと保存: モデルをリストし、[ModelOptions.candidates] のシーケンスを押して、モデルがツールを 1 回呼び出します ([probe] を参照)。成功した場合は、このサービス プロバイダーを保存します。
     * 渡されたものをメインモデルとして（メインモデルがまだない場合）、フォームをクリアして保管します。最初の試行が失敗した場合は、モデル自体に問題がありました (アカウントが開かれていないか、権限がありませんでした)。
     * (そのようなモデルは存在しないため、ツールを呼び出すことができません)、次のモデルを試してください。多くても [PROBE_CANDIDATES] を試してください: キーが正しいことが証明されました。
     * メイン モデルに合格し、失敗したモデルのうちどれが最初に試行されたかを示しました。失敗した場合は、サービスプロバイダーと未決定の主要モデルを保存し、理由を説明してユーザーにリストから選択するよう求めます。
     * ([ModelSetupUi.probeNote]、[ModelSetupUi.pickToProbe]、選択するときに最初に呼び出してみてください)。
     * 最初の接続は他の理由 (キー、ネットワーク、アドレス、タイムアウト、サーバー エラー) で失敗し、その理由は接続が存在しないためであると述べました。
     *
     * モデルがリストにありません (リスト インターフェイスが存在しないか、リストが空です): この会社は本当にリストを提供していないか、アドレスとインターフェイスのタイプが間違って入力されている可能性があり、リストを見ただけでは違いがわかりません。
     * したがって、直接保存されません。ユーザーはモデル名 ([AddForm.noList]) を入力し、テスト時にそれを使用して呼び出しを試みる必要があります。リストを提供しないサービス プロバイダーとして保存されます。
     * 理由はわかりません、存在しません。
     *
     * はモデル名を手動で入力しました ([AddForm.typedModel]、いつでも「モデル名を手動で入力」をクリックできます)。これを試してみてください。リストにリストされていても、リストされていない場合でも、リストに含まれていても同じです。
     * リストに他のエラーがある場合でも (キーが間違っている、サーバーにエラーがある、応答が JSON ではない...)、試行呼び出しの結論が最終決定権を持ちます。キーとアドレスが正しくないため、試行呼び出しも失敗します。
     * リストに入力していないときにリストに他のエラーがある場合は、その理由を伝え、モデル名の列を展開します。一部のサービスには、使いにくいリスト インターフェイスしかありません。モデル名を入力することで接続できます。
     */
    fun testAndSave() {
        val f = _state.value.form
        if (!f.canTest) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            val models = when (val r = withContext(io) { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> {
                    if (r.reason != ListFailure.NotSupported && typed.isEmpty()) {
                        _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message), manual = true)) }
                        return@launch
                    }
                    emptyList()
                }
            }
            if (models.isEmpty() && typed.isEmpty()) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
                return@launch
            }
            // モデル名を手動で入力します。これを試してみてください。リスト (存在する場合) はまだ保存されています。
            if (typed.isNotEmpty()) {
                val listed = models.firstOrNull { it.id == typed }
                val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
                val caps = listed?.let { ModelOptions.merge(listOf(it), base).firstOrNull()?.capabilities } ?: base
                val outcome = probeModel(
                    f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader, typed, listed?.endpoint, caps, f.exampleKind,
                    listMissing = models.isEmpty() && f.isCustom,
                )
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(outcome.message))) }
                    return@launch
                }
                val noTools = outcome is ProbeOutcome.NoToolCall
                withContext(io) {
                    val p = store.connect(
                        f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOf(typed),
                        typed = if (listed == null) setOf(typed) else emptySet(),
                    )
                    if (!noTools && store.roles.value.main == null) use(ModelRole.Main, p, typed, ModelOptions.ofProvider(p, store.list()))
                }
                val note = if (noTools) tr(R.string.model_probe_no_tools, typed) else null
                _state.update { snapshot(it.copy(form = AddForm.of(ProviderPresets.all.first()), formOpen = false, probeNote = note)) }
                return@launch
            }
            val probed = when (val r = probe(f, baseUrl, key.ifEmpty { null }, models)) {
                is ListProbe.Blocked -> {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message))) }
                    return@launch
                }
                is ListProbe.Done -> r
            }
            val noneUsable = probed.main == null && probed.rejected.isNotEmpty()
            val saved = withContext(io) {
                val p = store.connect(f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOfNotNull(probed.main))
                if (!noneUsable && store.roles.value.main == null) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (probed.main ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id)
                        ?.let { m -> use(ModelRole.Main, p, m, options) }
                }
                p
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = probeNote(probed), pickToProbe = saved.id.takeIf { noneUsable },
                ))
            }
        }
    }

    /**
     * はテストせず、直接保存します。入力されたインターフェイス タイプ、アドレス、キーに従ってサービス プロバイダーのみを保存し、モデルをリストしたり、呼び出しを試行したりしません ([AddForm.canSaveUntested] を参照)。
     * によって保存されたものには、[Provider.savedUntested] が含まれています。サービス プロバイダーのページには、まだテストされていないことが明確に記載されており、「再テスト」のままになっています。
     * はマスターモデルが決まっていない場合も [ModelSetupUi.pickToProbe] を設定します。その後、リストからマスター モデルの呼び出しが 1 回試行されます ([ModelSetupUi.probeBeforePick] を参照)。
     *
     * モデル名 ([AddForm.typedModel]) を手動で入力した場合、手動入力としてのみ記録されます。リストにない場合、またはモデルがこの会社にリストされない場合でも、失われることはなく、リストが更新されます。
     * テストされていないモデルは自動的にメイン モデルにはなりません。メイン モデルがない場合は、ユーザーがそれを選択する必要があります。選択するときは、最初に呼び出して、渡されてから使用するようにしてください。
     *
     * の list パラメーターは、null ではなく空のリスト (「リストされているがなし」) を提供します。このように、「モデルがリストされていない」は、[Provider.savedUntested] と同じです。
     * は 2 つの状況を明確に区別しています。実際にはリストがないか、今回はリストしませんでした。古い [testAndSave] は 1 行も移動しません。
     */
    fun saveWithoutTest() {
        val f = _state.value.form
        if (!f.canSaveUntested) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            val saved = withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key.ifEmpty { null }, emptyList(), f.sessionHeader, untested = true,
                ).also { p -> if (typed.isNotEmpty()) store.markTyped(p.id, typed) }
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = tr(R.string.model_saved_untested, f.providerName),
                    // メインモデルがない場合、その中のメインモデルを選択すると、最初にそれを呼び出そうとします。
                    pickToProbe = saved.id.takeIf { store.roles.value.main == null },
                ))
            }
        }
    }

    /**
     * の試行呼び出しの結論: 合格、[model] はツールを呼び出さず、失敗しました ([message] が他の人に見せる理由です)。
     * の障害がモデル自体の問題によるものである場合 (このアカウントは使用できない、このモデルがない、またはツールがサポートされていない)、[modelReason] の原因はショート障害であるため、モデルを変更するだけで十分な場合があります。
     * [notAvailable] は、アクティブ化されていない、または権限がないことを意味します。 [serverSide] は、サーバー エラー (5xx) であることを意味します。このモデルの上流がダウンしている可能性があります。[probe] を参照してください。
     */
    private sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(
            val message: String, val modelReason: String? = null,
            val notAvailable: Boolean = false, val serverSide: Boolean = false,
        ) : ProbeOutcome
    }

    /**
     * は最初に試行されて失敗したモデルです。[reason] は短いです。 [serverSide] は真のサーバー エラー (5xx) です。[probe] を参照してください。
     * [skipped] まったく試していない（インターフェイスには、このインターフェイス タイプを使用しないと記載されている）ため、コピーでは「試した」とは言えません。 リストがあるときに
     */
    private data class Rejected(
        val model: String, val reason: String, val notAvailable: Boolean, val serverSide: Boolean = false,
        val skipped: Boolean = false,
    )

    /** を呼び出そうとした結果: [Blocked] は、モデル以外の理由で失敗した最初のリストであり、存在しません。 [Done] [probe] を参照。 */
    private sealed interface ListProbe {
        data class Blocked(val message: String) : ListProbe
        /** [main] は合格したモデルです。 [rejected] は以前に試行されましたが失敗しました。すべて空であることは、試せるモデルがないことを意味します。 */
        data class Done(val main: String?, val rejected: List<Rejected>) : ListProbe
    }

    /**
     * モデルをリストした後、[ModelOptions.candidates] の順序を押して (そうでない場合は最初のものを使用します)、モデルがツールを 1 回呼び出します ([probeModel] を参照)。せいぜい試してみてください
     * [PROBE_CANDIDATES]: 合格した場合は停止します。失敗した場合は、モデル自体に問題があるため、次のモデルを試してください。最初のものは、[ListProbe.Blocked] として他の理由で失敗しました。
     * 以降、他の理由で失敗した場合は再試行されず (キーが正しいことが証明されている)、失敗した場合と同じように扱われます。リストに使用可能な項目がない場合は、合格とみなされ、試行は行われません。
     *
     * サーバー エラー (5xx) は、必ずしも会社全体が利用できないことを意味するわけではありません。集約サービス プロバイダーは多くのアップストリーム モデルを同じリストに入れており、アップストリームの 1 つがダウンした場合にこれが発生します。
     * したがって、5xx は最初にこのモデルを押して失敗したかどうかを確認し、次に次のモデルを試します。後で合格した場合は、そのモデルに問題があることを意味し、通常どおり保存します。
     * これはすべてのテストに当てはまるため、ファミリー全体が利用できず、存在しないと考えられます。 5xx に遭遇しただけでは、キーが正しいことが証明されていません。その後、他の理由で失敗した場合は、依然として存在しません。
     */
    private suspend fun probe(f: AddForm, baseUrl: String, key: String?, models: List<ListedModel>): ListProbe {
        // は、ChatGPT を使用してログインするときにこのフォームを通過する必要はありません (ログイン自体はチェックされています)。フォームには Key のインターフェイス タイプしか示されていないため、ここで再度ブロックします。
        if (f.kind == ProviderConfig.Kind.ChatGpt || f.kind == ProviderConfig.Kind.Xai) {
            return ListProbe.Done(null, emptyList())
        }
        val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
        val options = ModelOptions.merge(models, base)
        // インターフェイスでモデルが使用できるプロトコルを指定する場合、このインターフェイス タイプで使用できるプロトコルのみを試してください。一致しないプロトコルでの呼び出しは必然的に失敗します (たとえば、集約サービスは モデル
        // /messages が 1 位にランクされており、現在のインターフェイスは OpenAI と互換性があります)。 3 つすべてが壁にぶつかった場合、PROBE_TIMEOUT_MS × 3 分間無駄に待つことになります。
        // リストは、特定のモデルの別のインターフェイスを指定します ([ListedModel.endpoint] を参照)。呼び出しは、独自のプロトコルと、ここで選択されたインターフェイス タイプに従います。
        // は無関係であるため、フィルターで除外することはできません。プロトコルが不明な場合 (このフィールドが書き込まれていない場合)、通常どおり試してください。
        val mismatched = mutableListOf<String>()
        val usable = ModelOptions.candidates(options, f.familyTokens).filter { m ->
            val listed = models.firstOrNull { it.id == m }
            if (listed?.endpoint != null) return@filter true
            val protocols = listed?.protocols ?: return@filter true
            if (f.kind.protocol in protocols) true else { mismatched += m; false }
        }
        if (usable.isEmpty() && mismatched.isNotEmpty()) {
            val reason = tr(R.string.model_reason_wrong_protocol)
            return ListProbe.Done(null, mismatched.take(PROBE_CANDIDATES).map { Rejected(it, reason, notAvailable = false, skipped = true) })
        }
        val order = usable.ifEmpty { listOfNotNull(options.firstOrNull()?.id) }
        val rejected = mutableListOf<Rejected>()
        var firstServerError: String? = null
        for ((i, model) in order.take(PROBE_CANDIDATES).withIndex()) {
            val caps = options.first { it.id == model }.capabilities
            val endpoint = models.firstOrNull { it.id == model }?.endpoint
            when (val o = probeModel(f.kind, baseUrl, key, f.sessionHeader, model, endpoint, caps, f.exampleKind, listMissing = false)) {
                ProbeOutcome.Passed -> return ListProbe.Done(model, rejected)
                is ProbeOutcome.NoToolCall -> rejected += Rejected(model, tr(R.string.model_reason_no_tools), notAvailable = false)
                is ProbeOutcome.Failed -> when {
                    o.modelReason != null -> {
                        rejected += Rejected(model, o.modelReason, o.notAvailable, o.serverSide)
                        if (o.serverSide && firstServerError == null) firstServerError = o.message
                    }
                    // の前に 5xx しかない場合、キーが正しいことが証明されていません。キー、ネットワーク、アドレス、タイムアウトなどに遭遇しても、それらはまだ存在しません。
                    firstServerError != null || i == 0 -> return ListProbe.Blocked(o.message)
                    else -> {
                        rejected += Rejected(model, o.message, notAvailable = false)
                        break
                    }
                }
            }
        }
        // 私が試したものはすべてサーバー側のエラーでした。ほとんどは利用できず、保存されませんでした。
        if (rejected.isNotEmpty() && rejected.all { it.serverSide }) {
            return ListProbe.Blocked(firstServerError ?: rejected.first().reason)
        }
        return ListProbe.Done(null, rejected)
    }

    /**
     * サービス プロバイダーが他の人に示す文を保存します。最初のテストは失敗し、後のテストは合格し、どのテストが失敗したか、どれがメイン モデルに使用されたか。
     * は合格しませんでした。最初のものが失敗した理由と、何を試したかを教えて、ユーザーにリストから選択するよう依頼してください。成功した場合はすべて null になります。
     *
     * が失敗した場合、最後に「何をするか」は理由によって異なります。いずれも試行されておらず、その理由は、このインターフェイス タイプがそれらに到達できないことです ([Rejected.skipped] を参照)。
     * 変更する必要があるのは、インターフェースの種類、またはこのインターフェースの種類で使用できるモデルです。人々にそれを「アクティブ化」させることはできません。それはここでの問題ではありません。残りについては、アクティブになっているものを選択するように依頼してください。
     */
    private fun probeNote(r: ListProbe.Done): String? {
        if (r.rejected.isEmpty()) return null
        val names = r.rejected.joinToString(tr(R.string.list_sep)) { it.model }
        val first = r.rejected.first()
        if (r.main != null) return tr(R.string.model_probe_fallback, names, first.reason, r.main)
        val wrongProtocol = r.rejected.all { it.skipped }
        val head = if (wrongProtocol) {
            tr(R.string.model_probe_none_usable_protocol, first.model, first.reason)
        } else {
            tr(R.string.model_probe_none_usable, first.model, first.reason)
        }
        // どれも試していない（インターフェースが明示されていても、このインターフェースタイプは使用されません）: 理由が頭に明記されており、「試す」はもう書けません。
        if (r.rejected.size == 1 || wrongProtocol) return head
        val tried = if (r.rejected.all { it.notAvailable }) tr(R.string.model_probe_tried_unavailable, names) else tr(R.string.model_probe_tried, names)
        return "$head\n$tried"
    }

    /**
     * は、[model] にツールを 1 回呼び出して、実際のタスクと同じモデル構築パスに従うように要求します (リクエスト ヘッダーとセッション番号が同じです)。キーが間違っており、サービス プロバイダーに必要なリクエスト ヘッダーが欠落しています。
     * モデル名が間違っているため、モデルがツールを呼び出しません。最初のタスクを待たずにここで公開されます。 [PROBE_TIMEOUT_MS] を超えた場合は失敗としてカウントされず、サーバーからのエラーレポートはそのまま残ります。
     * モデルが見つからない場合は、それがどのモデルであるか、およびモデル名がどのようになるかを記載してください (このタイプの例は [exampleKind])。 [listMissing] が true の場合、リストも表示されません。
     * アドレスまたはインターフェイスの種類が正しく入力されていない可能性があります。一緒に説明しましょう。
     */
    private suspend fun probeModel(
        kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind, listMissing: Boolean,
    ): ProbeOutcome {
        val result = try {
            withContext(io) { withTimeout(PROBE_TIMEOUT_MS) { store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps) } }
        } catch (e: TimeoutCancellationException) {
            return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
        }
        return when (result) {
            ProbeResult.ToolCalled -> ProbeOutcome.Passed
            ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
            is ProbeResult.Failed -> when (val e = result.error) {
                is ModelError.ModelNotAvailable -> {
                    val detail = e.detail.ifEmpty { e.message.orEmpty() }
                    ProbeOutcome.Failed(
                        tr(R.string.model_probe_not_available, model, detail),
                        tr(R.string.model_reason_not_available, detail), notAvailable = true,
                    )
                }
                is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model), tr(R.string.model_reason_no_tools))
                is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                    if (listMissing) tr(R.string.model_probe_not_found_no_list, model, ModelSetupCopy.modelExample(exampleKind))
                    else tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
                    tr(R.string.model_reason_not_found),
                )
                // 5xx: このモデルの上流側がダウンしている可能性があります。先に失敗した場合は失敗したものとしてカウントされます。次のものを試行するかどうかは、[probe] が決定します。
                is ModelError.Server -> ProbeOutcome.Failed(
                    tr(R.string.model_probe_failed, model, e.message.orEmpty()),
                    tr(R.string.model_reason_server, "HTTP ${e.status}"), serverSide = true,
                )
                // エージェンシーの問題: それがモデルやサービスプロバイダーではなく、エージェントであるとは言い切れません。
                is ModelError.Proxy -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, proxyReason(e)))
                else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
            }
        }
    }

    /** 接続されたサービス プロバイダーから [model] の呼び出しを試みます。保存されたキーとリクエスト ヘッダーを使用します。これは、既存の構成またはデフォルトに基づくことができます。 */
    private suspend fun probeSaved(p: Provider, model: String): ProbeOutcome {
        val options = ModelOptions.ofProvider(p, store.list())
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        val endpoint = p.models?.firstOrNull { it.id == model }?.endpoint
        val example = ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind
        return probeModel(p.kind, p.baseUrl, store.providerKey(p.id), p.sessionHeader, model, endpoint, caps, example, listMissing = false)
    }

    // ---------- ChatGPTでログイン ----------

    /**
     * ブラウザを開いて ChatGPT にログインします。コールバック後、トークンを変更し、モデルを確認してリストし、ChatGPT サービス プロバイダーを保存 (または更新) して、Mika に戻ります。
     * にメインモデルがない場合は、いずれかのモデルをメインモデルとして選択します。初めて登録が成功すると、現在使用されているパッケージの量が表示されます。失敗した場合は、その理由を述べてください (診断情報として書き留めてください)。
     * は [signIns] で実行されます。ユーザーがブラウザにログインすると、Mika はバックグラウンドで動作し、インターフェイスに従うことができません。
     */
    fun signInWithChatGpt() {
        val browser = browser ?: return
        if (_state.value.chatGptSignIn == ListState.Running || signIns.running.value) return
        _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
        signIns.start {
            val (listProblem, first) = try {
                withContext(io) {
                    val (hostId, clientId) = store.chatGptRegistration()
                    val existing = store.chatGptProvider()
                    val session = signInWithChatGpt(
                        store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                        openBrowser = browser::open, onRegistered = store::rememberChatGptClient,
                        page = { e -> browser.page(e?.let { ModelsCopy.chatGptFailure(it.reason, it.code) }) },
                    )
                    val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                    val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    (listed as? ModelListResult.Failed)?.message to (clientId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? SignInException ?: SignInException(SignInFailure.Network, e.message.orEmpty(), e)
                signIns.recordFailure(failure)
                browser.returnToApp()
                _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(failure.reason, failure.code))) }
                return@start
            }
            browser.returnToApp()
            val id = store.chatGptProvider()?.id
            _state.update {
                snapshot(it.copy(
                    chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                    listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
                ))
            }
        }
    }

    /** 進行中のログインを中止します (ローカル コールバックはそれに応じてオフになります)。 */
    fun cancelChatGptSignIn() {
        signIns.cancel()
        _state.update { it.copy(chatGptSignIn = ListState.Idle) }
    }

    fun dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

    /**
     * アカウントを使用して xAI または OpenRouter:xAI にログインします。ブラウザで検証済みの検証ページを開き、インターフェイスにショート コードを残して、ユーザーの確認を待ちます。
     * OpenRouter が認証ページを開きます。同意した後、ローカル コールバックに戻り、ユーザーに属するキーに変更します。
     * 成功したら、モデルをリストし、これを保存し、メイン モデルがない場合は 1 つを選択し、その詳細ページ ([ModelSetupUi.signedInProvider]) を開きます。
     * 失敗では、カテゴリとエラー コードのみが表示されます (診断情報として書き留めます)。 ChatGPT のように [signIns] で実行: ユーザーがブラウザーにいるとき、Mika はバックグラウンドで動作します。
     */
    fun signInWithAccount(provider: AccountProvider) {
        val browser = browser ?: return
        if (signIns.running.value || _state.value.accountSignIn.running || _state.value.chatGptSignIn == ListState.Running) return
        _state.update { it.copy(accountSignIn = AccountSignInUi(provider = provider, running = true)) }
        val started = signIns.start(
            target = provider.target,
            noticeTitle = tr(R.string.account_signin_notif_title),
            noticeBody = tr(R.string.account_signin_notif_body),
        ) {
            try {
                val (p, failed) = withContext(io) {
                    val (p, listed) = when (provider) {
                        AccountProvider.Xai -> connectXai(browser)
                        AccountProvider.OpenRouter -> connectOpenRouter(browser)
                    }
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    p to (listed as? ModelListResult.Failed)
                }
                browser.returnToApp()
                _state.update {
                    it.copy(listing = if (failed != null) it.listing + (p.id to ListState.Failed(failed.message)) else it.listing - p.id)
                }
                signIns.finishAccount(AccountOutcome(provider.target, providerId = p.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? AccountSignInException ?: AccountSignInException(AccountSignInFailure.Network, cause = e)
                signIns.recordAccountFailure(provider.name, failure.reason.name, failure.code)
                browser.returnToApp()
                signIns.finishAccount(AccountOutcome(provider.target, failure = ModelsCopy.accountFailure(failure)))
            }
        }
        if (!started) _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    /** xAI デバイス コードでログインし、モデルをリストし、どれを保存 (または更新) します。 */
    private suspend fun connectXai(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val session = store.xaiAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
        val existing = store.xaiProvider()
        val listed = store.listModels(ProviderConfig.Kind.Xai, session.apiBase.ifBlank { XaiAuth.API_BASE }, session.accessToken)
        return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** OpenRouter ライセンス変更キーを入力し、モデルをリストし、[OpenRouter] をクリックしてデフォルトでモデルを保存 (または更新) します。 */
    private suspend fun connectOpenRouter(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val key = signInWithOpenRouter(store.openRouterAuth, browser::open, page = { e -> browser.page(e?.let(ModelsCopy::accountFailure)) })
        val existing = store.openRouterAccount()
        val listed = store.listModels(ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, key)
        return store.connectOpenRouter(key, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** インターフェイスにショート コードを残します。ブラウザを開くのはログインプロセスによって行われるため、ここでは再度開きません。 */
    private fun publishDevice(c: DeviceChallenge) {
        val openUrl = c.verificationUriComplete ?: c.verificationUri
        val expiresAt = System.currentTimeMillis() + c.expiresInSec * 1000
        // インターフェイスは、signIns.device 経由で表示されます (再構築されたインターフェイスも受信できます)。
        signIns.showDevice(DevicePrompt(c.userCode, openUrl, expiresAt))
    }

    fun cancelAccountSignIn() {
        signIns.cancel()
        _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    fun dismissAccountFailure() = _state.update { it.copy(accountSignIn = AccountSignInUi()) }

    /** の詳細ページを公開しました。 */
    fun signedInShown() = _state.update { it.copy(signedInProvider = null) }

    /** アカウントからログアウトしてログインします。xAI はローカル トークンをクリアし、OpenRouter はローカル キーを削除します。サービスプロバイダーと目的を変更せずに、再度ログインして引き続き使用してください。 */
    fun signOutAccount(providerId: String) {
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                if (p.kind == ProviderConfig.Kind.Xai) store.signOutAccount(providerId) else store.signOutOpenRouter(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    /** は、デフォルト モデル ([ModelOptions.defaultMain] を参照) をメイン モデルとして設定します。 */
    fun useAsMain(providerId: String) {
        val p = store.provider(providerId) ?: return
        val options = ModelOptions.ofProvider(p, store.list())
        val m = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return
        pick(ModelRole.Main, providerId, m)
    }

    /** ChatGPT を終了してログインします。サーバーからログアウトし、ローカルでトークンをクリアします。サービス提供者や目的に変更はなく、再度ログインすることで引き続きご利用いただけます。 */
    fun signOutChatGpt(providerId: String) {
        scope.launch {
            val revoked = withContext(io) { store.signOutChatGpt(providerId) }
            _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
        }
    }

    // ---------- 目的 ----------

    fun setReasoningEffort(role: ModelRole, effort: String?) {
        store.setReasoningEffort(role, effort)
        sync()
    }

    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val saved = store.setModelContext(configId, settings)
        sync()
        return saved
    }

    fun setModelVision(providerId: String, model: String, sees: Boolean) {
        store.setModelVision(providerId, model, sees)
        sync()
    }

    /** [providerId] の下の [model] を [role] として使用します (選択ページと [用途] パネルの両方についてはここに移動します): 構成がない場合は、その場で作成します。 */
    fun pick(role: ModelRole, providerId: String, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                use(role, p, m, ModelOptions.ofProvider(p, store.list()))
            }
            // メインモデルを選択したら、試用通話の手順は終了です。
            _state.update { snapshot(if (role == ModelRole.Main) it.copy(probeNote = null, pickToProbe = null) else it) }
        }
    }

    /** 写真の表示、軽量固定オプション: 「メイン モデルを使用する」または (写真の表示のみ) 「使用しない」。 */
    fun choose(role: ModelRole, choice: RoleChoice) {
        scope.launch {
            withContext(io) { store.choose(role, choice) }
            sync()
        }
    }

    /**
     * 選択ページで特定の企業のモデル名を手動で入力します (どの企業も入力できます。リストは提供されず、リストには何もありません)。または、メイン モデルは保存したばかりの企業から選択され、どのモデルもテストに合格しませんでした。
     * ([ModelSetupUi.probeBeforePick] を参照): 最初に 1 回呼び出してから、[role] として使用してください (検証済みとして記録されます。リストにないものは手動で入力されたとして記録されます)。
     * 、次に [onPicked] を調整します。理由がわからない場合は選択しないでください。メインモデルはツールも呼び出します。
     */
    fun pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        if (m.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
        val p = store.provider(providerId) ?: return
        _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
        scope.launch {
            val outcome = probeSaved(p, m)
            val problem = when {
                outcome is ProbeOutcome.Failed -> outcome.message
                outcome is ProbeOutcome.NoToolCall && role == ModelRole.Main -> tr(R.string.model_probe_no_tools_pick, m)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Failed(problem))) }
                return@launch
            }
            withContext(io) {
                store.markVerified(p.id, m)
                // リストにない場合は、手動入力として記録されます。リストを更新しても失われることはありません。選択時にチェックを入れてください。
                if (p.models.orEmpty().none { it.id == m }) store.markTyped(p.id, m)
                store.provider(p.id)?.let { fresh -> use(role, fresh, m, ModelOptions.ofProvider(fresh, store.list())) }
            }
            _state.update {
                snapshot(if (role == ModelRole.Main) it.copy(manualPick = it.manualPick - providerId, probeNote = null, pickToProbe = null)
                    else it.copy(manualPick = it.manualPick - providerId))
            }
            onPicked()
        }
    }

    // ---------- 音声からテキストへ ----------

    /** 「本体に付属」または「不要」を選択すると、その場で反映されます。 */
    fun pickSpeech(choice: SpeechChoice) {
        if (choice is SpeechChoice.Provider) return
        speech?.set(choice)
        _speechProbe.value = emptyMap()
        sync()
    }

    /**
     * 特定のサービス プロバイダーの音声モデルを選択します。まず、短い無音部分を取得して転送を試みます (ちなみに、使用するインターフェイスを識別できます)。選択内容を保存し、通過後に検証済みとして記録してから、[onPicked] を調整します。
     * 機能しない場合は、そのグループ ([speechProbe]) の下に理由を伝え、現在の選択を変更しないでください。
     */
    fun pickSpeechModel(providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        val settings = speech ?: return
        if (m.isEmpty() || _state.value.speechTesting != null) return
        val p = store.provider(providerId) ?: return
        _speechProbe.value = _speechProbe.value - providerId
        _state.update { it.copy(speechTesting = providerId) }
        scope.launch {
            val problem = try {
                val method = withContext(io) {
                    withTimeout(PROBE_TIMEOUT_MS) { transcriber(p).detect(p, store.providerKey(p.id), m) }
                }
                withContext(io) { store.markVerified(p.id, m) }
                settings.set(SpeechChoice.Provider(p.id, m, method))
                null
            } catch (e: TimeoutCancellationException) {
                tr(R.string.speech_pick_timeout, m, (PROBE_TIMEOUT_MS / 1000).toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                speechFailure(m, p, e)
            } catch (e: Exception) {
                tr(R.string.speech_pick_failed, e.message.orEmpty())
            }
            if (problem != null) _speechProbe.value = _speechProbe.value + (providerId to problem)
            _state.update { snapshot(it.copy(speechTesting = null)) }
            if (problem == null) onPicked()
        }
    }

    /** トライアル転送が失敗した理由: アカウントが開設されていない、モデルが見つからない。主なモデルを選択するときと同じ説明を使用し、残りはサービスプロバイダーからの最初の回答に基づいてください。 */
    private fun speechFailure(model: String, p: Provider, e: ModelError): String = when (e) {
        is ModelError.ModelNotAvailable -> tr(R.string.model_probe_not_available, model, e.detail.ifEmpty { e.message.orEmpty() })
        is ModelError.ModelNotFound -> tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind))
        is ModelError.Proxy -> tr(R.string.speech_pick_failed, proxyReason(e))
        else -> tr(R.string.speech_pick_failed, e.message.orEmpty())
    }

    private fun proxyReason(e: ModelError.Proxy): String = tr(
        if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
    )

    /** 選択ページを開くと、前回のトライアル転送の結果が無効になります。 */
    fun clearSpeechProbe() { _speechProbe.value = emptyMap() }

    /** 選択ページを開いたとき、最後の手動テスト呼び出しの結果が無効です。 */
    fun clearManualPick() = _state.update { it.copy(manualPick = emptyMap()) }

    /**
     * 「再テスト」: この会社が現在使用している未検証のモデル ([ModelSetupUi.unverified] を参照) が 1 つずつ試行され、呼び出されます。合格すると、検証済みとして記録されます。
     * 問題がある場合は、停止して理由を説明してください。
     */
    fun retest(providerId: String) {
        val p = store.provider(providerId) ?: return
        val models = _state.value.unverified(p)
        if (models.isEmpty() || _state.value.retest[providerId] == ListState.Running) return
        _state.update { it.copy(retest = it.retest + (providerId to ListState.Running)) }
        scope.launch {
            for (m in models) {
                val outcome = probeSaved(p, m)
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { snapshot(it.copy(retest = it.retest + (providerId to ListState.Failed(outcome.message)))) }
                    return@launch
                }
                withContext(io) { store.markVerified(p.id, m) }
            }
            _state.update { snapshot(it.copy(retest = it.retest - providerId)) }
        }
    }

    private fun use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>) {
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        store.use(role, p.id, model, caps)
    }

    // ---------- サービスプロバイダー ----------

    /** このサービス プロバイダーのモデルを再リストし、書き留めます。失敗した場合は、その理由を書き留めます。 */
    fun refresh(providerId: String) {
        val p = store.provider(providerId) ?: return
        if (refreshJobs[providerId]?.isActive == true) return
        _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
        refreshJobs[providerId] = scope.launch {
            val r = withContext(io) { store.listModels(p) }
            val models: List<ListedModel>? = when (r) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
            }
            if (models != null) withContext(io) { store.rememberModels(p.id, models) }
            val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(r.message) else ListState.Idle
            _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
        }
    }

    /** 選択ページとサービスプロバイダーページを開くと、まだ記憶されていないサービスプロバイダーが一度リストされます。 */
    fun ensureListed() {
        _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
    }

    /** ログアウトすると機種を一覧表示できなくなります。再度ログインしてください。 */
    private fun listable(p: Provider) = if (p.signIn != null) store.hasKey(p.id) else when (p.kind) {
        ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
        ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
        else -> true
    }

    /** キーを変更し、新しいキーでモデルを再リストします。 */
    fun changeKey(providerId: String, apiKey: String) {
        val k = apiKey.trim()
        if (k.isEmpty()) return
        scope.launch {
            withContext(io) { store.updateProviderKey(providerId, k) }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
        }
    }

    /** サービスプロバイダーとそのキーを削除します。その目的はデフォルトに戻すために使用します。 */
    fun removeProvider(providerId: String) {
        scope.launch {
            withContext(io) {
                // ChatGPT の場合は、一度ログアウトし、サーバーにログインしてから削除してください。
                when (store.provider(providerId)?.kind) {
                    ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
                    ProviderConfig.Kind.Xai -> store.signOutAccount(providerId)
                    else -> Unit
                }
                // OpenRouter によって承認されたキーは、サービス プロバイダーと一緒に削除されます。 OpenRouter からのキーは、ユーザーが [キー] ページで削除する必要があります。
                store.removeProvider(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    companion object {
        /** 「テストと保存」でのテスト呼び出しを待つ時間。 */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** 「テストと保存」にリストがある場合、最初に試すモデルと、失敗した場合に次に試すさらに 2 つのモデルの、最大でいくつかのモデルを試すことができます。 */
        const val PROBE_CANDIDATES = 3

        /** カスタマイズされたサービス プロバイダーの名前は、最大 2、3 語まで入力できます (サービス プロバイダーのリストは 1 行に収まります)。 */
        const val MAX_NAME = 32
    }
}
