package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.observation.tag
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.tool.parseArgs
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.FollowUp
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Presented
import com.wangzan101.mika.core.action.Refusals
import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.knowledge.ChannelAdvisor
import com.wangzan101.mika.core.knowledge.ChannelFacts
import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryContext
import com.wangzan101.mika.core.memory.ProfileContext
import com.wangzan101.mika.core.memory.ProfileEntry
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.knowledge.toolsNote
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.verify.Verifier
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId
import com.wangzan101.mika.core.userscreen.UserScreenSource
import com.wangzan101.mika.core.general.HISTORY_READ
import com.wangzan101.mika.core.general.VIEW_USER_SCREEN

/** バッチ終了後の別の外観: コンテンツと画面が同時に表示されます。 */
private fun batchViewNote(content: String) = Feedback.format("batch_view_note", "screen" to content)
private val MEMORY_VERBS = setOf(Verb.MemorySearch, Verb.MemorySave)
/** 歴史について話し合うときも、ローカル メモリとスキル データ、および以前のラウンドの記録を確認できます。これらの呼び出しでは、アプリや Web サイトは開きません。 */
private val DISCUSSION_LOOKUP_VERBS = setOf(Verb.MemorySearch, Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.HistoryRead)

/**
 * は「モデル↔ツール」のサイクルを回し、モデルに依頼（[ModelCaller]）→実行エントリを渡す（[ActionExecutor]）→というスケジューリングのみを行います。
 * 戦略に応じてリマインダー、提案、エンディングを決定 ([TurnPolicy]、[StopRules]、[StuckAdvice]、[NoticePolicy]) → 履歴に書き込みます。
 * 終了: ツール呼び出しのない応答がユーザーへの最終応答です (検証については、[AnswerReview] を参照してください。ソースはコードによって証拠簿に見つかります)。
 * 通話とともに書き込まれるテキストは、進行状況の説明 ([TaskState.Running.narration]) です。それができない場合は、ユーザーに尋ねる必要があり、手動で行う必要があり、誰もが独自の方法で進みます。
 * ユーザーに尋ね、ユーザーに渡します。空の返信 (または出力制限によって切り捨てられた返信) のみがリマインドされます。リマインドされるのは 1 回だけです。
 * は、各ラウンドの最初のツール呼び出しのみを実行します。 [gate] は書き込み操作の前に渡され (携帯電話のロック画面など、機能の前提条件が満たされない場合は中断されます)、ガードの結論がツール結果のガード フィールドに入れられます。
 * ゲートは、中断する前に setWaiting を使用して理由 (機能によって指定される [TaskState.Held]) を報告し、解放時に null を渡します。
 * は待機期間中にこのステータスを外部に表示し、終了後に実行を再開します。
 * の実行プロセスは [RunEvent] として [sink] に引き継がれ (感度が低くなり、大きなフィールドは切り捨てられます)、保存された画面は [RunSink.media] に引き継がれます。
 */
class AgentLoop(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val basePrompt: String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    private val knowledge: KnowledgeStore? = null,
    sink: RunSink = RunSink.NONE,
    /** はアプリ (パッケージ名、表示名) をインストールしました。これは、タスクのテキストに従って参考資料を選択するためにのみ使用されます。取得できない場合は空のリストとして処理されます。 */
    private val installedApps: suspend () -> List<Pair<String, String>> = { emptyList() },
    /** 最終的な回答が渡される前に、リスクに基づいて独立した検証が要求されます ([AnswerReview] を参照)。 null の場合、リテラル検証のみが行われます (製品パスを指定する必要があります)。 */
    private val verifier: Verifier? = null,
    /** 支払い確認レベル;独立した実行パスは null を渡し、モデルに request_confirmation を提供しません。 */
    private val confirmations: ConfirmationGate? = ConfirmationGate(),
    /** Query / Autonomous (途中で Autonomous に切り替えた場合は、次のステップからに従ってください);デフォルトのクエリ。 */
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    /** ユーザーが応答を確認し、同意しないを押すまで待機する時間制限。 null の場合は、待ち続けます (テスト)。 */
    confirmTimeoutMs: Long? = null,
    /** ミカ自身のパッケージ名: これに対する書き込み操作は実行されません。 */
    private val selfPackage: String? = null,
    /** ユーザーがフォローアップの質問 (ask_user と作業開始前のあいまいな質問) に回答するまでの制限時間。タイミングはコルーチン スケジューラのクロックに基づいており、テストでは仮想時間が使用されます。 */
    inputTimeoutMs: Long = DEFAULT_INPUT_TIMEOUT_MS,
    /** チャンネル選択に関する事実。指定されている場合は、タスクの開始時に「利用可能なチャネル」の行を挿入します。 */
    private val channelFacts: (suspend () -> ChannelFacts)? = null,
    /** スピードアップ スイッチ;デフォルトでは完全にオフになっています。 */
    val flags: SpeedFlags = SpeedFlags.OFF,
    /** イベント時間と経過時間に使用されるクロック。テストでは偽の時計に置き換えました。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 大型モデルと小型モデルの分業。 flags.lightSteps がオンになっている場合にのみ使用されます。 */
    router: ModelRouter? = null,
    /** ツールを初めて使用するときの UI ダイアログの独立した意図判断。 null は、評価およびその他のエントリの元の動作を保持します。 */
    intentJudge: ChatModel? = null,
    /** エクスペリエンスをリプレイするためのルート マッチング。 flags.replay がオンになっている場合にのみ使用されます。 */
    routes: RouteMatcher? = null,
    /** スキルディレクトリ (個人スキルを含む);タスクの開始時にすべてのスキルがリストされ、ターゲットに応じていくつかのアプリ/サイトの知識インデックスが与えられます。 null の場合、または [recommendSkills] がオフの場合、インデックスは与えられません。 */
    private val skills: GuideCatalog? = null,
    private val recommendSkills: Boolean = true,
    /** メモリ; null の場合 (またはメイン スイッチがオフの場合)、取得、注入、memory_* ツールは提供されません。 */
    private val memory: MemoryStore? = null,
    /** ランタイム状態の現地時間は、このタイムゾーンに従って書き込まれます。 */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** モデル呼び出しで一時的な障害 (電流制限、5xx、ネットワーク) が発生した場合に再試行します。 */
    retry: RetryPolicy = RetryPolicy(),
    /** 通話なしの応答は受け入れられ、最終応答の前に 1 回尋ねられます ([BeforeAccept] を参照)。デフォルトが受け入れられます。ステップ数を使い果たした後の最終ラウンドは問われません。 */
    private val beforeAccept: suspend (answer: String) -> BeforeAccept = { BeforeAccept.Accept },
    private val interaction: Interaction = LocalInteraction,
    private val profileSnapshot: List<ProfileEntry> = emptyList(),
    private val autonomous: Boolean = false,
    /** インタラクティブ インターフェイスにより、目に見えるテキスト フローが可能になります。評価呼び出しとバックグラウンド呼び出しでは、元の要求形式が維持されます。 */
    private val streamReplies: Boolean = false,
    /** この会話でユーザーによって送信された画面 ([UserScreenTurns] を参照)。そうでない場合は、画面のある線がそのままモデルに与えられます。 */
    private val userScreens: UserScreenSource? = null,
    /** 評価では古い終了検証を保持できます。インタラクティブな製品は実行プロセスの結果を観察し、終了はソースを分類するだけです。 */
    private val reviewFinalAnswer: Boolean = true,
    /** 同じ Web サイトまたはアプリ上での 2 つの連続した外部アクション (送信、送信) 間の間隔。デフォルトは等しくありません。 */
    private val outwardPacing: OutwardPacing = OutwardPacing.Off,
    private val trace: RunTrace? = null,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        // 再生がオンになっている場合にのみ、書き込み操作の前にターゲット要素とアンカー ポイントが取得されます。
        tools.captureTargets = flags.replay
    }

    /** 走行記録: 原因と結果の順に書き込まれます ([RecordOrder] を参照)。 */
    val events = RunEventBus().also { bus ->
        bus.subscribe(sink)
        trace?.let { bus.subscribe(it) }
    }
    private val sink = RecordOrder(events).also { order ->
        // 証拠台帳に入力された各観察は、そのまま実行レコードに入力されます (その後、それを読み取る呼び出しの結果が続きます)。
        tools.observer = { record { order.observed(RunEvent.Observed.of(it)) } }
    }

    private val caller = ModelCaller(model, tools, router, flags, retry, ::emit, ::now, autonomous, trace)
    private val intentGate = intentJudge?.let { IntentGate(it, model.takeUnless { main -> main === it }) }
    private val routeMatcher = routes
    /** このタスクのコンテキスト ジェネレーター (シェル、予算、指定された参照)。 */
    private var ctx = ContextBuilder()
    /** このタスクでユーザーによって送信された画面 (最初に作成)。 */
    private var screens: UserScreenTurns? = null
    /** 今回は、元の単語がモデルに与えられます (画面を使用するときの指示に置き換えられます。[UserScreenTurns] を参照)。開始する前は null です。結果を決定し、クリックして読むことで記憶を取得します。 */
    @Volatile var taskText: String? = null
        private set
    /**
     * ユーザーの最新の文に入力した単語 (最初または中断部分の元の単語) が [ownWords] に表示されます。中断中に入力がない場合 (画面のみが送信され、単語のみが選択されている場合)、前の文が使用されます。
     * これをクリックして言語のプロンプトと回答を確認します。記憶とスキルの引用は [UserWords.said] です (私は彼が自分自身を引用して言ったことも認識しています)。
     */
    private var latestWords = ""

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    @Volatile private var usedMemory: List<String> = emptyList()
    /** このタスクのメモリ ブロックにリストされているエントリ番号。タスクが完了したら読んでください。 */
    val usedMemoryIds: List<String> get() = usedMemory

    /** このミッションで実際に使用される能力グループ (このラウンドで記録され、次のラウンドの開始時に事前拡張されます)。ミッション終了後に読んでください。 */
    val usedGroups: Set<String> get() = tools.used

    /** ユーザーが今回言ったこと (memory_save と skill_save の引用検証に使用);与えられるとすぐに収集され、メモリ内にのみ保存され、各タスクの開始時にクリアされます。 */
    private val utterances get() = tools.utterances

    private val user = UserExchange(tools, confirmations, confirmTimeoutMs, inputTimeoutMs, { utterances }, { _state.value = it }, ::emit, ::now)
    private val replay = routes?.let { RouteReplay(it, tools, ::awaitGate, { r -> gateState = r; _state.value = r }, ::emit, clock) }

    @Volatile private var job: Job? = null
    /** 画面のロック/レベルの解除と待機 (現在のステップ、バッチ、または再生) 後に復元されたインターフェイスの状態。 */
    @Volatile private var gateState: TaskState = TaskState.Idle
    @Volatile private var cancelRequested = false
    /** は、リクエストが削除されるステップで中断されます。その後、遅延ストリーミング テキストは表示されなくなります。 */
    @Volatile private var interruptedStep = 0
    @Volatile private var streamedText: String? = null
    /** このダイアログの前のラウンドでユーザーが言ったことは、検証者が理解してフォローアップするためだけに使用されます。作業モデルについては何も含まれていません。 */
    private var earlierGoals: List<String> = emptyList()
    /** このランの終わり。ルートカウントはエンディング決定後に使用されます（[noteOutcome]）。 */
    @Volatile private var lastEnd: TaskState? = null
    private val review = AnswerReview(tools, verifier, ::emit, ::now)
    /** このタスクの各ステップで保存された画面番号 (ステップ → 番号)、回答のソースをクリックして画面を添付します。 */
    private val media = mutableMapOf<Int, MutableList<Int>>()
    /** このタスクに対する回答は、検証中に正しくなかったため、一度返されました。再度正しくない場合は、未確認としてマークされて引き渡され、再度返却されません。 */
    private var gapsNudged = false
    private var provenance = RunProvenance()
    val activeAsk: Delivery.Ask? get() = user.activeAsk

    /** 新しい入口: 同じ会話の中断と制御は、[inbox] を通じて入力されます。古い入口は引き続き同じ実行パスを使用します。 */
    suspend fun run(spec: RunSpec, inbox: RunInbox): TaskState = runInternal(spec.goal, spec.priorTurns, inbox, spec.provenance, spec.threadId)

    suspend fun run(task: String, priorTurns: List<Turn> = emptyList()): TaskState = runInternal(task, priorTurns, null, RunProvenance(), "local")

    private suspend fun runInternal(task: String, priorTurns: List<Turn>, inbox: RunInbox?, runProvenance: RunProvenance, threadId: String): TaskState {
        cancelRequested = false
        provenance = runProvenance
        user.begin(threadId, runProvenance, interaction)
        inbox?.bind(object : RunInbox.Handler {
            override suspend fun answer(text: String, askId: String?) = user.answerInput(text, askId)
            override suspend fun confirm(choice: ConfirmChoice, askId: String?) = user.answerConfirmation(choice, askId)
            override suspend fun handBack(result: HandBack, askId: String?) = user.resumeAfterTakeover(result, askId)
            override fun cancel() = this@AgentLoop.cancel()
        })
        caller.reset(basePrompt)
        // の同意は 1 つのミッション内でのみ有効です
        confirmations?.reset()
        utterances?.clear()
        emit(RunEvent.TaskStarted(now(), Redactor.text(task), model.name,
            origin = runProvenance.origin.takeIf { it != RunOrigin.Ui }?.name?.lowercase(),
            stepLimit = maxSteps.takeIf { it != Int.MAX_VALUE }))
        var end: TaskState? = null
        replay?.reset()
        lastEnd = null
        try {
            end = runLoop(task, priorTurns, inbox)
            lastEnd = end
            return end
        } catch (e: CancellationException) {
            end = TaskState.Cancelled
            throw e
        } finally {
            emit(ended(end))
            inbox?.close()
        }
    }

    private suspend fun runLoop(task: String, priorTurns: List<Turn>, inbox: RunInbox?): TaskState {
        val end = try {
            coroutineScope {
                // 介在シグナルは実行コンテキスト全体にインストールされます。待機は削除されても何も失われずに途中で終了します (waitOrSteer を参照) 書き込み前は
                val body = async(inbox?.signal ?: EmptyCoroutineContext) { loop(task, priorTurns, inbox) }
                job = body
                if (cancelRequested) body.cancel()
                body.await()
            }
        } catch (e: CancellationException) {
            if (!cancelRequested) throw e
            TaskState.Cancelled
        } finally {
            job = null
            user.clear()
            confirmations?.reset()
        }
        _state.value = end
        return end
    }

    /** [gate]：一時停止期間中は外部に待機理由が表示され、終了後は[gateState]が復帰します。 */
    private suspend fun awaitGate() {
        try {
            gate { waiting ->
                _state.value = waiting ?: gateState
                if (waiting != null) emit(waitingEvent(waiting))
            }
        } finally {
            _state.value = gateState
        }
    }

    /**
     * は、渡された回答がエンディングを通過した後に呼び出されます ([OutcomeJudge])。完了したルートが再生され、完了時に 1 つの成功のみが記録されます ([LearnGate])。
     * 失敗またはキャンセルされたタスクは判断されないため、呼び出す必要はありません。呼び出されなかった場合、成功は記録されません。
     */
    suspend fun noteOutcome(outcome: RunOutcome) {
        val end = lastEnd ?: return
        replay?.noteResult(end, outcome)
    }

    /** エンディングを譲ってください。エンディングは、hand_to_user のツール結果としてモデルに渡されます。 */
    suspend fun resumeAfterTakeover(result: HandBack) = user.resumeAfterTakeover(result)

    /** の古い戻りメソッドは常に完了したものとみなされます。 [note] が [USER_FINISHED_NOTE] の場合は、ユーザーが自分で完了したことを意味します。 */
    suspend fun resumeAfterTakeover(note: String) =
        resumeAfterTakeover(HandBack(HandBackOutcome.complete, userFinished = note == USER_FINISHED_NOTE))

    /** は追加の質問に答えます。待っている質問がない場合 (タイムアウト、回答済み)、 false が返され、インターフェイスはユーザーに回答が遅れていることを伝えます。 */
    suspend fun answerInput(text: String): Boolean = user.answerInput(text)

    suspend fun answerConfirmation(approved: Boolean) = answerConfirmation(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny)

    suspend fun answerConfirmation(choice: ConfirmChoice) = user.answerConfirmation(choice)

    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    private suspend fun loop(raw: String, rawPrior: List<Turn>, inbox: RunInbox?): TaskState {
        // ユーザーから送信された画面: この文は全体 (その文、スクリーンショット、詳細) であり、前のラウンドでの収穫が連続しています。次の文は次のように置き換えられます
        val shots = UserScreenTurns(userScreens, zone, tools.seesImages, canAskVision = !tools.seesImages && tools.vision != null && tools.definitions().any { it.name == VIEW_USER_SCREEN },
            image = { tools.modelImage(it, null) }, seen = tools::noteUserScreen).also { screens = it }
        user.answerText = shots::asText
        val opening = shots.render(raw)
        val task = opening?.said ?: raw
        taskText = task
        latestWords = ownWords(raw, opening)
        val priorTurns = shots.collapse(rawPrior)
        earlierGoals = priorTurns.map { it.userText }
        // 前のラウンドは参照専用であり、このタスクの前に配置する必要があります。
        val context = Conversation.context(priorTurns)
        utterances?.add(UserWords.said(opening?.words ?: raw, tools))
        // メモリ ブロック: 上のダイアログの後、元のタスク テキストの前。使用されたエントリはコードに記録されます
        usedMemory = emptyList()
        // メモリ ファイルが最後に読み込まれなかった場合 (キーストアが一時的に利用できなくなった場合)、もう一度読み取ります。それでも読み取れない場合は、今回はメモリ ファイルを使用しないでください。
        // プロダクト パスは自動を使用します。メモリの総量が適合する場合は、全量を注入し (取得の欠落なし)、適合しない場合は取得に戻ります。
        val block = memory?.takeIf { it.reloadIfNeeded() }?.let {
            MemoryContext.build(it.items.value, task, priorTurns.lastOrNull()?.userText, tools.legacy::rewrite,
                mode = MemoryContext.Mode.Auto)
        }
        if (block?.message != null) {
            usedMemory = block.usedIds
            memory?.markUsed(block.usedIds)
            emit(RunEvent.MemoryUsed(now(), block.usedIds))
        }
        // 開始時に事前展開: 前回のラウンドで使用され、今回も必ず使用されるグループ (ToolDispatcher.preload を参照)
        tools.preload(task, priorTurns.lastOrNull()?.groups.orEmpty()).forEach { emit(RunEvent.State(now(), "tools_preloaded", it)) }
        var openGroups = tools.expanded
        val batchSpec = BatchRunner.spec(tools.batchable()).takeIf { flags.batchSteps }
        fun keep(s: ToolSpec): Boolean {
            val verb = tools.verb(s.name)
            return (confirmations != null || verb != Verb.RequestConfirmation) && (memory != null || verb !in MEMORY_VERBS)
        }
        // プロンプト ワードのバージョンは、各グループが展開されるときに計算されます。同じバージョンのプロンプト ワードは、今回どのグループが展開されるかによって異なりません。
        val everything = tools.offeredNow(listOfNotNull(batchSpec), all = true).filter(::keep)
        emit(RunEvent.PromptVersion(now(), PromptVersion.of(SystemPrompt.assemble(basePrompt, tools.promptSections(everything, all = true)), everything)))
        var specs = emptyList<ToolSpec>()
        // 毎回モデルについて尋ねる前に、新しいグループが展開されると、それに応じてツールとシステムのプロンプトの単語が変わります。拡張されない場合は、変更されないままになります。
        fun refreshOffer() {
            val offer = tools.offeredNow(listOfNotNull(batchSpec)).filter(::keep)
            if (offer == specs) return
            specs = offer
            caller.systemPrompt = SystemPrompt.assemble(basePrompt, tools.promptSections(specs))
            (tools.expanded - openGroups).forEach { emit(RunEvent.State(now(), "tools_opened", it)) }
            openGroups = tools.expanded
        }
        refreshOffer()
        val guards = OperationGuards(maxSteps)
        val recorder = ActionRecorder(sink, clock, saveScreen = { id, png -> sink.media(id, png) })
        val executor = ActionExecutor(tools, guards, confirmations, gate = ::awaitGate, recorder = recorder, offScreen = tools.offScreenGuard(), approvals = approvals,
            provenance = provenance,
            selfPackage = selfPackage, autonomous = autonomous, pacing = outwardPacing)
        var emptyNudged = false
        // ツールを調整した後でのみ、材料を検証できます。ユーザーに支払いを行うことは、それを外部の世界に解放することと同じです。答えは別途検証する必要があります。
        var acted = false
        var paymentHandedOver = false
        // ユーザーはフォローアップの質問に回答し、他には何もしていません。現時点では、回答を送信する必要があり、そうするよう促されます ([owedFollowUp] を参照)
        var answered: String? = null
        var followUpNudged = false
        var resultReadNudged = false
        var languageNudged = false
        // ツールの呼び出し時にテキストとして見逃された応答が 1 回だけ返されました ([LeakedToolCall] を参照)。ツールを正常に調整した後、再計算されます。
        var leakNudged = false
        fun facts() = ReviewFacts(acted, paymentHandedOver || recorder.outwardActs > 0, recorder.lastChangeMark?.let { !tools.readSince(it) } == true)
        media.clear()
        gapsNudged = false
        interruptedStep = 0
        var note = ""
        // ユーザーの元の単語以外のすべてのコンテキストは、統合シェル、統合バジェットを通じて生成されます。
        ctx = ContextBuilder(zone = zone)
        val installed = try { installedApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val labels = installed.toMap()
        val matched = knowledge?.matchTask(task, Int.MAX_VALUE, installed).orEmpty()
        val index = if (skills != null && recommendSkills) GuideRecommender.index(skills, task) else null
        val start = ctx.start(
            task, priorTurns = context?.let { (it.parts.single() as Part.Text).text }?.let { prior ->
                // 前のラウンドで読んだ内容が判明した場合に発言します（デメリットがあり、実行ラウンドがあり、history_readが提供されている場合のみ）
                if (tools.definitions().any { it.name == HISTORY_READ }) Feedback.format("history_hint", "turns" to prior) else prior
            },
            memory = listOfNotNull(ProfileContext.render(profileSnapshot), block?.message).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            channel = if (knowledge != null) channelLine(matched) else null,
            knowledge = matched, knowledgeNote = toolsNote(tools.scriptsOffered()), withLinks = flags.deepLinks,
            skillsIndex = index,
            taskImages = opening?.images.orEmpty(), screens = opening?.details.orEmpty(),
        )
        val history = start.messages.toMutableList()
        start.knowledge.filter { it.trial }.forEach { emit(RunEvent.State(now(), "knowledge_trial_used", it.name)) }
        // リンクは、open_link が利用可能な場合にのみ与えられ、モデルに実際に与えられたコピーのみがリリースされます。
        if (flags.deepLinks) start.knowledge.forEach { if (it.links.isNotEmpty()) tools.allowLinks(it.links) }
        // Web ページ上の各サイトは、初めてアクセスするときにドメイン名でナレッジを 1 回だけ検索します。
        val visitedSites = mutableSetOf<String>()

        // 現時点でのタスクはどこですか (アプリ、サイト...)。各呼び出しの結果に応じて機能側によって与えられる
        var place: Place? = null
        var stuck = StuckState()
        var lastProgressVersion = guards.progressVersion
        fun stuckAdvice(verdicts: List<GuardVerdict>, typed: String?): String? {
            val version = guards.progressVersion
            val (next, advice) = StuckAdvice.next(stuck, StuckInput(verdicts, typed, place?.name, version != lastProgressVersion))
            lastProgressVersion = version
            stuck = next
            return advice
        }
        val track = StepTrack()
        var intent: IntentGate.Intent? = null
        var intentAssessed = false
        // は実行ごとに 1 回だけ判定されます。判断できない場合 (呼び出しが失敗する、応答が 3 つの単語のいずれでもない場合) は null となり、メイン モデルは独自の決定に従います。
        suspend fun assessIntent(): IntentGate.Intent? {
            intentAssessed = true
            // は画面を送信するだけで、言葉はありません。判断する言葉はありません。画面の意図を推測するルールに従って、メインモデルに渡します。
            val result = if (opening != null && opening.words.isEmpty()) null else intentGate?.assess(task, priorTurns)
            emit(RunEvent.State(now(), "intent_gate", result?.name ?: "unavailable"))
            return result
        }
        // 初めてモデルを呼び出す前に: 適切なルートがある場合は、モデルを調整せずにそのルートに従います。完了するか一致しない場合はモデルを返します。
        // ルートだけを記憶して携帯電話に保存する方法: 点呼用 Web ページを使用してタスクを再生しない
        if (flags.replay && replay != null && WEB_CHANNEL_PREFIXES.none(task.trimStart()::startsWith)) {
            val reviewRoute = provenance.origin == RunOrigin.Ui && intentGate != null && routeMatcher?.candidates(task)?.isNotEmpty() == true
            if (reviewRoute) intent = assessIntent()
            if (!reviewRoute || intent == IntentGate.Intent.Read || intent == IntentGate.Intent.Act) {
                replay.run(task, history, executor, ctx, track)?.let { place = it; acted = true }
            }
        }

        for (step in 1..maxSteps) {
            appendSteers(history, inbox)
            if (maxSteps != Int.MAX_VALUE) TurnPolicy.beforeStep(TurnSnapshot(step, maxSteps))?.let { history += ctx.notice(it.source, it.text) }
            appendRuntimeState(history, place, labels, step)
            _state.value = TaskState.Running(step, note)
            refreshOffer()
            var asked = now()
            val routed = try {
                routedOrRedirect(inbox, history, specs, track, step, { asked = it }, previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.modelFailed(e)
            }
            if (routed == null) {
                keepInterrupted(history, inbox, step)
                continue
            }
            var (reply, used) = routed
            val proposed = reply.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (provenance.origin == RunOrigin.Ui && intentGate != null && proposed != null &&
                tools.verb(proposed.name) !in DISCUSSION_LOOKUP_VERBS) {
                if (!intentAssessed) intent = assessIntent()
                if (intent == IntentGate.Intent.Discuss) {
                    // 提案された通話は履歴に書き込まれず、実行されていません。もう一度質問し、直接答える機会だけを与えてください。
                    val discussion = history + ctx.notice("conversation_intent",
                        "The user is discussing or reviewing this time. Answer this question directly, without reading or operating apps, websites or other outside content.")
                    reply = try { caller.ask(discussion, emptyList(), step, using = model, onText = previewFor(step)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return StopRules.modelFailed(e) }
                    used = model
                }
            }
            val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            history += Message(Role.Assistant, reply.parts, Author.Mika).also { caller.noteReply(it, used, reply.usage) }
            val calls = reply.parts.filterIsInstance<Part.ToolCall>()
            val visibleText = displayReply(text, calls.isNotEmpty())
            note = visibleText.ifEmpty { calls.firstOrNull()?.name ?: "" }
            val tool = calls.firstOrNull()?.name
            val reason = calls.firstOrNull()?.let { tools.callReason(it.name, parseArgs(it.argsJson)) }
                ?.let { if (JapaneseOutput.isJapaneseProse(it)) it else GENERIC_PROGRESS }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            // 通話とともに書かれた言葉は進行状況の説明です。最初にインターフェイスのリアルタイム ラインで使用します。
            val narration = visibleText.takeIf { calls.isNotEmpty() && it.isNotEmpty() }
            _state.value = TaskState.Running(step, note, tool, reason = reason, narration = narration,
                preview = visibleText.takeIf { streamReplies && calls.isEmpty() && it.isNotEmpty() })
            // 通話による応答は、実行後に記録されます。これは、ポートがパスワード ボックスを報告するかどうかによって異なります。
            fun recordReply(argsJson: String?) = emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(visibleText)), tool, argsJson,
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs,
                if (caller.recordRoles) used.name else null, if (caller.recordRoles) caller.roleOf(used) else null, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))

            if (calls.isEmpty()) {
                track.after(null, ok = false, guard = false, changed = null, recovery = false, app = null)
                recordReply(null)
                // モデルが最終的な答えを与えるとき、最初に途中でユーザーからのコメントを受け入れ、その後、答えを渡すかどうかを決定します。
                if (keepFinished(history, inbox)) continue
                // 空の返信、出力上限で切り取られた文の半分は回答ではありません。一度リマインドして、これで終了します。
                if (text.isEmpty() || reply.stopReason == StopReason.MaxTokens) {
                    val n = TurnPolicy.afterEmpty(emptyNudged, truncated = text.isNotEmpty()) ?: return StopRules.noAnswer()
                    emptyNudged = true
                    history += ctx.notice(n.source, n.text)
                    continue
                }
                emptyNudged = false
                // ツール呼び出しがテキストとして漏洩します (サーバーはそれを呼び出しに解析しません): これは答えではありません。一度戻って、ツールインターフェイスを通じて再度呼び出します。
                // は別のリークが発生するとすぐに終了し、元のマークはユーザーに引き継がれません。
                val leaked = LeakedToolCall.find(text, everything.map { it.name })
                if (leaked != null) {
                    if (leakNudged) return StopRules.toolCallAsText()
                    leakNudged = true
                    emit(RunEvent.State(now(), "tool_call_as_text", RunEvent.clip(leaked)))
                    TurnPolicy.leakedToolCall(leaked).let { history += ctx.notice(it.source, it.text) }
                    continue
                }
                // ユーザーは、何かに同意または回答した​​だけで、その後は何もしませんでした。まず、ユーザーにそれを実行するよう思い出させるか、実行しなかった理由を説明します。それからいつものようにチェックしてください
                val owed = owedFollowUp(answered).takeIf { !followUpNudged }
                if (owed != null) {
                    followUpNudged = true
                    TurnPolicy.followUp(owed).let { history += ctx.notice(it.source, it.text) }
                    continue
                }
                val before = beforeAccept(text)
                if (before is BeforeAccept.Continue) {
                    history += ctx.notice(Feedback["before_accept_source"], before.note)
                    continue
                }
                val currentFacts = facts()
                if (!reviewFinalAnswer && currentFacts.unreadChange && !resultReadNudged) {
                    resultReadNudged = true
                    history += ctx.notice("operation_result", "直前の操作結果をまだ読み取っていません。現在の画面で実際の状態を確認してから回答してください。読み取れない場合は、その旨を明示してください。")
                    continue
                }
                // 回答本文が日本語でない場合:1回だけ差し戻して全文を日本語に書き直させる。
                // 書き直しても日本語にならなければ、外国語の回答をそのまま渡さず日本語で失敗を返す(翻訳を捏造しない)。
                // 引用・コード・短い固有名詞のみの回答は [ReplyLanguage] が不一致としないため、そのまま渡す。
                val languageMismatch = ReplyLanguage.mismatch(text)
                if (languageMismatch != null) {
                    if (!languageNudged) {
                        languageNudged = true
                        TurnPolicy.language(languageMismatch).let { history += ctx.notice(it.source, it.text) }
                        continue
                    }
                    return StopRules.languageNotJapanese(languageMismatch, currentFacts)
                }
                // 電話をかけずに返信することが最終的な回答です。製品パスはソースを分類するだけです。
                val result = conclude(task, text, step, currentFacts, history)
                if (result != null) {
                    // 引き渡す前にもう 1 つコメントがあります (ソースの確認と整理から来ています): 完成せずに持ち帰って作業を続けてください。そうでない場合は、受信箱を閉じてください。そうすれば、次のことになります
                    if (inbox?.closeUnlessSteered() == false) { keepFinished(history, inbox); continue }
                    return result
                }
                continue
            }
            emptyNudged = false
            leakNudged = false
            acted = true
            // は回答後に別のことを行いました。その回答には続きがあります。
            answered = null

            val first = calls.first()
            trace?.toolStarted(step, first.name, first.id, now())
            val skipped = calls.drop(1).map {
                Part.ToolResult(it.id, buildJsonObject {
                    put("ok", false)
                    put("error", "parallel_tool_call_skipped")
                    put("message", Feedback["parallel_call_skipped"])
                }.toString(), isError = true)
            }
            val args = parseArgs(first.argsJson)

            // ユーザーが同意したステップは個別に実行する必要があります。バッチ内のステップには同意がありません。これらのステップが混在している場合、レベルによってブロックされます。同意は他の手順で使用または無効化される場合もあります。
            if (((flags.batchSteps && first.name == DO_STEPS_TOOL) || tools.composite(first.name)) && confirmations?.hasGrant == true) {
                val content = buildJsonObject {
                    put("ok", false); put("error", "approved_step_first"); put("message", APPROVED_STEP_FIRST); put("recoverable", true)
                }
                history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError = true)) + skipped, Author.Mika)
                recordReply(if (first.name == DO_STEPS_TOOL) BatchRunner.redactArgs(first.argsJson) else Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                recorder.result(step, first.name, tools.verb(first.name), content, JsonObject(emptyMap()), null, null, 0, "model", first.id)
                track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                continue
            }
            if (flags.batchSteps && first.name == DO_STEPS_TOOL) {
                when (val end = batch(first, skipped, history, specs, guards, executor, track, step, note, tool, reason, narration, recordReply = ::recordReply,
                    app = { place = it }, steerWaiting = { inbox?.hasSteers() == true },
                    noteVerdicts = { s, sub, v ->
                        // シングルステップと同様に、スタックリマインダーや同じ段落の繰り返し入力もカウントされます。
                        val typed = StuckAdvice.typedText(tools.verb(s.tool), parseArgs(s.argsJson), sub?.content)
                        stuckAdvice(v, typed)
                    })) {
                    null -> continue
                    else -> return end
                }
            }

            val verb = tools.verb(first.name)
            val offScreen = tools.offScreen(first.name)
            val running = TaskState.Running(step, note, tool, reason = reason, narration = narration)
            gateState = running
            var advice: String? = null
            var waitsForUser = false
            val action = executor.run(first, Origin.Model(step), object : FollowUp {
                override suspend fun resolve(exec: ToolExecution): ToolExecution? {
                    var e = exec
                    (e.outcome as? Outcome.Confirm)?.let { e = user.confirm(it, step, running) }
                    // ユーザーの応答を待ちたい場合 (質問、引き継ぎ): 待つ前にこの応答を書き留め、待機中にタスクをキャンセルし、実行記録に残します。
                    waitsForUser = e.outcome is Outcome.Input || e.outcome is Outcome.Takeover
                    if (waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                    // 質問に対する答え (またはタイムアウト) は、この呼び出しのツール結果であり、呼び出しは結果とペアになっています。
                    (e.outcome as? Outcome.Input)?.let { o ->
                        e = user.input(o, running)
                        if (e.content["ok"] == JsonPrimitive(true)) answered = o.prompt
                    }
                    // ハンドオーバーが完了するまで待ちます。ハンドオーバーの結果は、この呼び出しのツールの結果になります。キャンセルタスクが終了する
                    (e.outcome as? Outcome.Takeover)?.let { e = user.takeover(it, running) ?: return null }
                    return e
                }

                override suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented {
                    val e = exec
                    val a = stuckAdvice(verdicts, StuckAdvice.typedText(verb, args, e.content))
                    advice = a
                    val content = if (verdicts.isEmpty() && advice == null) e.content
                    else JsonObject(e.content +
                        (if (verdicts.isEmpty()) emptyMap() else mapOf("guard" to JsonArray(verdicts.map { it.toJson() }))) +
                        (advice?.let { mapOf("advice" to JsonPrimitive(it)) } ?: emptyMap()))
                    val isError = (e.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
                    history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError, e.observations.map { it.tag })) + skipped, Author.Mika)
                    // ポートに「パスワード ボックスではありません」と明確に表示されている場合にのみ、元のテキストを思い出すことができます。
                    val secret = (e.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
                    if (!waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secret))
                    return Presented(e, content)
                }

                override fun refused(content: JsonObject) {
                    history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError = true)) + skipped, Author.Mika)
                    recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                }
            })
            val ran = when (action) {
                is ActionOutcome.Cancelled -> return TaskState.Cancelled
                is ActionOutcome.Refused -> {
                    guards.stop?.let {
                        recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                        return StopRules.guard(it)
                    }
                    // アプリケーションのアクセス許可の拒否が、実行ポータルによるツールの結果としてモデルに引き渡されました。タスクを終了せず、モデルにルートを変更させるか、ユーザーに説明させてください。
                    if (AppAccessDenied.isAppAccessCode(action.code)) {
                        track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                        continue
                    }
                    // モデルの単一ステップは、ガードによって停止されるか、確認レベルによってブロックされるか、ミカ自身に影響するため実行されません。他の拒否は、実行入口がここで一致しないことを示します。
                    check(action.hit != null || action.code == Refusals.OWN_APP) { "unexpected refusal ${action.code} for ${first.name}" }
                    // は確認レベルによって停止されました。これは故障したサーキット ブレーカーとしてカウントされず、モデルに引き渡されて確認プロセスが実行されます。
                    track.after(tools.verb(first.name), ok = false, guard = action.before.isNotEmpty(), changed = null, recovery = false, app = null)
                    continue
                }
                is ActionOutcome.Ran -> action
            }
            val exec = ran.exec
            if (verb == Verb.HandToUser && (args["reason"] as? JsonPrimitive)?.contentOrNull == PAYMENT) paymentHandedOver = true
            val isError = (exec.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
            noteResultEvents(verb, exec.content)
            exec.place?.let { place = it }
            track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)
            val picture = ran.picture
            val shotId = ran.shotId
            if (picture != null && shotId != null) {
                record { sink.media(shotId, picture) }
                media.getOrPut(step) { mutableListOf() } += shotId
                emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(place)))
            }

            val stopNow = guards.stop ?: executor.offScreenStop
            val end = StopRules.afterAction(exec.outcome, stopNow)
            if (end != null) {
                // モデルがそれは実行できない (end_not_done) と言ったとき、ユーザーは次のように付け加えました。終了せずに、この文を念頭に置き続けてください。警備員がそれを止めますが、それでも終わります。
                if (stopNow == null && exec.outcome is Outcome.Failure && inbox?.closeUnlessSteered() == false) {
                    appendSteers(history, inbox)
                    continue
                }
                return end
            }

            if (exec.images.isNotEmpty()) {
                // 読み取られた画像ファイルは画面イメージではなく、screenshotId がありません。
                history += Message(Role.User, listOf(Part.Text(NoticePolicy.imageNote(verb, offScreen))) +
                    exec.images.mapIndexed { i, img -> tools.modelImage(img, if (offScreen) null else exec.imageIds.getOrNull(i)) }, Author.Mika)
            }
            for (lookup in NoticePolicy.knowledgeAfter(verb, exec.place, !isError)) when (lookup) {
                is KnowledgeLookup.App -> knowledge?.byPackage(lookup.pkg).let { injectKnowledge(history, it) }
                // 同じ知識は繰り返し注入されません
                is KnowledgeLookup.Site -> if (visitedSites.add(lookup.host)) knowledge?.byDomain(lookup.host).let { injectKnowledge(history, it) }
            }
        }
        appendSteers(history, inbox)
        refreshOffer()
        return finalTurn(task, history, specs, maxSteps + 1, labels, place, recorder, facts(), inbox)
    }

    /** 走行記録の結果に応じて個別に記録されるもの：報告された障害物（評価用、バリアカテゴリーに応じて属性が付けられます。パラメータが間違っていたり拒否されたものはカウントされません）、見た個人スキル、保存された記憶。 */
    private fun noteResultEvents(verb: Verb?, content: JsonObject) {
        if (verb == Verb.HandleObstacle && content["ok"] == JsonPrimitive(true)) {
            (content["kind"] as? JsonPrimitive)?.contentOrNull?.let { emit(RunEvent.Barrier(now(), it)) }
        }
        GuideRecommender.viewedPersonal(verb, content)?.let { emit(RunEvent.SkillUsed(now(), it, "viewed")) }
        if (verb == Verb.MemorySave && content["ok"] == JsonPrimitive(true)) emit(RunEvent.MemorySaved(now(),
            content["id"]?.jsonPrimitive?.content ?: "", content["kind"]?.jsonPrimitive?.content ?: "", content["status"]?.jsonPrimitive?.content ?: ""))
    }

    /** フロントエンド アプリ/サイトの参考資料には履歴が補足されています (確実性、キーワードに基づく推測はありません)。すべてのスキルの空き領域がリストされているため、ここでは補足しません。 */
    private fun injectKnowledge(history: MutableList<Message>, e: KnowledgeEntry?) {
        ctx.knowledge(e, toolsNote(tools.scriptsOffered()), flags.deepLinks)?.let { m ->
            if (flags.deepLinks && e!!.links.isNotEmpty()) tools.allowLinks(e.links)
            history += m
            if (e?.trial == true) emit(RunEvent.State(now(), "knowledge_trial_used", e.name))
        }
    }

    /**
     * このステップの実行時ステータスは、このステップの最後のメッセージ (まだ送信されていないメッセージ) の末尾に接続されており、このステップと一緒に切り取られて折りたたまれます。
     * によって送信されるメッセージはまったく変更されず、要求プレフィックスは徐々に増加するだけです。最後のメッセージがアシスタント メッセージである場合 (通常のプロセスでは発生しません)、別のメッセージを追加します。
     * Web ページ上のバージョンは、ブラウザーの現在のタブで実際に使用されているバージョンから読み取られます。
     */
    private suspend fun appendRuntimeState(history: MutableList<Message>, place: Place?, labels: Map<String, String>, step: Int) {
        val mode = if (place?.kind == PlaceKind.Site) tools.placeMode() else null
        val state = ctx.runtimeState(place, place?.name?.let(labels::get), mode, step, maxSteps, now(), latestWords)
        val last = history.lastOrNull()
        if (last == null || last.role == Role.Assistant) history += Message(Role.User, listOf(state), Author.Mika)
        else history[history.lastIndex] = last.copy(parts = last.parts + state)
    }

    /**
     * 今回ユーザーが入力した内容：画面の説明文、ブラウザページのページ説明文、引用文の段落、添付ファイルの行が次々に削除されました（[UserWords.typed]を参照、持参の際はすべて削除してください）、
     * 何もない場合は、元の単語を意味します。画面のみ送信、段落引用のみ、単語選択のみ、添付ファイルのみの場合は空です。
     * モデルに与えられた文には、固定の英語の説明と中断マークがあり、その後に実行時のステータスが続く場合があります。ユーザーが使用する言語を判断することはできません。
     */
    private fun ownWords(raw: String, shown: UserScreenTurns.Rendered?): String = UserWords.typed(shown?.words ?: raw, tools)

    private suspend fun channelLine(matched: List<KnowledgeEntry>): String? {
        val facts = channelFacts ?: return null
        val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        return ChannelAdvisor.line(matched, tools.skillCatalog, f)
    }

    /**
     * 介入は実際のユーザーの個別の行です。これらはステップ間でのみ追加され、すでにモデルに送信されたメッセージを上書きすることはありません。
     * が中断してその時点でユーザーの画面を取得すると、最初の元の単語に置き換えられます。その文にはスクリーンショットが伴い、その後に画面の詳細が続きます。
     */
    private suspend fun appendSteers(history: MutableList<Message>, inbox: RunInbox?): Boolean =
        appendSteers(history, inbox?.takeSteers().orEmpty())

    private suspend fun appendSteers(history: MutableList<Message>, updates: List<RunInbox.UserSteer>): Boolean {
        updates.forEach { update ->
            val shown = screens?.render(update.text)
            val words = ownWords(update.text, shown)
            if (words.isNotBlank()) latestWords = words
            history += Message(Role.User, listOf(Part.Text("$STEER_MARKER\n${shown?.said ?: update.text}")) + shown?.images.orEmpty(), Author.User)
            shown?.details?.forEach { (source, body) -> history += ctx.userScreen(source, body) }
            utterances?.add(UserWords.said(shown?.words ?: update.text, tools))
            emit(RunEvent.State(now(), "steer_received", RunEvent.clip(Redactor.text(update.messageId))))
        }
        return updates.isNotEmpty()
    }

    /**
     * 通話なしの応答が履歴に記録され、表示されます。このとき、割り込みが発生します。割り込みを接続して続行します（true を返します）。
     * 応答を画面上に保持します。インターフェイス上のリアルタイム テキストは、次のステップが開始するとすぐにクリアされるため、`reply_finished_before_steer` を送信して割り込みの上にハングします。
     * は、中断がない場合は false を返し、何も行いません。
     */
    private suspend fun keepFinished(history: MutableList<Message>, inbox: RunInbox?): Boolean {
        val steers = inbox?.takeSteers().orEmpty()
        if (steers.isEmpty()) return false
        val current = _state.value as? TaskState.Running
        val shown = current?.preview?.trim()
        if (current?.preview != null) _state.value = current.copy(preview = null)
        // 切り詰められていません: これは完全な答えです。インターフェイスは、中断がなかったかのように全文を表示する必要があります (長さはモデル出力の上限によって本質的に制限されます)。
        if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_finished_before_steer", Redactor.text(shown)))
        appendSteers(history, steers)
        return true
    }

    /** 割り込みは待機中のモデル要求のみをキャンセルします (このステップは履歴に書き込まれず、次のステップでは割り込みを使用して再度要求します)。ツール呼び出しは、実行入口に入ると自動的に完了します。 */
    private suspend fun routedOrRedirect(
        inbox: RunInbox?, history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)?,
    ): Pair<ModelReply, ChatModel>? {
        if (inbox == null) return caller.routed(history, specs, track, step, restarted, onText)
        return coroutineScope {
            val pending = async { caller.routed(history, specs, track, step, restarted, onText) }
            select<Pair<ModelReply, ChatModel>?> {
                pending.onAwait { it }
                inbox.redirects.onReceive {
                    pending.cancelAndJoin()
                    null
                }
            }
        }
    }

    /**
     * ステップ[step]のモデル要求を中断する。生の部分応答は次のモデル要求の履歴に保持する。
     * `reply_interrupted`にはユーザーが実際に見た日本語の状況表示だけを渡し、未検証の部分応答は表示しない。
     * 再試行で応答が取り消された場合や、部分応答がない場合は何も残さない。
     */
    private suspend fun keepInterrupted(history: MutableList<Message>, inbox: RunInbox?, step: Int) {
        val steers = inbox?.takeSteers().orEmpty()
        interruptedStep = step
        val current = _state.value as? TaskState.Running
        val partial = streamedText?.trim()
        val shown = current?.takeIf { it.step == step }?.preview?.trim()
        streamedText = null
        if (current?.preview != null) _state.value = current.copy(preview = null)
        if (!partial.isNullOrEmpty() && steers.isNotEmpty()) {
            history += Message(Role.Assistant, listOf(Part.Text(partial)), Author.Mika)
            if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_interrupted", RunEvent.clip(Redactor.text(shown))))
        }
        appendSteers(history, steers)
    }

    private fun previewFor(step: Int): ((String) -> Unit)? {
        streamedText = null
        if (!streamReplies) return null
        return { preview ->
            val current = _state.value as? TaskState.Running
            // 未完の文は後半で外国語になる可能性があるため、内容を表示せず日本語の状況だけを示す。
            // 生の部分応答は内部に保ち、中断時のモデル履歴とユーザーが見た表示を分ける。
            if (current?.step == step && interruptedStep != step) {
                streamedText = preview
                _state.value = current.copy(preview = GENERIC_PROGRESS.takeIf { preview.isNotEmpty() })
            }
        }
    }

    /**
     * は do_steps を 1 回実行します。バッチ全体はステップの上限に対して 1 ステップとしてカウントされ、各サブステップはガードおよび実行レコード ([BatchRunner] によって記録される) に対して 1 つのアクションとしてカウントされます。
     * バッチが停止したら、次のステップをメイン モデルに戻します。 null 以外を返すと、タスクが完了したことを示します。
     */
    private suspend fun batch(
        first: Part.ToolCall, skipped: List<Part.ToolResult>, history: MutableList<Message>, specs: List<ToolSpec>, guards: OperationGuards,
        executor: ActionExecutor, track: StepTrack, step: Int, note: String, tool: String?, reason: String?, narration: String?,
        recordReply: (String?) -> Unit, app: (Place) -> Unit, steerWaiting: () -> Boolean,
        noteVerdicts: (BatchStep, ToolExecution?, List<GuardVerdict>) -> String?,
    ): TaskState? {
        // 閉じたグループのツールをバッチに入れることもできます (通常どおり実行し、単一ステップのようにグループを展開します)
        val (parsed, problem) = BatchRunner.parse(first.argsJson, tools.definitions().map { it.name }.toSet(), tools.batchable())
        // 1 つのバッチは 1 つの画面でのみ作成されます。2 つの画面が混在したバッチ全体は作成されません
        val mixed = parsed?.let { BatchRunner.mixedSurface(it, tools::surfaceOf) }
        val steps = parsed.takeIf { mixed == null }
        val running = TaskState.Running(step, note, tool, steps?.let { RunProgress.Batch(it.size) }, reason, narration)
        if (steps != null) _state.value = running
        val started = now()
        var runner: BatchRunner? = null
        val exec = if (steps == null) ToolExecution(buildJsonObject {
            put("ok", false); put("error", if (mixed != null) "mixed_surface" else "invalid_arguments"); put("message", mixed ?: problem); put("recoverable", true)
        }) else {
            gateState = running
            runner = BatchRunner(tools, guards, executor, noteVerdicts = noteVerdicts, steerWaiting = steerWaiting)
            runner.run(steps, first.id, step)
        }
        val durationMs = now() - started
        val isError = exec.content["ok"] != JsonPrimitive(true)
        history += Message(Role.Tool, listOf(Part.ToolResult(first.id, exec.content.toString(), isError, exec.observations.map { it.tag })) + skipped, Author.Mika)
        // バッチが終了 (または停止) した後、モデルに現在の要素リストのコピーを与えます。要素情報が不足している場合は画面のみ添付となります。
        if (steps != null && ranAnyStep(exec.content)) {
            tools.lookNow()?.let { v ->
                history += Message(Role.User, listOfNotNull(Part.Text(batchViewNote(v.content.toString())), v.image?.let { tools.modelImage(it, v.imageId) }), Author.Mika)
            }
        }
        recordReply(BatchRunner.redactArgs(first.argsJson))
        val picture = exec.frame
        val shotId = picture?.let { executor.recorder.nextShot() }
        // サブステップの後、条件に応じたインターフェイスの待機時間が別途記録され、待機時間はサマリーに含まれます。
        emit(RunEvent.ToolResult(now(), step, first.name, RunEvent.clipResult(Redactor.text(exec.content.toString())), exec.changed, shotId, durationMs,
            waitMs = runner?.waitedMs ?: 0, callId = first.id))
        val currentPlace = exec.place
        currentPlace?.let(app)
        // は故障ではないことの確認が必要ですが（一段ブロックのように故障ヒューズが入りません）、これもメイン機種に引き継がれます。 新たな中断のために停止された
        // バッチも失敗とみなされません。
        val confirm = exec.content["error"] == JsonPrimitive("confirmation_required") || exec.content["reason"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)
        track.after(tools.verb(first.name), !isError || confirm, runner?.verdicts?.isNotEmpty() == true || exec.content["advice"] != null, exec.changed, false, currentPlace?.name)
        // バッチ停止 (チェック失敗、確認が必要): 次のステップはメイン モデルに引き渡すことです
        if (isError) track.markRecovery()
        if (picture != null && shotId != null) {
            record { sink.media(shotId, picture) }
            media.getOrPut(step) { mutableListOf() } += shotId
            emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(currentPlace)))
        }
        return guards.stop?.let(StopRules::guard)
    }

    /** do_steps の結果では、少なくとも 1 つのサブステップが実際に実行されます (実行されない停止項目には、executed=false のマークが付けられます)。 */
    private fun ranAnyStep(content: JsonObject): Boolean =
        (content["steps"] as? JsonArray)?.any { (it as? JsonObject)?.get("executed") != JsonPrimitive(false) } == true

    /**
     * ユーザーが同意したがまだ使用していない操作、または回答されたフォローアップの質問 ([answered] がその質問であり、その後何か他のことをした場合は無効です): 引き渡す前にまだ残っている次の質問に回答してください。
     * のどれも null ではありません。
     */
    private fun owedFollowUp(answered: String?): String? {
        confirmations?.granted?.let { r ->
            val what = listOfNotNull(r.action.name, r.target.takeIf(String::isNotBlank), r.content.takeIf(String::isNotBlank)?.let { "「$it」" }).joinToString(" ")
            return Feedback.format("followup_approved", "action" to what)
        }
        return answered?.let { Feedback.format("followup_answered", "prompt" to it.take(200)) }
    }

    /**
     * ステップ [step] の応答では、ツールは調整されません。テキスト [answer] は最終的な回答です: リスク別チェック ([AnswerReview])。ソースはコード内にあります。
     * はチェックに一致しません。返せる場合 ([history] が null ではなく、このタスクがまだ返されていない)、モデルに一度通知し、null を返して続行します。
     * それ以外は通常通りのお渡しとなり、欠品部分は未確認とさせていただきます。 [facts] は、チェックするタスクのステータスです ([ReviewFacts] を参照)。
     */
    private suspend fun conclude(task: String, answer: String, step: Int, facts: ReviewFacts, history: MutableList<Message>?): TaskState? {
        if (!reviewFinalAnswer) {
            val sources = review.sourcesOnly(task, answer, media)
            if (sources.isNotEmpty()) emit(RunEvent.Sources(now(), sources))
            return TaskState.Succeeded(answer, verified = false, sources = sources, reviewed = false)
        }
        val r = review.review(task, answer, step, facts, earlierGoals, media) {
            _state.value = TaskState.Running(step, VERIFYING_NOTE, reason = VERIFYING_NOTE,
                preview = (_state.value as? TaskState.Running)?.preview)
        }
        val missing = r.unconfirmed
        if (missing.isNotEmpty() && history != null && !gapsNudged) {
            gapsNudged = true
            TurnPolicy.unconfirmed(r.gaps, r.modelObjections).let { history += ctx.notice(it.source, it.text) }
            return null
        }
        if (missing.isNotEmpty()) emit(RunEvent.Unconfirmed(now(), step, missing.map(::detail)))
        if (r.sources.isNotEmpty()) emit(RunEvent.Sources(now(), r.sources))
        return if (missing.isEmpty()) TaskState.Succeeded(answer, r.verified, r.note, r.sources, reviewed = r.reviewed)
        else TaskState.Succeeded(answer, verified = false, note = UNCONFIRMED_NOTE + missing.joinToString("、"), sources = r.sources, unconfirmed = missing)
    }

    /**
     * ステップ数を使い果たした後、最終ラウンドが与えられ、ツールだけが残ります。 end_not_done: 完了したらテキストで回答します (通常どおりチェックしますが、返されず、未確認のものは未確認としてマークされます)。
     * それができない場合は、確認した内容を正直に話してください。このラウンドでは何も与えられず (または切り捨てられ)、ステップ数はまだ使い果たされています。
     */
    private suspend fun finalTurn(
        task: String, history: MutableList<Message>, specs: List<ToolSpec>, step: Int, labels: Map<String, String>, place: Place?, recorder: ActionRecorder,
        facts: ReviewFacts, inbox: RunInbox?,
    ): TaskState {
        TurnPolicy.finalTurn().let { history += ctx.notice(it.source, it.text) }
        repeat(3) { attempt ->
            appendSteers(history, inbox)
            appendRuntimeState(history, place, labels, step)
            _state.value = TaskState.Running(step, "")
            val asked = now()
            val reply = try {
                caller.ask(history, TurnPolicy.finalTools(specs, tools::verb), step, onText = previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.stepLimit(maxSteps)
            }
            val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            history += Message(Role.Assistant, reply.parts, Author.Mika)
            val calls = reply.parts.filterIsInstance<Part.ToolCall>()
            val call = calls.firstOrNull { tools.verb(it.name) in TurnPolicy.FINAL_VERBS }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(displayReply(text, calls.isNotEmpty()))), call?.name, call?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = false) },
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))
            // 仕上げモデルは、生成中に補間を受ける場合もあります。答えを最大 2 回繰り返し、残りを次のタスクに渡します。
            if (attempt < 2 && keepFinished(history, inbox)) return@repeat
            if (call == null) {
                if (calls.isNotEmpty() || text.isEmpty() || reply.stopReason == StopReason.MaxTokens) return StopRules.stepLimit(maxSteps)
                // 最終ターンでも日本語でない回答はそのまま渡さない(差し戻すステップは残っていないため日本語で失敗を返す)
                ReplyLanguage.mismatch(text)?.let { return StopRules.languageNotJapanese(it, facts) }
                return conclude(task, text, step, facts, history = null)!!
            }
            val started = now()
            // 諦めて電話機は変えないでください
            val exec = tools.executeRead(call)
            recorder.result(step, call.name, tools.verb(call.name), exec.content, exec.record, null, null, now() - started, "model", call.id)
            return StopRules.afterFinalTurn(exec.outcome, maxSteps)
        }
        return StopRules.stepLimit(maxSteps)
    }

    companion object {
        private const val GENERIC_PROGRESS = "処理を続けています。"
        private const val CHECKING_JAPANESE = "日本語の回答を確認しています。"

        /** 元の応答は履歴と照合に保持し、採用前の表示には日本語の説明だけを渡す。 */
        private fun displayReply(text: String, hasTools: Boolean): String = when {
            text.isEmpty() -> ""
            JapaneseOutput.isJapaneseProse(text) -> text
            hasTools -> GENERIC_PROGRESS
            else -> CHECKING_JAPANESE
        }

        const val STEER_MARKER = "<user_interjection>"
        const val DEFAULT_MAX_STEPS = 60
        /** 通常のタスクには固定ステップカットオフがありません。明示的なステップ予算は、上限が指定された評価およびサブタスクにのみ使用されます。 */
        const val UNLIMITED_STEPS = Int.MAX_VALUE
        /** 照合中に画面へ出すこのステップの説明。 */
        const val VERIFYING_NOTE = "結果を確認しています"
        val APPROVED_STEP_FIRST: String get() = Feedback["approved_step_first"]
        /** 回答を1回差し戻しても一致しなかった箇所について、結末説明の冒頭に付ける(画面では [TaskState.Succeeded.unconfirmed] を項目別に表示する)。 */
        const val UNCONFIRMED_NOTE = "未確認:"
        /** hand_to_user 支払いのためにユーザーに渡すときの理由。支払いは取り消すことができません。回答は別途ご確認ください。 */
        private const val PAYMENT = "payment"
        const val DEFAULT_INPUT_TIMEOUT_MS = 15 * 60_000L
        /** は、引き継ぎ後にツール結果にリマインダーを返します (これをクリックしてインターフェイス テストを確認します)。 ユーザー */
        val HANDBACK_NOTE: String get() = UserExchange.HANDBACK_NOTE

        /** は「自分でやった」をクリックしました。 */
        val USER_FINISHED_NOTE: String get() = Feedback["user_finished_note"]
    }

    private fun now() = clock()

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private inline fun record(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 記録の失敗はタスクに影響しません
        }
    }

    private fun emit(e: RunEvent) = record { sink.event(e) }

    private fun ended(end: TaskState?): RunEvent.TaskEnded = when (end) {
        // 照合しても確認できなかった場合(未確認として付けた場合を含む): unverified として記録し、評価・会話記録と同じ言い方にする
        is TaskState.Succeeded -> RunEvent.TaskEnded(now(), if (end.doubtful) "unverified" else "succeeded", Redactor.text(end.answer))
        is TaskState.Failed -> RunEvent.TaskEnded(now(), "failed", Redactor.text(end.reason), end.kind.name.lowercase())
        TaskState.Cancelled -> RunEvent.TaskEnded(now(), "cancelled", "キャンセルされました")
        else -> RunEvent.TaskEnded(now(), "error", "タスクが異常終了しました")
    }

    private fun waitingEvent(s: TaskState): RunEvent.State = when (s) {
        is TaskState.Held -> RunEvent.State(now(), s.reason.code, detail(s.reason.why))
        else -> RunEvent.State(now(), s::class.simpleName.orEmpty(), "")
    }
}

/** 名前による Web ページへのアクセスに関するユーザーのステートメント (必須 Web ページ チャネルを評価するときにターゲットの前にも追加されます)。 */
private val WEB_CHANNEL_PREFIXES = listOf("ウェブで", "用网页")
