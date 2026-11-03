package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.agent.CoreSections
import com.wangzan101.mika.core.agent.DO_STEPS_TOOL
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.phone.PhoneCapability
import com.wangzan101.mika.core.cap.sandbox.SandboxCapability
import com.wangzan101.mika.core.cap.sandbox.SANDBOX
import com.wangzan101.mika.core.cap.sandbox.SandboxGroup
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.cap.web.WebCapability
import com.wangzan101.mika.core.cap.web.WebGroup
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.general.GeneralGroup
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.record.RunRecords
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.userscreen.UserScreenSource

/**
 * 1 つのタスクのためのツール: 携帯電話、Web ページ、サンドボックスの 3 つの機能とコア ツールを備えています。ここは複数の能力が同時に認められる唯一の場所です。
 * は、ポート機能がない (ツールが提供されていない) 場合でもインストールされており、名前で宣言を検索するとツールも見つかります。
 */
fun toolDispatcher(
    screen: ScreenPort,
    visionEnabled: Boolean = false,
    /** 読み取り専用の知識とスキルのディレクトリ。空の場合、knowledge_search /Knowledge_read/skill_search/skill_read は提供されません。 */
    skills: GuideCatalog? = null,
    /** 画面を読み取るときは、走行記録用にもう一度スクリーンショットを撮ります（画像が表示できないモデルの場合は画面も保存されます）。 */
    recordFrames: Boolean = false,
    /** 画像表示モデル。空の場合 (設定に画像を表示できるモデルがない場合)、ビジュアル ツールは提供されません。 */
    vision: VisionService? = null,
    /** 内蔵ブラウザ;空の場合、Web ツールは提供されず、モバイル インターフェイスのみが提供されます。 */
    web: WebPort? = null,
    /** Web ページアップロードのワークスペースパスチェック (正規化とシンボリックリンク解決はアプリ側で行われます)。 */
    workspacePath: ((String) -> WorkspacePathCheck)? = null,
    /** Web ページをページごとに読むリズム (ページが切り替わる前の一時停止を補います。[PagePacing] を参照)。デフォルトは等しくありません。 */
    webPacing: () -> PagePacing = { PagePacing.Off },
    /** Web サイトの閲覧速度要件 (ホスト名別)。デフォルトは、[skills] のサイト知識のペースです。 */
    webPlacePace: (String) -> PlacePace = { host -> PlacePace.of(skills?.sitePace(host)) },
    /** Web ページに滞在しているときにページを数回ゆっくりスクロールします ([IdleScroll] を参照)。デフォルトではスクロールしません。 */
    webIdleScroll: () -> IdleScroll? = { null },
    /** アプリのステップバイステップ操作のリズム (画面上のアクションを変更する前に一時停止を補います。[AppPacing] を参照)。デフォルトは等しくありません。 */
    appPacing: () -> AppPacing = { AppPacing.Off },
    /** アプリの閲覧速度要件 (パッケージ名による);デフォルトは、[skills] のアプリ知識のペースです。 */
    appPace: (String) -> PlacePace = { pkg -> PlacePace.of(skills?.appPace(pkg)) },
    /** アプリの新しい画面に留まる場合は、ゆっくりと数回スクロールします ([IdleScroll] を参照)。デフォルトではスクロールしません。 */
    appIdleScroll: () -> IdleScroll? = { null },
    /** 読み取り画面全体で安定した番号: 読み取り画面ではモデル番号と s<N> が表示され、アクションの前にポート番号に変換されます。 */
    stableRefs: Boolean = false,
    /** 書き込み操作が成功したら、インターフェイスが安定するまで待ってから別の画面を読み取り、前の画面との差分を結果の画面フィールドに入力します。暗黙的な [stableRefs]。 */
    inlineScreen: Boolean = false,
    /** は最初にモデル要素テーブルを与えます。明らかに要素情報が不足している場合に限り、自動的に図面が添付されます。アクションは依然として要素の変化をもたらします。 */
    adaptiveVision: Boolean = false,
    /** スピードアップ スイッチ deepLinks: リンクを開くためのツールを提供します。 */
    deepLinks: Boolean = false,
    /** メモリ;および [utterances] は両方とも、memory_search/memory_save が提供される前に提供されます。 今回 */
    memory: MemoryStore? = null,
    profile: ProfileStore? = null,
    /** ユーザーの発言によると、memory_save の引用が検証される予定です。 AgentLoop が追加します。 */
    utterances: UserUtterances? = null,
    /** このミッションが含まれるダイアログ。それをメモリに記録するソース。 */
    conversationId: String? = null,
    /** Linux サンドボックス。サンドボックス ツールは、デバイスがサポートしている場合に提供されます (準備ができていない場合、ツール呼び出しはサンドボックス ツールを待機します)。 */
    sandbox: SandboxPort? = null,
    /** このタスクのワークスペース (会話 ID)。 */
    workspace: () -> String = { "default" },
    /** サンドボックスがコマンドまたはスクリプトの実行を開始したとき (タスク カードに「サンドボックス:...」と表示されます)。 */
    onSandboxCommand: (String) -> Unit = {},
    /** サンドボックス内の `mika progress` によって報告される進捗状況。 */
    onSandboxProgress: (String) -> Unit = {},
    /** サンドボックス ツールは、実行環境の準備が整うのを待機しています (タスク カードには「準備中」と表示されます)。 */
    onSandboxPreparing: () -> Unit = {},
    /** スキルスクリプトの実行開始時。そうでない場合は、[onSandboxCommand] を押して報告してください。 */
    onSandboxScript: ((String) -> Unit)? = null,
    /** は、メイン モデルに送信される画像の統合エンコードに使用されます。空の場合はそのまま発送させていただきます。 */
    imageOps: ImageOps? = vision?.ops,
    /** 展開制約により機能が許可されます。古い呼び出し元は、渡されなかった場合でも今日のツールを受け取ります。 */
    runSpec: RunSpec = RunSpec(RunId("legacy"), "legacy", "legacy"),
    /** このタスクに接続されているすべての外部 MCP エンドポイント (ソースに関係なく、呼び出し元はそれらを 1 か所にまとめます)。それぞれが機能となり、serverId によって区別されます。 */
    mcpServers: List<McpConnection> = emptyList(),
    /** は、スケジュールされたタスクを保存し、それが時間通りに実行できるかどうかを示します ([Scheduled] を参照)。 */
    scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    scheduleAdmin: ScheduleAdmin? = null,
    childHost: ChildRunHost? = null,
    /** この会話でユーザーによって送信された画面。今回、または前のラウンドで画面が持ち込まれたときに view_user_screen が提供されました。 */
    userScreens: UserScreenSource? = null,
    /** ユーザーの個人的なスキル。および [skills]、[utterances] はすべて skill_save が提供される前に提供されます (ユーザーが要求したときに個人スキルを書き留めるか、変更します)。 */
    personalSkills: PersonalSkills? = null,
    /** は、Mika によって設定されたホワイトリスト エントリを変更します。空の場合、settings_list と settings_set は提供されません ([SelfConfig] を参照)。 */
    selfConfig: com.wangzan101.mika.core.settings.SelfConfig? = null,
    /** 保存されたランニング記録。この会話が以前に実行中のラウンドを記録した場合は、history_read (前のラウンドで読み取られた内容を確認) を提供します。 */
    runRecords: RunRecords? = null,
): ToolDispatcher {
    val config = ToolConfig(visionEnabled, skills, recordFrames, vision, inlineScreen, adaptiveVision, memory, profile, utterances, conversationId,
        imageOps, scheduleTrigger, scheduleAdmin, runSpec, childHost, userScreens, personalSkills, selfConfig, runRecords)
    val providers: List<CapabilityProvider> = listOf(
        object : CapabilityProvider {
            override val id = PHONE
            override fun open(spec: RunSpec) = PhoneCapability(screen, deepLinks, appPacing(), appPace, appIdleScroll())
        },
        object : CapabilityProvider {
            override val id = WEB
            override fun open(spec: RunSpec) = WebCapability(web, workspacePath, webPacing(), webPlacePace, webIdleScroll())
        },
        object : CapabilityProvider {
            override val id = SANDBOX
            override fun open(spec: RunSpec) = SandboxCapability(sandbox, workspace, visionEnabled,
                onSandboxCommand, onSandboxProgress, onSandboxPreparing, onSandboxScript)
        },
    ) + mcpServers.map(::McpClientProvider)
    return RunAssembler(providers).assemble(runSpec, config, stableRefs, FORMER_PREFIX, RENAMED, RETIRED, MOVED)
}

/** 名前変更前の各機能ツール名の接頭辞: 携帯電話の場合は接頭辞はなく、Web ページの場合は browser_ になります。 */
private val FORMER_PREFIX = mapOf(PHONE to "", WEB to "browser_")
/** 画面ツールの2回目の名前変更（前→今）時の動詞部分の変化は、携帯電話でもWebページでも同様です。 */
private val SCREEN_VERBS_MOVED = mapOf(
    "dump_elements" to "list_elements", "locate_on_screen" to "find_visually",
    "screen_description" to "describe", "check_screen" to "diagnose",
)

/** プレフィックスに加えて、残りも変更されています (現在の名前 → 最初の名前。2 回目に名前が変更されたスクリーン ツールは、複数のスクリーンで共有される最初の名前でした)。 */
private val RENAMED = mapOf("web_wait_for" to "browser_wait") +
    SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$now" to old } }

/**
 * は名前を完全に変更しました（旧名→現名）。 1 つは知識とスキルの 2 つの列に分かれています。モデルの名前を変更するときにパラメーターに従って選択します ([LegacyNames.replacements] を参照)。
 * 本文には旧名を両方書きます。
 */
private val MOVED: Map<String, List<String>> = mapOf(
    "find_skills" to listOf("knowledge_search", "skill_search"),
    "skill_view" to listOf("knowledge_read", "skill_read"),
    "run_script" to listOf("run_bundled_script"),
    "report_barrier" to listOf("handle_obstacle"),
    "report_failure" to listOf("end_not_done"),
    "request_input" to listOf("ask_user"),
    "user_takeover" to listOf("hand_to_user"),
    "recall_screenshot" to listOf("view_past_screens"),
) + SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$old" to listOf("${it}_$now") } }

/** は削除され、代替ツールはありません (以前の名前 → 現在の実行方法): 以前はツールを使用して結果を提供していましたが、現在は電話をかけずに応答することで回答が得られます。 */
private val RETIRED = mapOf(
    "finish" to "ツールを呼び出さず、回答を通常の文章でユーザーへ返してください",
    "phone_ask_app_ai" to "アプリの画面を自分で読み取ってください（アプリ内AIへの質問機能は利用できません）",
)

/** 名称変更前のツール名→現在の名称（旧ルートや走行記録、個人スキルを読み込む際に使用）。 */
fun legacyNames(): LegacyNames =
    LegacyNames(knownTools(), FORMER_PREFIX, RENAMED, retired = RETIRED, moved = MOVED)

/** 走行記録の読み取りに使用されるツールのステートメント: 名前変更前に書き留められた古い名前も現在のツールとして認識され (アクションと能力は同じ)、古い記録は新しい評価と同じになります。 */
fun recordedTools(): ToolIndex = legacyNames().let { legacy -> ToolIndex(allTools()) { legacy.resolve(it) } }

/** Mito すべてのツールは、モデルに出荷された順序で提供される場合があります。 */
internal fun allTools(): List<AgentTool> =
    GeneralGroup.tools + PhoneCapability.tools() + WebGroup.tools + SandboxGroup.tools

/** ミカが提供するすべてのツールの宣言。 */
fun knownTools(): ToolIndex = ToolIndex(allTools())

/** ミカによって提供されたすべての可能なツール名 (スキルドキュメントに記載されているツール名はそれに応じて検証されています)。 */
fun knownToolNames(): Set<String> = knownTools().names + DO_STEPS_TOOL
