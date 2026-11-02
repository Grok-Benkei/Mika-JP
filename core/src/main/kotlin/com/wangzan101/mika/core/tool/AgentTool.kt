package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import kotlinx.serialization.json.JsonObject

/**
 * ツール自体が、外部の世界に対して何を行うかを宣言します。
 * 読み取り: 読み取り専用 (画面の読み取り、Web ページの読み取り、メモリのチェック、失敗のレポート)、読み取り専用呼び出しを直接行うことができます。
 * 書き込み: 画面、ページ、またはサンドボックスの変更は、実行エントリを通じてのみ実行できます。
 * 外部: インターフェイスの変更に加えて、対象の要素に応じて発行、支払い、送信することも可能です (不可逆性の判断は対象によって異なります)。
 */
enum class Risk { Read, Write, External }

/** 入口ツール: [Opens] は呼び出し後にグループを拡張します。 [Alone] だけで十分ですが (アプリの一覧表示、URL のテキストの読み取り)、通話は拡張されません。 */
enum class Entry { Opens, Alone }

/** は他の呼び出しと同時に実行できますか。デフォルトは一度に 1 つです。 */
enum class Concurrency { Serial, ParallelSafe }

/**
 * ツールはどのようなアクションを実行しますか?アクション (ガード、クロップ、ルート、バッチ、分業モデルなど) に基づいて判断が行われる場合は、ツール名ではなくすべてのアクションに注目します。
 * 画面上のアクションと画面の読み取りには、それぞれ複数のインターフェイスに同じ動詞を持つツールがあります。
 */
enum class Verb {
    Observe, ListElements, Query, Collect, PhoneRun, PhoneFind, PhoneExtract, PhoneDialog, PhoneEvent, PhoneGesture, PhoneCommand, Screenshot, ViewPastScreens, ViewUserScreen, Describe, FindVisually, Diagnose,
    Tap, TypeText, ClearText, Scroll, Swipe, Back, PressKey,
    ListApps, LaunchApp, OpenLink, ShareText, AppAfterTask,
    SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent, SetWheel,
    Navigate, ReadPage, EvaluateJs, SiteCommand, Tabs, Downloads, Upload, SiteMode, Script, Interact, Capture, WaitFor, KeepOpen,
    RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile,
    Wait, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
    KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, ProfileEdit, HistoryRead,
    ScheduleTask,
    SpawnAgent, SettingsList, SettingsSet,
}

/** ツール宣言が名前で見つかりました。 */
interface ToolInfo {
    val name: String
    val verb: Verb
    /** それが属する機能の ID。コア独自のツールの場合は null。 */
    val capability: String?
    /** は、複数のステップ (do_steps) を一度に入力できます。 */
    val batchable: Boolean
    /** 体験ルートを記憶して再生します。 */
    val routable: Boolean
    /** はインターフェースでこう言いました。そうでない場合は、ツール名が表示されます。 サンドボックス内の */
    val label: String?
    /** `mika phone <操作>` は調整できます (携帯電話の機能、結果はテキストです)。残りは拒否されます。 */
    val sandboxCallable: Boolean get() = false
}

/**
 * 名前による検索ツール宣言。不明な名前は null です。 [former] 名前変更前の名前を現在の名前として認識します (古い実行レコードを読み取るときに使用されます。LegacyNames を参照)。
 * [names] 現在登録されている名前のみ。
 */
class ToolIndex(tools: Collection<ToolInfo>, private val former: (String) -> String? = { null }) {
    private val byName: Map<String, ToolInfo> = tools.associateBy { it.name }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String?): ToolInfo? = name?.let { byName[it] ?: former(it)?.let(byName::get) }

    fun verb(name: String?): Verb? = get(name)?.verb

    /** このアクションを実行するツールの名前: [capability]。与えられた場合、その能力を持つものだけが見つかります (コアツールが null のグループ)。指定されていない場合は、最初に登録されたものが採用されます。 */
    fun nameOf(verb: Verb, capability: String? = null): String? =
        byName.values.firstOrNull { it.verb == verb && (capability == null || it.capability == capability) }?.name

    /** 画面機能 [from] [to] のセットで同じアクションを実行するツールの名前は何ですか。そうでない場合は null です。 */
    fun counterpart(name: String, to: String): String? = get(name)?.let { nameOf(it.verb, to) }
}

/**
 * ツール: 定義 (名前、説明、パラメーター) + 宣言されたリスク + 実行。新しいツール = 実装を追加し、対応する機能に登録します。配布者は具体的なツールを知りません。
 * のステータスは [ToolContext] であり、ツール自体にはステータスがありません。
 */
internal interface AgentTool : ToolInfo {
    val def: ToolDef
    override val capability: String? get() = null
    override val batchable: Boolean get() = false
    override val routable: Boolean get() = false
    override val label: String? get() = null
    /** は画面を移動しません (サンドボックス タイプ): 画面レベルをロックし、スクリーン ガードに入りません。結果の画像はファイルであり、画面イメージではありません。 */
    val offScreen: Boolean get() = false
    val risk: Risk
    /** 非インターフェイス機能は承認リスクを直接宣言できます。インターフェイス ツールは依然としてターゲットと現在の観察に基づいて判断されます。 */
    val approvalRisk: ApprovalRisk? get() = null
    val concurrency: Concurrency get() = Concurrency.Serial
    /** 取り消しできない判断は、この呼び出しのターゲットが何であるかによって異なります。外部ツールを指定する必要があり、残りは null (判定されません)。 */
    val gateTarget: GateTarget? get() = null
    /** はインターフェイスを移動せず、内部のアクションは実行入り口 (ページ スクリプト) を通じて 1 つずつ返されます。ただし、ロック画面レベルを読み取り専用呼び出しとして呼び出すことはできません。 */
    val composite: Boolean get() = false
    /**
     * 能力グループは、閉じたときに入り口も提供します ([ToolGroups] を参照)。閉じたときに短い説明 (prompts/entries/<ツール名>.md) を使用し、展開して全文に戻ります。
     * は null エントリではありません。
     */
    val entry: Entry? get() = null
    /** はどの能力にも属さず、画面を持つグループが展開された場合にのみ提供される汎用ツール（乗っ取り、障害の報告、画面の確認）です。 */
    val withScreens: Boolean get() = false
    /**
     * 通話をキャンセルしても、何も失われることはありません (インターフェイスを移動せずに、ただ待つか、画像モデルを見るように頼むだけです): ユーザーが中断したらすぐにキャンセルしてください。
     * 結果は [com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE] として報告されます。モデルは最初にこの文を読み取り、必要に応じて再度調整します。
     */
    val yieldsToSteer: Boolean get() = false

    override val name: String get() = def.name
    /** はモデルの定義に送信されます。このタスクに従って、いくつかの画面とそれをカバーする追加のパラメーター ツール (サーフェス) があります。 */
    fun def(ctx: ToolContext): ToolDef = def
    /** は、どのファイルからのものかを示します (prompts/tools/<file>.md)。デフォルトの名前は同じですが、複数のツールがテンプレートを共有する場合は別々に記述されます。 */
    val promptFile: String get() = name
    val description: String get() = def.description
    /** パラメーターの JSON スキーマ (モデルに送信されるもの)。 */
    val params: String get() = def.spec().parametersJsonSchema

    /**
     * この呼び出しがどの画面で機能するか (画面機能の ID): 画面ツールは独自のものです。これは、パラメータを参照するための一般ツールのパラメータによってどの画面が決定されるかを指します。残りは null (変更されていない) です。 したがって、
     * 分配器は、通話前にどの画面を操作しているかを記録します。
     */
    fun aimAt(o: JsonObject): String? = capability

    /** このタスクを呼び出すことができるかどうか (構成を参照: ピクチャ モデル、スキル カタログ、Web ポートがあるかどうか...)。 現時点では、 */
    fun available(ctx: ToolContext): Boolean = true

    /** をモデルに送信されるツール リストに入れることはできません。デフォルトは [available] と同じです。 */
    fun offered(ctx: ToolContext): Boolean = available(ctx)

    suspend fun execute(a: Args, ctx: ToolContext): ToolExecution

    /** 成功後、アクション後の画面が結果に含まれます（アクションのある画面がオンの場合）、画像を表示できるメイン モデルにはフレームが付けられます。 */
    val showsScreenAfter: Boolean get() = false

    /**
     * インターフェイスのアクションを変更します (クリック、入力、クリア、キー、リターン、スライド...): ディストリビューターはアクションの前後の画面 (要素テーブル + 画面) を見て、
     * 実際のエフェクトを結果のエフェクト フィールドに書き込みます (インターフェイス ステータス側の ActionEffect を参照)。画面を読み取るだけで、他のアクションは実行されません。
     */
    val reportsEffect: Boolean get() = false

    /** はアクション リスト (何かを変更するアクション、または携帯電話、Web ページ、サンドボックス内の場所に移動するアクション) を入力します。 [wideTarget] はオブジェクトをより長く保持します。 */
    val logged: Boolean get() = false
    val wideTarget: Boolean get() = false
    /** はアクション リストに書かれたオブジェクトです。要素の名前を数字で書きたい場合は、この画面の最新の画面読み取りを[ctx]から取得してください。 */
    fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = null
    /** アクションリストに記載された入力文字列(パスワードボックスではないことが確認された場合のみ)。 */
    fun logTyped(o: JsonObject, r: ToolExecution): String? = null

    /**
     * 結果のモデルは最終回答で参照される可能性がありますが、実行時に他の観察は提供されません (どの Web ページが開かれているか、コマンドの出力など)。ディストリビュータはそれらを結果の結果と組み合わせます
     * [ToolExecution.observations] も検証資料に併記されます。 [log] には、少量のクロスツール ステータス (リストされたアプリ名、書き込まれたファイル) が含まれています。
     */
    fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> = emptyList()
}

/** 画面とページのステータスを変更できるツール: ロック画面/一時停止レベルを通過します。記載されているリスクに従って、画面以外のスクリプトやページ スクリプトはリスクに含まれません。 */
internal val AgentTool.writesScreen: Boolean get() = risk != Risk.Read && !offScreen && !composite

/** 読み取り専用の呼び出しを行うことができます。読み取り専用、画面の移動 (画面を移動しないものにはガードがあります)、ページ スクリプトはできません。 */
internal val AgentTool.readOnly: Boolean get() = risk == Risk.Read && !offScreen && !composite
