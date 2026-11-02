package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.SurfaceLayer
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.writesScreen
import com.wangzan101.mika.core.visual.Shots
import com.wangzan101.mika.core.vision.LocateResult
import com.wangzan101.mika.core.vision.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * アクションがどこに該当するかはインターフェイス側で指定されます。執行入口の不可逆的な判決と承認はこう読むしかない。
 * [args] は判定に使用されるパラメータ (画像内にある @vN をクリックすると、登録されている座標と表示される内容に置き換えます)、[aimed] はモデルのビュー (snapshotId で見つかった画面読み取り、
 * は番号が指定されていない場合の座標変換に使用される画面です)、[bySight] は画像を見て得られた点の位置を示し、[seen] はビジュアルモデルを確認して確認した後に表示されるテキストです。
 * [readAgeMs] は、画面の読み取りからの経過時間です (不明、null です)。 [now] この時点でもう一度見てください (不可逆的であると判断された場合にのみ読み取ります。番号は登録しないでください)。
 */
class ActionTarget internal constructor(
    val args: JsonObject,
    val aimed: ScreenSnapshot?,
    val bySight: Boolean,
    val seen: String?,
    val readAgeMs: Long?,
    private val look: suspend () -> ScreenSnapshot?,
) {
    suspend fun now(): ScreenSnapshot? = look()
}

/** は、説明に従ってターゲットのパラメータを指します。要素によって表示される単語、名前、または入力ボックスのプロンプトです。役割を追加することができます。 */
const val DESCRIBED_ELEMENT = "element"
const val DESCRIBED_ROLE = "role"
/** と説明が同時に出てくるのでどれをクリックすればいいのか分かりません。 */
private val DESCRIBED_CONFLICTS = listOf("ref", "snapshotId", "x", "y")
/** ページ ポップアップがハングしたときのエラー コード (cap/web の DIALOG_OPEN を参照)。 */
private const val DIALOG_OPEN_CODE = "dialog_open"
/** 複数が一致する場合は、最大でもいくつかをリストします。 */
private const val DESCRIBED_LISTED = 6
/** 理由は、画像を探しましたが、見つからないか、確認できなかったためです（残りは、画像を取得または表示できないことです）。 */
private val SIGHT_MISSED = setOf("not_found", "name_mismatch", "not_confirmed", "unverified_grounding", "vision_unavailable")

/** 説明に従ってターゲットを検索した結果: [Resolved] は番号と見つかった要素を変更した後のパラメーター、[Failed] はモデルの失敗結果です。 */
sealed interface Described {
    /**
     * [note]: 先頭を押すか、マッチング時に添付されるアクション結果の説明 (説明内容、実際にマッチングした人) を含めます。同じことがヌルです。
     * [element] は null です。ツリーには点がありません。点は画像を見ることで見つかります (パラメーターは @vN に変更されます)。
     */
    class Resolved(val args: JsonObject, val element: UiElement?, val note: JsonObject? = null) : Described
    class Failed(val result: ToolExecution) : Described
}

/**
 * タスク内のインターフェース機能（携帯電話、Web ページ）が共有するインターフェース状態。各画面の画面読み取り状態のコピー、どの画面を操作しているか、実行入口のアクション対象、ルートのターゲットとアンカーポイント。
 * ディストリビュータは、各呼び出しの前後にインターフェイスを完成させるためにこれを使用します (アクションの前に確認し、実際の効果を比較し、アクションの後にインターフェイスを接続し、画像から取得したポイントをクリアします)。
 * ディストリビュータとコア ループはそれを認識せず、[SurfaceLayer] のみがそれを使用します。スタートは [attach] によってツール コンテキストに入れられ、インターフェイスを持つツールはコンテキストから取得されます。
 */
internal class Interfaces(
    surfaces: List<Surface>,
    stableRefs: Boolean,
    private val config: ToolConfig,
    val shots: Shots,
    /** 最後に読み取られたインターフェイス テキスト (複数の画面で共有)。アクション後に読み取られた内容が同じであれば、再度記憶されることはありません。 */
    lastRead: () -> String?,
) : SurfaceLayer {
    companion object {
        /**
         * このタスクのインターフェースのステータス: [surfaces] は、ポートに接続されている画面です。 [stableRefs] がオンの場合、安定した番号が画面全体の読み取りに使用されます (アクションには独自の画面があり、優先度が与えられると要素は常にオンになります)。
         * を [ctx] に配置し、そこからインターフェイス ツールを取得します。
         */
        fun attach(ctx: ToolContext, surfaces: List<Surface>, stableRefs: Boolean): Interfaces =
            Interfaces(surfaces, stableRefs || ctx.config.inlineScreen || ctx.config.adaptiveVision, ctx.config, ctx.shots) {
                ctx.evidence.recentReads.lastOrNull()
            }.also { ctx[INTERFACES] = it }
    }

    val focus = SurfaceFocus(surfaces)
    val sessions: Map<String, ScreenSession> = focus.surfaces.associate { s ->
        s.id to ScreenSession(s, stableRefs, config.visionEnabled, config.recordFrames, lastRead, shots, config.vision, config.adaptiveVision)
    }
    private val gateView = GateView()

    suspend fun checkAppAccess(tool: AgentTool?, after: Boolean = false) {
        if (tool == null || tool.offScreen || tool.capability !in sessions || tool.verb == Verb.ListApps) return
        sessionFor(tool).checkAppAccess(current = after || tool.verb !in APP_TARGET_VERBS)
    }

    /** 現在操作している画面の画面読み取り状態。 */
    val session: ScreenSession get() = sessions.getValue(focus.current.id)

    override val current: String get() = focus.current.id

    override fun enter(id: String?) = focus.enter(id)

    override fun leave(succeeded: Boolean) = focus.leave(succeeded)

    /** がオンの場合、ターゲット要素とアンカー ポイントは書き込み操作が実行される前に取得されます (再生スイッチがオンのときにループによって設定されます)。 */
    override var captureTargets: Boolean = false

    /** ツール [tool] その画面の画面読み取りステータス。画面ツールでない場合は、最後に操作したものになります。 */
    private fun sessionFor(tool: AgentTool?): ScreenSession = tool?.capability?.let(sessions::get) ?: session

    /** このツールが動作する画面。ツールが画面ではない場合、null になります。 */
    override fun surfaceOf(tool: AgentTool?): String? = tool?.capability?.takeIf { it in sessions }

    /**
     * 別の画面の番号を使用して、この画面上のツールを操作します。結果は、その番号がどの画面に属しているか、およびどのツールを使用する必要があるかを示す、間違った表面になります。
     * 番号がどの画面に属しているかを認識できない場合 (期限切れが長すぎるか、スペルが間違っている場合)、番号は null となり、通常どおりツールに渡されます (期限切れとして処理されます)。
     */
    override fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution? {
        val own = tool.capability?.let(sessions::get) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        if (own.owns(sid)) return null
        val owner = sessions.values.firstOrNull { it.owns(sid) }
            ?: sessions.values.firstOrNull { s -> sid.startsWith(s.surface.family.snapshotPrefix) && sid.drop(s.surface.family.snapshotPrefix.length).firstOrNull()?.isDigit() == true }
            ?: return null
        if (owner === own) return null
        val ownerId = owner.surface.id
        return fail("wrong_surface", Feedback.format("wrong_surface",
            "id" to sid, "owner" to ownerId, "tool" to tool.name, "surface" to own.surface.id,
            "instead" to (tools.counterpart(tool.name, ownerId) ?: tools.nameOf(Verb.Observe, ownerId) ?: ownerId),
            "observe" to (tools.nameOf(Verb.Observe, own.surface.id) ?: tool.name)))
    }

    /** は、呼び出しの前にインターフェイスで実行されます。インターフェイスを変更する前に、ルートのターゲットとアクションを確認してください。 [finish]で終了。 */
    private class Prep(
        val session: ScreenSession,
        val probe: ScreenSession.EffectProbe?,
        val aimed: Pair<UiElement?, List<Int>?>?,
    ) {
        /** この呼び出しは、インターフェイス側からの実際の効果を報告します。 */
        val reportsEffect: Boolean get() = probe != null
    }

    override suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution {
        if (tool.offScreen || tool.verb == Verb.ListApps) return body(false)
        // 制限されたアプリケーションを終了するターゲットに従って起動します。現時点では古いページは収集されず、起動後に許可されたページのみがアタッチされます。
        if (tool.verb in APP_TARGET_VERBS) {
            val raw = body(false)
            return finish(tool, args, Prep(session, null, null), raw, inlineScreen)
        }
        val prep = prepare(tool, args)
        return finish(tool, args, prep, body(prep.reportsEffect), inlineScreen)
    }

    private suspend fun prepare(tool: AgentTool, o: JsonObject): Prep {
        val session = this.session
        gateView.capture = if (captureTargets && tool.routable) gateView.captureTarget(session, tool.verb, o) else null
        // インターフェースのアクションを変更します。アクションの前に見て (要素テーブル + 画面)、アクション後の実際の効果を比較します。 呼び出し
        val probe = if (tool.reportsEffect) session.probe(o.argStr("snapshotId")) else null
        val aimed = probe?.let { effectTarget(session, tool.verb, o, it.before) }
        return Prep(session, probe, aimed)
    }

    /** はインターフェイス上にありません (または、[prepare] に送信されません)。ルート ターゲットは残されていません。 */
    override fun skip() { gateView.capture = null }

    /**
     * が 1 回呼び出された後: 成功したアクションは [inlineScreen] に従ってアタッチされます。観察戦略に従ってスクリーンが取り付けられ、実際の効果が報告されます。
     * この画面を改ざんした後 (失敗はカウントされますが、パラメータが間違っている場合や実行の失敗はカウントされません)、前の写真から得られたこの画面のポイントは信頼できなくなります。
     */
    private suspend fun finish(tool: AgentTool, o: JsonObject, prep: Prep, raw: ToolExecution, inlineScreen: Boolean): ToolExecution {
        val session = prep.session
        session.probeDone()
        val readsAtAction = session.reads
        var result = raw
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (inlineScreen && tool.showsScreenAfter && ok) result = session.withScreen(result)
        // 古いモードでは、ビジュアル メイン モデルはアクション後の画像を直接参照します。階層モードでは、インターフェイスに違いがある場合、または次の観察が必要な場合にのみ画像が追加されます。
        if (tool.showsScreenAfter && ok && !config.adaptiveVision && result.images.isEmpty() && "screen" !in result.content) result = session.withFrame(result)
        val probe = prep.probe
        val aimed = prep.aimed
        if (ok && probe != null && aimed != null) {
            val typed = if (tool.verb == Verb.TypeText) o.argStr("text") else null
            result = session.withEffect(result, probe, tool.verb, readsAtAction, aimed.first, aimed.second, typed)
        }
        if (tool.writesScreen && result.content["error"] != JsonPrimitive("invalid_arguments")) session.clearLocated()
        return result
    }

    /**
     * 効果を判定する対象：数字に応じたアクションがモデル画面内の要素となります。数字を入力せずにクリアすると、アクションの前にフォーカスが置かれた入力ボックスになります。
     * 画面領域はターゲットの範囲であり、座標アクション (画像を見て見つけた点を含む) は着地点の周囲の小さな領域です。どちらも (null、null) ではありません。
     */
    private fun effectTarget(session: ScreenSession, verb: Verb, o: JsonObject, before: ScreenSnapshot?): Pair<UiElement?, List<Int>?> {
        val ref = o.argStr("ref")?.takeIf { !LOCATED_REF.matches(it) }
        val el = when {
            ref != null -> gateView.snapshotFor(session, o)?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.TypeText || verb == Verb.ClearText -> before?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        fun num(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
        val point = gateView.gateArgs(session, verb, o).let { a -> num(a, "x")?.let { x -> num(a, "y")?.let { y -> x to y } } }
        val region = el?.bounds?.takeIf { it.size == 4 } ?: point?.let { (x, y) ->
            val d = EffectTuning.POINT_REGION
            listOf((x - d).coerceAtLeast(0), (y - d).coerceAtLeast(0), (x + d).coerceAtMost(999), (y + d).coerceAtMost(999))
        }
        return el to region
    }

    /**
     * は、説明に従ってターゲットのクリックとタイプを指します ([DESCRIBED_ELEMENT] パラメーター、番号と座標はありません)。 次に、画面 (登録番号) を再読み込みして、唯一一致する要素を見つけます。
     * パラメータをそのref + snapshotIdに置き換えると、以降の判断、承認、実行、検証は番号に基づくものと同じになります。
     * 入力時に、ツリーの一部が欠落しているか使用できず、一致する入力ボックスがありません。写真を見てください ([bySight] を参照)。
     * このタイプの呼び出しが見つからない場合は、null になります。見つからない場合、複数ある場合、またはパラメータが競合している場合は、モデルの失敗結果となり、実行されません。
     */
    suspend fun described(tool: AgentTool?, args: JsonObject): Described? {
        val verb = tool?.verb
        if (verb != Verb.Tap && verb != Verb.TypeText) return null
        val session = tool.capability?.let(sessions::get) ?: return null
        val text = args.argStr(DESCRIBED_ELEMENT)
        if (text == null) {
            if (DESCRIBED_ROLE in args) return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ROLE: only used together with $DESCRIBED_ELEMENT"))
            return null
        }
        DESCRIBED_CONFLICTS.firstOrNull { it in args }?.let {
            return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ELEMENT: give either $DESCRIBED_ELEMENT or $it, not both"))
        }
        val role = args.argStr(DESCRIBED_ROLE)?.trim()?.ifEmpty { null }
        // 内部読み取り画面: モデルはそれを見ていないため、読み取りとしてカウントされません (「手持ちの画面が古い」をクリアできません)。この画面は、「アクション前」として次のアクションのために予約されています。
        val snap = screenAttempt { session.snapshot(internal = true) }
            ?: return Described.Failed(fail("screen_unavailable", Feedback["screen_unavailable"]))
        // ページのポップアップ ボックスがハングします: ページが停止されており、何もクリックできません。
        if (snap.web?.dialog != null) {
            session.clearPrimed()
            return Described.Failed(failCode(DIALOG_OPEN_CODE))
        }
        val observe = session.surface.family.tool("observe")
        val shown = text.trim().take(80)
        val m = DescribedTarget.match(snap.elements, ElementQuery(text, role, field = verb == Verb.TypeText))
        if (m !is DescribedMatch.Found) session.clearPrimed()
        return when (m) {
            is DescribedMatch.Found -> Described.Resolved(
                JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                    mapOf("ref" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
                m.element,
                // はまったく同じ名前ではありません。正直に言って、どれが説明と一致するか教えてください。
                if (m.exact) null else JsonObject(mapOf("resolvedFrom" to JsonPrimitive(shown), "matched" to JsonPrimitive(m.element.name.take(80)),
                    "matchedRef" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
            )
            is DescribedMatch.Ambiguous -> Described.Failed(fail("element_ambiguous", Feedback.format("described.ambiguous",
                "text" to shown, "count" to m.candidates.size, "snapshot" to snap.snapshotId,
                "candidates" to m.candidates.take(DESCRIBED_LISTED).joinToString("; ") { e ->
                    val near = DescribedTarget.neighbour(e, snap.elements)?.let { " 付近の文字「${it.take(30)}」" }.orEmpty()
                    val off = if (DescribedTarget.usable(e, verb == Verb.TypeText)) "" else "（操作できません）"
                    "${e.ref} ${e.role} 「${e.name.take(40)}」 位置 ${e.bounds}$near$off"
                })))
            DescribedMatch.None -> {
                // 入力中ですが、この画面上のツリーに一部が欠けているか、使用できません。ツリーが一致しない場合でも、それが画面上にないわけではありません。このフレームを見つけるには写真を見てください。
                val locator = session.locator
                if (verb == Verb.TypeText && locator != null && session.treeBlind(snap)) bySight(session, locator, args, shown)
                else Described.Failed(fail("element_not_found", Feedback.format("described.none",
                    "text" to shown, "role" to (role?.let { "（role: $it）" } ?: ""), "observe" to observe)))
            }
        }
    }

    /**
     * 画像を見て、説明で示されている入力ボックスを見つけて (find_visually と同じチェック セット: 赤いボックスを再読み取り、根拠のないものにマークを付けます)、パラメーターを @vN で見つかったものに置き換えます。
     * 以降は@vNにモデルをアップロードするのと同じです。まず、画面が変化していないこと、およびクリックが不可逆的に判定されていることを確認します。貼り付け後、画面上のテキストを押して、ポイントがクリックされたかどうかを確認します。
     */
    private suspend fun bySight(session: ScreenSession, locator: Locator, args: JsonObject, shown: String): Described =
        when (val r = locator.locate(shown, null)) {
            // が見つかりませんでした (または画像を見るときにエラーが発生しました): 説明を変更するか、アドバイスをお願いします。画面を取得または表示できない（保護されており、アプリがまだ開いていない）場合は、find_visually の指示に従ってください
            is LocateResult.NotFound -> Described.Failed(fail("element_not_found", Feedback.format("described.not_seen",
                "text" to shown, "reason" to r.reason,
                "next" to if (r.reason in SIGHT_MISSED) Feedback["described.not_seen.retry"] else r.message)))
            is LocateResult.Found -> {
                session.locatedInfo[r.ref] = Located(shown, null, r.seen, r.unverified)
                Described.Resolved(
                    JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                        mapOf("ref" to JsonPrimitive(r.ref), "snapshotId" to JsonPrimitive(r.snapshotId))),
                    null,
                    JsonObject(listOfNotNull("resolvedFrom" to JsonPrimitive(shown), "foundBySight" to JsonPrimitive(true),
                        r.seen?.let { "seen" to JsonPrimitive(it) }).toMap()),
                )
            }
        }

    /** は、このステップの目標を実行します ([ActionTarget] を参照)。読み取り専用のため、アクションは実行されません。 */
    fun actionTarget(tool: AgentTool?, args: JsonObject): ActionTarget {
        val session = sessionFor(tool)
        val verb = tool?.verb
        val checked = gateView.gateArgs(session, verb, args)
        return ActionTarget(checked, gateView.snapshotFor(session, checked), gateView.visionSourced(session, verb, args),
            gateView.confirmedSeen(session, verb, args), snapshotAgeMs(args.argStr("snapshotId")), ::peek)
    }

    /** 画面の読み取りからのミリ秒数。どの画面の最後の数回でもない場合は、null になります。 */
    private fun snapshotAgeMs(snapshotId: String?): Long? = sessions.values.firstNotNullOfOrNull { s ->
        s.recentSnapshots.firstOrNull { it.snapshotId == snapshotId }?.let { snap -> s.readAt[snap.snapshotId]?.let { System.currentTimeMillis() - it } }
    }

    /** の画面読み取り (ツール [tool] の画面をクリックし、パラメーターで snapshotId を見つけます)。過去数回ではない場合は null です。 */
    fun snapshotFor(tool: AgentTool?, args: JsonObject): ScreenSnapshot? = gateView.snapshotFor(sessionFor(tool), args)

    /** の実行記録では、現時点でのモデル番号に対応するポート番号が使用されます。安定した番号がオフになっている場合、@eN がない場合、または番号の有効期限が切れている場合は空になります。 */
    override fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject = gateView.portRecord(sessionFor(tool), args)

    /** 最後の呼び出しが書き込み操作だった場合、そのターゲット要素 (null) が認識されないか、アクションの前のアンカーで認識されました。それ以外の場合は null でした。 */
    fun lastActionTarget(): Pair<Selector?, Anchors>? = gateView.capture?.let { it.target to it.before }

    /** 最後の書き込み操作前の画面は、スクリーンショットからシステムによって保護されています。 */
    fun lastActionProtected(): Boolean = gateView.capture?.snapshot?.protected == true

    /** 最後の書き込み操作後のこの時点で 1 つの画面 (登録番号なし) を読み取り、アクションの後に新しく表示されたテキストを取得します。書き込み操作ではない場合、または読み取りできない場合は、null になります。 */
    suspend fun actionAfter(): Anchors? {
        if (gateView.capture == null) return null
        return peek()?.let(::anchorsAfter)
    }

    /** 上記と同じですが、呼び出し元がすでに読んでいるアクションを使用します。 */
    fun anchorsAfter(after: ScreenSnapshot): Anchors? = gateView.anchorsAfter(after)

    /** この時点で、モデルハンドに番号を登録したり、画面の読み取りを変更したりせずに、最近操作した画面を再読み取りします。読み取れない場合は null になります。 */
    suspend fun peek(): ScreenSnapshot? = session.peek()

    /** など 最近操作した画面は [timeoutMs] までは安定しています。ステータスを読み取れない場合は通常どおりに戻ります。 */
    override suspend fun settle(timeoutMs: Long) {
        screenAttempt { session.screen.waitStable(timeoutMs) }
    }

    /** 最近操作した画面のフレーム（原画）とその画面番号をレビュー登録します。保護ページ、決済ページは切り取られず、切り取られない場合はnullとなります。 */
    override suspend fun visibleText(): List<String> = peek()?.elements?.map { it.name }.orEmpty()

    override suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome =
        UntilWait(session.screen, ::peek, clock, interrupted).run(until)

    override suspend fun imageNow(): Pair<ByteArray, Int>? = session.currentFrame()

    /** メイン モデルは、画像を表示するときにアクションの後に画面を表示できます。画面と要素テーブルは同時に表示されます。画面上にアプリがない場合は null になります。 */
    override suspend fun viewNow(): ToolDispatcher.Glance? = session.currentView()

    /** モデルで引き続き表示される画面の読み取り値 (トリミングと圧縮後)。異なる塩基が含まれていない場合は、全額が送信されます。 */
    override fun setVisible(ids: Set<String>) { sessions.values.forEach { it.visible = ids } }

    /** 実行ステータスに書き込まれたこの画面の現在のバージョン (Web ページのモバイル バージョン、コンピューター バージョン)。利用できない場合、または読み取ることができない場合は、null になります。 */
    override suspend fun mode(): String? = focus.surfaces.firstNotNullOfOrNull { it.mode }?.invoke()
}

private val APP_TARGET_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent)
