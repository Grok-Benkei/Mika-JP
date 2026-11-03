package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.observation.CORE_SOURCE
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.verify.Evidence
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.ModelImage
import com.wangzan101.mika.core.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.visual.Shots
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.Paced
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.guard.OffScreenGuard

/**
 * ツール呼び出しのエントリ ポイント: パラメーターを解析し、[ToolRegistry] でツールの実行を名前で検索し、すべてのツールに共通の最後の仕上げを行います。
 * （行動後の画面、絵を見て獲得できるクリアポイント、行動リスト、検証資料付属）。特定のツールはすべてそれぞれの能力にあるため、ここでは知りません。
 * は、このタスクの機能に従ってアセンブリ レイヤーによって作成されます。[tools] はすべてのツール (モデルに送信された順序)、[sections] はシステム プロンプト ワードの段落の各グループ (接続順序) です。 インターフェイス機能 (携帯電話、Web ページ) を備えた
 *
 * のインターフェイス ステータスはここにはありません。アセンブリ層は [SurfaceLayer] を与え、ディストリビューターは各呼び出しの前後にそれを介してインターフェイス処理を行うだけです。
 * 各呼び出しの前に、ツールが属する画面を押し、どの画面を操作しているかを書き留めます ([aim])。間違った画面番号を使用した場合は、それをポートに渡さず、使用するツールのセットを直接指定してください。
 */
class ToolDispatcher internal constructor(
    private val capabilities: List<Capability>,
    tools: List<AgentTool>,
    private val sections: List<PromptSections>,
    config: ToolConfig,
    /** インターフェイス機能によって共有されるレイヤーがあります。このタスクのツール コンテキストに従って構築されます (インターフェイスのステータスがコンテキストに組み込まれ、機能ツールはそこから取得されます)。 */
    surfaceLayer: (ToolContext) -> SurfaceLayer,
    /** 名前が変更される前のツール名を認識する方法 ([LegacyNames] を参照)。 */
    formerPrefix: Map<String, String> = emptyMap(),
    renamed: Map<String, String> = emptyMap(),
    /** 古い名前が削除され、代替ツールがありません → 今何をすべきか ([LegacyNames] を参照)。 */
    retired: Map<String, String> = emptyMap(),
    /** は古い名前→現在の名前に完全に変更されました ([LegacyNames] を参照)。 */
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val evidenceLog = EvidenceLog()
    private val shots = Shots()
    internal val ctx = ToolContext(
        config, evidenceLog, ToolIndex(tools), capabilities.firstNotNullOfOrNull { it.scripts }, shots,
    )
    /** には、インターフェイス機能 (携帯電話と Web ページ) によって共有されるレイヤーがあります。インターフェース上のすべてがそれを通過します。 */
    private val surfaces = surfaceLayer(ctx)

    init {
        capabilities.forEach { it.onTaskStart(ctx) }
    }

    /** は、使用後にのみ展開されるグループです。格納されたアビリティごとに 1 つのグループと、スクリーン アビリティによって共有される一般的なツール用の 1 つのグループ ([ToolGroups.SCREENS]、格納されたスクリーンがない場合は常に存在します)。 */
    private val folding: List<String> = capabilities.filter { it.folds }.map { it.id }
    private val screenGroups: Set<String> = capabilities.filter { it.folds && it.family != null }.map { it.id }.toSet()

    private fun groupOf(t: AgentTool): String? = when {
        t.capability in folding -> t.capability
        t.withScreens && screenGroups.isNotEmpty() -> ToolGroups.SCREENS
        else -> null
    }

    private val registry = ToolRegistry(ctx, tools, ::groupOf)

    /** このタスク用に拡張された機能グループ (拡張順)。 */
    private val opened = linkedSetOf<String>()
    /** このタスクで実際に使用された能力グループ (グループ内のツールは調整されています。1 回の使用に十分な入り口はカ​​ウントされません): 次の開始ラウンドがそれに応じて開始されます。 */
    private val usedGroups = linkedSetOf<String>()

    /** このタスクでツールが与えられているが、入り口が 1 つも与えられていないグループ (読み取り専用タスクではコマンドが与えられていない): それを受け入れず、グループ全体が表示されないように最初から展開します。 */
    private val entryless: Set<String> by lazy { folding.filter { registry.members(it).isNotEmpty() && !registry.hasEntry(it) }.toSet() }

    /** 拡張機能グループ ([ToolGroups.SCREENS] を除く)。 */
    val expanded: Set<String> get() = entryless + opened

    /** このタスクで実際に使用された能力グループ。 */
    val used: Set<String> get() = usedGroups.toSet()

    /** は [group] を展開します (このタスク用のツールが提供された後にのみ展開されます)。新しく展開された場合は true を返します。 */
    fun expand(group: String): Boolean = group in folding && group !in entryless && registry.members(group).isNotEmpty() && opened.add(group)

    /**
     * は最初に事前展開されています。前回のラウンドで使用したグループ ([previous]) と、今回役立つと思われる能力 (ワークスペースにファイルを取り込むときのサンドボックスについては、[Capability.preload] を参照)。
     * は、展開されたグループを返します。
     */
    fun preload(task: String, previous: Collection<String>): List<String> =
        capabilities.filter { it.folds && (it.id in previous || it.preload(task)) }.map { it.id }.filter(::expand)

    /** ユーザーはこの文の各能力の説明を削除しました ([Capability.userWords] を参照)。今回彼が入力した入力により、参照行と添付行も削除されました。[com.wangzan101.mika.core.agent.UserWords.typed] を参照してください。 */
    fun userWords(text: String): String = capabilities.fold(text) { t, c -> c.userWords(t) }

    private fun openSet(): Set<String> = expanded.let { e -> if (e.any { it in screenGroups }) e + ToolGroups.SCREENS else e }

    /**
     * 現時点でモデルに送信されるツール: 常に存在する展開されたグループ (全文)、閉じられたグループはアクセスのみを提供し (短い説明)、順序は固定されています ([ToolGroups] を参照)。
     * [screenExtra] はループ自体によって与えられる定義であり、画面のグループとともに表示されます (一度に複数のステップ)。各グループを展開すると[all]。
     */
    fun offeredNow(screenExtra: List<ToolSpec> = emptyList(), all: Boolean = false): List<ToolSpec> =
        registry.layered(if (all) folding.toSet() + ToolGroups.SCREENS else openSet(), if (screenGroups.isEmpty()) emptyMap() else mapOf(ToolGroups.SCREENS to screenExtra)).let {
            if (screenGroups.isEmpty()) it + screenExtra else it
        }

    /** 名前変更前のツール名 → 現在の名前。古い個人的なスキルや実践を読むときに使用されます。古い名前を呼び出すと、新しい名前を求めるプロンプトが表示されます。 */
    val legacy = LegacyNames(ctx.tools, formerPrefix, renamed, retired, moved)

    /** 画像表示モデル。空の場合 (設定に画像を表示できるモデルがない場合)、ビジュアル ツールは提供されません。 */
    val vision: VisionService? get() = ctx.config.vision
    /** のメインモデルは写真にあります。 */
    val seesImages: Boolean get() = ctx.config.visionEnabled
    /** 書き込み操作が成功した後、インターフェイスが安定するまで待ってから別の画面を読み取り、前の画面との差分を結果の画面フィールドに入力します。 */
    val inlineScreen: Boolean get() = ctx.config.inlineScreen
    /** ユーザーの発言によると、memory_save の引用が検証される予定です。 AgentLoop が追加します。 */
    val utterances: UserUtterances? get() = ctx.config.utterances
    private val imageOps: ImageOps? get() = ctx.config.imageOps

    /** を使用して、「利用可能なチャネル」を循環することができます (スキルに Web ページ メソッドがあるかどうかを確認してください)。 */
    internal val skillCatalog: GuideCatalog? get() = ctx.config.skills
    /** 最近使用しているインターフェース機能を持つものの ID。タスクが開始されたとき、それは最初のもの (携帯電話) でした。 */
    val surface: String get() = surfaces.current

    /** 実行ステータスに書き込まれた現在の場所のバージョン (Web ページのモバイル版、コンピューター版)。利用できない場合、または読み取ることができない場合は、null になります。 */
    suspend fun placeMode(): String? = surfaces.mode()

    /** このタスクは、スキル スクリプトを実行するためのツールを提供します (参考資料のメモはそれに基づいて書かれています)。 */
    fun scriptsOffered(): Boolean = ctx.scripts?.offered() == true

    /**
     * システムは、単語の各能力とコアの段落を入力するよう求めます。モデルに送信された同じツール リスト [specs] を使用して、存在するかどうかを判断します。閉じられた能力グループは、展開された後にのみ独自の段落を持ちます。
     * [all] 各グループが展開されるとき (プロンプト ワードのバージョンはすべての出現に従って計算されます)。
     */
    fun promptSections(specs: List<ToolSpec>, all: Boolean = false): List<String> {
        val offered = specs.map { it.name }.toSet()
        val screens = capabilities.filter { it.family != null }.count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val open = if (all) null else capabilities.filter { !it.folds || it.id in expanded }.map { it.id }.toSet()
        val openScreens = capabilities.filter { it.family != null && (open == null || it.id in open) }
            .count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val env = PromptEnv(offered, inlineScreen, screens, open, openScreens)
        return sections.flatMap { it.sections(env) }
    }

    /** 画面を移動しないツールを保護します (このタスクの 1 つのコピー)。そうでない場合は null です。 */
    fun offScreenGuard(): OffScreenGuard? = capabilities.firstNotNullOfOrNull { it.guard() }

    fun definitions(): List<ToolSpec> = registry.definitions()

    /** は画面とページのステータスを変更します (ロック画面/一時停止レベルを通過する必要があります): ツールによって宣言されたリスクによって導入されます。 */
    fun writesScreen(name: String): Boolean = registry.declared(name)?.writesScreen == true

    /** 画面を移動しないツール (サンドボックス タイプ): 画面ガードには入らず、それぞれのガードによって管理されます。結果内の画像はファイルです。 この呼び出しのために */
    fun offScreen(name: String): Boolean = registry.declared(name)?.offScreen == true

    /** モデルによって書き込まれた目的 (追加の理由パラメーター)。ツール自体にはreasonと呼ばれるフィールドがあり、それが書き込まれていないか空の場合はnullになります。 */
    fun callReason(name: String, args: JsonObject): String? {
        if (registry.declared(name)?.def?.ownReason == true) return null
        return (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    /** ツールのステートメントを名前で確認します (アクション、バッチ処理できるかどうか、インターフェイス上のステートメントなど)。 */
    val index: ToolIndex get() = ctx.tools

    /** do_steps に入れることができるツール名 (定義にリストされている順序)。 */
    fun batchable(): List<String> = registry.batchable()

    /** このツールによって実行されるアクション。不明な名前は null です。 */
    fun verb(name: String?): Verb? = ctx.tools.verb(name)

    /** このツールがどの画面で動作するか (画面ツールの機能 ID)。画面ツールでない場合は null です。 */
    fun surfaceOf(name: String): String? = surfaces.surfaceOf(registry.declared(name))

    /** はインターフェイスを移動せず、内部のアクションは実行入り口 (ページ スクリプト) を通じて 1 つずつ返されます。 */
    fun composite(name: String): Boolean = registry.declared(name)?.composite == true

    /** このツールのターゲットを表示する方法の決定は元に戻せません。外部として宣言されたツールのみがこれを持っています。 */
    fun gateTarget(name: String): GateTarget? = registry.declared(name)?.takeIf { it.risk == Risk.External }?.gateTarget

    /** は、ディストリビュータ モデルに、ループの各トリミングと圧縮履歴の後でも読み取り (数値による) が表示されることを伝えます。差額の基礎が含まれない場合は、全額が送金されます。 */
    fun setVisibleObservations(readIds: Set<String>) = surfaces.setVisible(readIds)

    /**
     * は呼び出しで始まります。操作している画面に注意してください (画面ツールは独自の画面であり、サーフェス パラメータを持つ一般的なツールはパラメータ化されています。残りは現在の画面を使用します)。
     * から対応する [release] まで。実行エントリーは判定前に一度入力され、配信時に再度入力されます。
     */
    fun aim(name: String, args: JsonObject) {
        surfaces.enter(registry.declared(name)?.aimAt(args))
    }

    /** 通話が終了します。成功した画面は、最後に操作された画面とみなされます (呼び出しが失敗してもデフォルト値は変更されません)。 */
    fun release(succeeded: Boolean) = surfaces.leave(succeeded)

    /**
     * 別の画面の番号を使用して、この画面のツールを操作します。ポートに渡されない場合、結果は、その番号がどの画面に属し、どのツールを使用する必要があるかを示す、wrong_surface になります。
     * 番号がどの画面に属しているかを認識できない場合 (期限切れが長すぎるか、スペルが間違っている場合)、番号は null となり、通常どおりツールに渡されます (期限切れとして処理されます)。
     */
    fun misaimed(name: String, args: JsonObject): ToolExecution? = registry.declared(name)?.let { surfaces.misaimed(it, args, ctx.tools) }

    /** 読み取り専用呼び出し (画面読み取り、失敗レポートなど): 画面、ページ、またはサンドボックスを変更するツール ステートメントはすべて拒否され、それらは実行入口を通じてのみ実行できます。 */
    suspend fun executeRead(call: Part.ToolCall, step: Int? = null): ToolExecution {
        val tool = registry.declared(call.name)
        if (tool != null && !tool.readOnly) {
            return fail("not_a_read", Feedback.format("not_a_read", "tool" to call.name), recoverable = false)
        }
        return dispatch(call, step, null)
    }

    /**
     * [pass] 実行入り口のみが利用可能です (警備員、レベル、不可逆判定、記録がすべて存在します)。
     * [step]: ループのラウンド数は、折り畳まれた概要の「N 番目のステップ」と一致します。指定しない場合、実行された呼び出しの数がカウントされます。
     * [sub]: web_script 内のアクションは、web_script を介してループに戻ります (ガード、レベル、およびレコードは通常どおりです)。指定しない場合、スクリプト内のアクションは実行されません。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(call: Part.ToolCall, pass: ActionPass, step: Int? = null, sub: SubActions? = null): ToolExecution = dispatch(call, step, sub)

    private var executed = 0

    private suspend fun dispatch(call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        surfaces.skip()
        val tool = registry[call.name] ?: return unknown(call.name, parseArgs(call.argsJson))
        // クローズド グループ内のツール (入り口、または表示せずにメモリから調整されたモデル) は通常どおり実行されます。そのグループが展開され、その結果、次の呼び出しからグループ全体が表示されることがわかります。
        val group = groupOf(tool)
        if (group != null && group != ToolGroups.SCREENS && tool.entry != Entry.Alone) usedGroups += group
        val opening = when {
            group == null || tool.entry == Entry.Alone -> null
            group == ToolGroups.SCREENS -> surfaces.current.takeIf { it in screenGroups }
            else -> group
        }?.takeIf(::expand)
        val result = dispatchTool(tool, call, step, sub)
        return if (opening == null) result else result.with(content = JsonObject(result.content + (MORE_TOOLS to JsonPrimitive(Feedback.format("group_opened",
            "group" to opening, "examples" to registry.members(opening).filter { registry[it]?.entry == null }.take(4).joinToString())))))
    }

    private suspend fun dispatchTool(tool: AgentTool, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        val args = try {
            val el = if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson)
            Args(tool.def(ctx), el as? JsonObject ?: throw ArgError("arguments", "must be a JSON object"))
        } catch (e: ArgError) {
            return invalid(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            return invalid(ArgError("arguments", "not valid JSON"))
        }
        shots.idAsSnapshot(args.o)?.let { return it }
        misaimed(call.name, args.o)?.let { return it }
        aim(call.name, args.o)
        var ok = false
        try {
            return run(tool, args, call, step, sub).also { ok = it.content["ok"] == JsonPrimitive(true) }
        } finally {
            release(ok)
        }
    }

    private suspend fun run(tool: AgentTool, args: Args, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        executed++
        ctx.currentStep = step ?: executed
        evidenceLog.step = ctx.currentStep
        ctx.callId = call.id
        ctx.sub = sub
        // ページ スクリプト内のアクションもこれを通過します。各アクションは自分の滞在を記憶しており、外側のレイヤーには内側のレイヤーが含まれています。
        val outerWait = ctx.pacedWaitMs
        val outerIdle = ctx.idleScrolls
        ctx.pacedWaitMs = 0
        ctx.idleScrolls = 0
        // インターフェイスでの準備 (ルート上の目標、インターフェイスでのアクションを変更する前に確認してください) と終了はすべてインターフェイスの片側で行われます
        val result = surfaces.around(tool, args.o, inlineScreen) { reportsEffect ->
            ctx.reportsEffect = reportsEffect
            try {
                if (!tool.yieldsToSteer) tool.execute(args, ctx)
                else waitOrSteer { tool.execute(args, ctx) } ?: failCode(SKIPPED_FOR_NEW_MESSAGE)
            } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
                throw e
            } catch (e: ArgError) {
                invalid(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("operation_failed", Feedback.format("operation_failed", "tool" to tool.name, "error" to (e.message ?: e::class.simpleName)))
            } finally {
                ctx.reportsEffect = false
            }
        }
        val waited = ctx.pacedWaitMs
        ctx.pacedWaitMs = outerWait + waited
        val idled = ctx.idleScrolls
        ctx.idleScrolls = outerIdle + idled
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (tool.logged) evidenceLog.logAction(stepLabel(), tool.name, tool.logTarget(args.o, result, ctx), tool.logTyped(args.o, result), result, tool.wideTarget)
        // 読み取ったもの（結果で能力によって与えられるもの、結果に応じて別途与えられる道具）を台帳に記録し、結果を台帳の項目に置き換えます。
        val seen = remember(result.observations + tool.observations(args.o, result, evidenceLog), tool.capability)
        // 入力ボックスに入力された単語 (ポートはパスワード ボックスではないことを明確に示しています): 画面上の入力ボックスを読んだ後、エコーは答えの証拠を提供しません。
        if (tool.verb == Verb.TypeText && ok && result.content["secret"] == JsonPrimitive(false)) args.o.argStr("text")?.let(evidenceLog::noteTyped)
        // 滞在中にページをゆっくりスクロールしてください。走行記録のみが記録され、モデルは付与されません。
        val record = if (idled > 0) JsonObject(result.record + ("idleScrolls" to JsonPrimitive(idled))) else result.record
        return result.with(observations = seen, place = placeAfter(tool.verb, result.content), waitMs = waited, record = record)
    }

    /** が台帳に記録されます。ソースが書き込まれていない場合は、[capability] を押します (コア ツールは core)。台帳に記録されている項目に戻り、各項目を [observer] に渡します。 */
    private fun remember(observations: List<Observation>, capability: String?): List<Observation> =
        observations.mapNotNull { o -> evidenceLog.observe(if (o.source != null) o else o.copy(source = capability ?: CORE_SOURCE))?.also { observer?.invoke(it) } }

    /** 各観察は証拠台帳に記録されます (実行記録はそれに応じて記録され、評価により台帳を再構築できます)。ループによって設定されます。 */
    var observer: ((Observation) -> Unit)? = null

    /** ツールを認識できません。改名前の名前の場合、現在は何と呼ばれているか教えてください（機種名を新しい名前に置き換えないでください）。 */
    private fun unknown(name: String, args: JsonObject) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "unknown_tool"); put("tool", name)
        val now = legacy.replacements(name, args).filter { registry[it] != null }
        val instead = legacy.retiredAs(name)
        put("message", if (instead != null) Feedback.format("unknown_tool_retired", "name" to name, "instead" to instead)
            else if (now.isNotEmpty()) Feedback.format("unknown_tool_renamed", "name" to name, "tools" to now.joinToString(" または "))
            else Feedback.format("unknown_tool", "name" to name, "tools" to (registry.listedNames().joinToString())))
        put("recoverable", true)
    })

    /** は証拠台帳の現在の位置です ([EvidenceLedger.lastId] を参照)。アクションの前にそれを書き留めてから、[readSince] を使用してアクションの後に再読み取りされたかどうかを確認します。 */
    fun ledgerMark(): Int = evidenceLog.ledger.lastId

    fun readSince(mark: Int): Boolean = evidenceLog.ledger.readSince(mark)

    /** このタスクに登録されているツールを名前で検索します。不明な場合は null になります。 */
    internal fun declared(name: String): AgentTool? = registry.declared(name)

    /** ツール宣言のリスク。不明な名前の場合は null。 */
    fun declaredRisk(name: String): Risk? = registry.declared(name)?.risk
    /** 画面外のツールが今回の引数について申告した確認リスク。申告がなければ null。 */
    fun approvalRisk(name: String, args: JsonObject = JsonObject(emptyMap())): com.wangzan101.mika.core.action.Risk? =
        registry.declared(name)?.approvalRisk(args)

    /** ask_user はユーザーの回答を取得します。チェッカーはユーザーが指定した事実を比較します。 */
    /** ユーザーから送信された画面 (画面上の文言) も、回答に記載されている画面上の内容に基づいています。 */
    fun noteUserScreen(screen: com.wangzan101.mika.core.userscreen.UserScreen) {
        if (screen.blocked || screen.lines.isEmpty()) return
        evidenceLog.observe(Observation(ObsKind.Read, screen.lines.joinToString("\n") { it.text }, source = CORE_SOURCE,
            place = screen.packageName?.let(com.wangzan101.mika.core.observation.Place::app),
            trust = com.wangzan101.mika.core.observation.Trust.Untrusted))
    }

    fun noteUserAnswer(prompt: String, answer: String) {
        evidenceLog.observe(Observation(ObsKind.UserSaid, "ユーザーへの質問「${prompt.take(200)}」、回答:$answer", source = CORE_SOURCE))
        capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /**
     * 検証者のための証拠: 最近使用された能力 ([Capability.freshLook]、モデルが手に持っているものには影響しません) と、このミッションの証拠台帳とアクション リストをもう一度見てください。
     * [withImage] 1 つのフレームと一緒に;アンチスクリーンショットページはキャプチャされません。 [fresh] が false の場合、(台帳が文字通りに検証されている限り) それを確認しません。
     */
    suspend fun evidence(withImage: Boolean = false, fresh: Boolean = true): Evidence {
        val e = evidenceLog
        // 今は見えなくても、以前読んだものを渡して検証者が判断します。
        val look = if (fresh) capabilities.firstOrNull { it.id == surface }?.freshLook(ctx, withImage) else null
        return Evidence(look?.observations.orEmpty(), look?.image, e.ledger.copy(), e.actionLog.toList(), look?.protected == true, ctx.appOpened,
            e.takeovers.toList(), userFacts = e.userFacts.toList())
    }

    /** がオンの場合、ターゲット要素とアンカー ポイントは書き込み操作が実行される前に取得されます (再生スイッチがオンのときにループによって設定されます)。 */
    var captureTargets: Boolean
        get() = surfaces.captureTargets
        set(v) { surfaces.captureTargets = v }

    /** 実行記録は、現時点でのモデル番号に対応するポート番号を使用します (インターフェイス側で指定されます。そうでない場合は空です)。 */
    fun portRecord(tool: String, args: JsonObject): JsonObject = surfaces.portRecord(registry.declared(tool), args)

    /** [timeoutMs]まで、現在使用している場所が安定するまで待ちます。ステータスを読み取れない場合は通常どおりに戻ります。 */
    suspend fun settle(timeoutMs: Long) = surfaces.settle(timeoutMs)

    /** 現時点で使用されている場所で見られる単語 (要素ごとに 1 つ、登録番号なし、モデルの手にあるものの変化なし)。読み取れない場合は空です。 */
    suspend fun visibleText(): List<String> = surfaces.visibleText()

    /** は、現在使用中の場所で [until] が確立されるまで待機します (または、事故が発生し、制限時間 [interrupted] に達する)。 [SurfaceLayer.waitUntil]を参照してください。 */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome = surfaces.waitUntil(until, clock, interrupted)

    /** 現時点では、この画面で外部アクション (送信、送信) を実行する必要があります。この画面の機能 ([Capability.onOutwardStart]) にそれを与え、完了後に [outwardEnd] が続く必要があります。 */
    fun outwardStart(site: String) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardStart(ctx, site)
    }

    /** 外部アクションが完了しました ([done]: 完了)。 */
    fun outwardEnd(site: String, done: Boolean) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardEnd(ctx, site, done)
    }

    /** 次に、この画面で画面を変更します。この画面 ([Capability.beforeWrite]) の機能に従ってしばらく停止します。 */
    internal suspend fun beforeWrite(tool: String, outward: Boolean): Paced =
        capabilities.firstOrNull { it.id == surface }?.beforeWrite(ctx, verb(tool), outward) ?: Paced.NONE

    /** そのアクションは完了しました。この画面 ([Capability.afterWrite]) の機能に任せます。 */
    internal fun afterWrite(tool: String, result: JsonObject) {
        capabilities.firstOrNull { it.id == surface }?.afterWrite(ctx, verb(tool), result)
    }

    /** 引き継ぎの結果はハンドオーバー後にわかり、ループによってアクション リストに追加されます (検証者はこれに基づいて引き継ぎが実際に行われたかどうかを知ります)。 */
    fun noteTakeover(reason: String, back: HandBack) {
        val effect = when (back.outcome) {
            HandBackOutcome.complete -> if (back.userFinished) "ユーザーは自分で完了したと回答しました（未確認）" else "ユーザーから操作が戻りました（未確認）"
            HandBackOutcome.skipped -> "ユーザーによる操作がスキップされました"
            HandBackOutcome.timeout -> "ユーザーによる操作がないままタイムアウトしました"
            HandBackOutcome.aborted -> "タスクがキャンセルされました"
        }
        evidenceLog.noteTakeover("${stepLabel()} ${ctx.tools.nameOf(Verb.HandToUser)}($reason) $effect")
        if (back.outcome == HandBackOutcome.complete) capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /** アクション リストの「N 番目のステップ」は、サイクルのラウンドと一致しています (バッチ内のいくつかのステップが同じラウンドに属します)。再生にラウンドはありません。 */
    private fun stepLabel() = if (ctx.currentStep > 0) "ステップ${ctx.currentStep}" else "記録した手順を再実行"

    /** はメイン モデルに送信されるピクチャで、[ModelImage] に従って均一にコード化されています。 [screenshotId] は画面のレビュー番号です。 */
    fun modelImage(img: ByteArray, screenshotId: Int?): Part.Image = ModelImage.part(img, imageOps, screenshotId)

    /**
     * メインモデルは、画像が現在使用されている場所でフレームとその番号を表示できます（後で表示できます）。保護されたページや支払いページには与えられず、何も開かれていない場合や取得できない場合は null になります。
     * ハンドオフを再生するときに使用します。
     */
    suspend fun imageNow(): Pair<ByteArray, Int>? = surfaces.imageNow()

    /** は次のようになります。モデルの内容 ([content]、読み取りと同じ登録番号) と画面 ([image]、そうでない場合は null) が同時に表示されます。 */
    class Glance(val content: JsonObject, val image: ByteArray?, val imageId: Int, val observations: List<Observation> = emptyList())

    /** メイン モデルは、画像を表示するときにアクションを確認できます。コンテンツと画像は同時に表示されます。何も開かれていない場合は null です。読み取られた内容は台帳に記録されます。 */
    suspend fun lookNow(): Glance? = surfaces.viewNow()?.let { v -> Glance(v.content, v.image, v.imageId, remember(v.observations, surface)) }

    /** リンクを開くツールは、この時点でこの一連のパラメーターを解放しますか (リンク ルールと参考資料を参照するだけで、実行はしません)。 */
    fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean =
        LinkPolicy.check(action, uri, pkg) == null && (action != null || (uri to pkg) in ctx.allowedLinks)

    /** ループは、リンクされた参照がモデルに渡されるときに登録します。 */
    fun allowLinks(links: List<Link>) {
        links.forEach { ctx.allowedLinks += it.uri to it.packageName }
    }

    /** は、このタスクの Memory_save によって保存されました (結果カードには「記憶されている」と表示され、取り消すことができます)。 */
    val savedChanges: List<Change> get() = ctx.savedChanges

    private companion object {
        /** グループが展開されたときに結果で指定されるフィールド。 */
        const val MORE_TOOLS = "moreTools"
    }

    /** テスト用: 記録に写真が残っているかどうかを確認します。 */
    internal fun keepsRecallImages(): Boolean = shots.recalls.any { it.png != null }
}
