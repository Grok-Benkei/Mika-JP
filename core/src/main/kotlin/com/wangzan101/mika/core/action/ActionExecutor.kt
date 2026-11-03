package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.guard.DIAGNOSTIC_VERBS
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.PAGE_READ_VERBS
import com.wangzan101.mika.core.guard.GuardScope
import com.wangzan101.mika.core.guard.elementsHash
import com.wangzan101.mika.core.guard.resultFingerprint
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.effectiveWrite
import com.wangzan101.mika.core.uistate.Described
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.guard.OffScreenGuard
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.capability.Paced

/**
 * 外界を変えるすべてのアクションの唯一の実行入口: [ToolDispatcher.execute] [ActionPass] が必要な場合は、ここでのみ入手可能です。
 * 各ソースの順序は同じです: ガード操作→ロック画面・レベル取得(書き込み操作)→不可逆判定と同意チェック(レベルを押した瞬間の画面)→
 * 実行→ガードチェック(故障ヒューズ、画面変更、再読み込み)→走行記録。ソース間の意図的な差異は、[OriginPolicy] にのみ記述されます。
 */
class ActionExecutor(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    confirmations: ConfirmationGate?,
    private val gate: suspend () -> Unit,
    val recorder: ActionRecorder,
    /** 画面を動かさないツール用のガード。そうでない場合は null。 */
    private val offScreen: OffScreenGuard? = null,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    provenance: RunProvenance = RunProvenance(),
    selfPackage: String? = null,
    autonomous: Boolean = false,
    /** 同じ Web サイトまたはアプリ上での 2 つの連続する外部アクション (送信、送信) 間の間隔 ([OutwardPacing] を参照)。デフォルトは等しくありません。 */
    private val pacing: OutwardPacing = OutwardPacing.Off,
) {
    private val decision = ConfirmDecision(tools, confirmations, approvals, recorder = recorder, provenance = provenance, selfPackage = selfPackage, autonomous = autonomous)
    private val pass = ActionPass()

    /** には確認レベル（ユーザー同意後の決済可能）が搭載されています。 */
    val confirms: Boolean = confirmations != null

    /** 画面を動かさないツールのガードは停止を要求するという結論。ツールが停止しない場合は null になります。 */
    val offScreenStop: GuardVerdict? get() = offScreen?.stop

    suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp = FollowUp.NONE): ActionOutcome =
        run(call, origin, followUp, reads = null)

    /** [reads]: ページ スクリプトの 1 回の呼び出しで個別にカウントされる読み取りの範囲 (ソースが分離を必要とする場合)。そこでのアクションはタスクに従ってカウントされます。 */
    private suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val args = parseArgs(call.argsJson)
        // 画面を動かさないツール（サンドボックスタイプ）：画面ガードには入らず、それぞれのガードによって管理されます。
        val offScreen = tools.offScreen(call.name)
        val verb = tools.verb(call.name)
        // は別の画面の番号を使用します。判断なし、アクションなし、どの画面が操作されているかの記録なし、どのツールを使用するかを説明するためにディストリビュータに直接渡されます。
        if (tools.misaimed(call.name, args) != null) {
            return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads))
        }
        // 以降のレベルと判定は今回呼び出された画面による（この呼び出しが終了するまで）
        tools.aim(call.name, args)
        try {
            return aimed(call, origin, followUp, reads, args, offScreen, verb)
        } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
            val content = buildJsonObject {
                put("ok", false); put("error", e.code); put("message", e.message); put("retryable", false)
                e.packageName?.let { put("package", it) }
            }
            // シングル ステップ: このツール呼び出しの結果として、モデルに渡されて、ルートを変更したり、ユーザーに説明したりします。バッチとスクリプトは拒否に従って自動的に処理されます
            if (origin.policy.recordsRefusal) followUp.refused(content)
            recorder.result(origin.step, call.name, verb, content, content, null, null, 0, origin.policy.source, call.id)
            return ActionOutcome.Refused(e.code, content, emptyList())
        } finally {
            // 成功または失敗はディストリビュータによって記録されます。この層だけがここで終わる
            tools.release(false)
        }
    }

    private suspend fun aimed(
        given: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?, givenArgs: JsonObject, offScreen: Boolean, verb: Verb?,
    ): ActionOutcome {
        // 説明によると、まず対象をその時点でその画面上の番号に変更します。その後の判断、承認、実行は番号に従って行うのと全く同じです。見つからない場合、または一意でない場合は実行されません。
        var call = given
        var args = givenArgs
        var note: JsonObject? = null
        tools.interfaces.checkAppAccess(tools.declared(call.name))
        when (val d = tools.interfaces.described(tools.declared(call.name), args)) {
            is Described.Failed -> return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads, preset = d.result))
            is Described.Resolved -> { args = d.args; call = call.copy(argsJson = d.args.toString()); note = d.note }
            null -> Unit
        }
        val policy = origin.policy
        val readScope = reads?.takeIf { verb in PAGE_READ_VERBS }
        val before = when {
            offScreen -> emptyList()
            readScope != null -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS, readScope)
            else -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS)
        }
        if (guards.stop != null) return refuse("guard_stopped", origin, before)
        if (policy.stopOnGuardWarning && before.isNotEmpty()) return refuse("guard_warning", origin, before)
        val writing = tools.writesScreen(call.name)
        if (writing && !policy.gatedByCaller) gate()
        // 有効な承認がなければ実行せず、連続失敗にも数えない。
        var d = decision.judge(call.name, args, origin)
        // 画面外の確認要求（変更するrootコマンド）は、その場で本人へ確認し、承認後に同じ内容を再判定する。
        // この実行や呼び出し元が本人へ確認できなければ実行せず、拒否として記録する。
        val ask = if (d is Decision.Required || d is Decision.Denied) tools.approvalRisk(call.name, args)?.ask else null
        if (ask != null) {
            val hit = Irreversible(ask.action, ask.content)
            val answer = (if (d is Decision.Required) followUp.approve(ask) else null)
                ?: return declined(call, args, origin, before, hit, ToolExecution(Refusals.unavailable()), followUp, "approval_unavailable")
            if (answer.content["ok"] != JsonPrimitive(true)) return declined(call, args, origin, before, hit, answer, followUp, "confirmation_declined")
            d = decision.judge(call.name, args, origin)
        }
        when (d) {
            is Decision.Required -> return blocked(call, args, origin, before, d.hit, null, followUp)
            is Decision.Mismatch -> return blocked(call, args, origin, before, d.hit, d.reason, followUp)
            is Decision.Denied -> return approvalUnavailable(call, args, origin, before, d.hit, followUp)
            Decision.Unresolved -> return refuse("target_unresolved", origin, before)
            Decision.OwnApp -> return ownApp(call, args, origin, before, followUp)
            else -> Unit
        }
        // 外部アクション: 同じ Web サイトまたはアプリでの前回のアクションから分離されています (待機中にタスクをキャンセルできますが、アクションはまだ開始されていません)
        val site = decision.outwardSite
        val paced = site?.let { pacing.waitBefore(it, recorder.now()) }?.takeIf { it > 0 } ?: 0L
        // ユーザーは等間隔で割り込みました。今回はやりません。最初にモデルにその文を読んでもらいます。間隔は最後に実行したときから計算され、残りは次回実行したときに補われます。
        if (paced > 0 && waitOrSteer { delay(paced) } == null) {
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = failCode(SKIPPED_FOR_NEW_MESSAGE)))
        }
        // 画面を変更するアクション: 画面の機能のリズムに従ってしばらく停止し (アプリの AppDwell を参照)、単一ステップまたはバッチの各ステップでここで停止します。
        // 結合されたアクション (スクリプト) 自体は停止せず、その中の各ステップはここで停止します。画面数の上限に達し、滞在中にユーザーが中断した場合は完了しません。
        val screenPaced = writing && !offScreen && !tools.composite(call.name)
        val dwelt = if (screenPaced) tools.beforeWrite(call.name, outward = site != null) else Paced.NONE
        dwelt.stop?.let { stop ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = stop, pacedMs = paced + dwelt.waitMs))
        }
        if (writing) {
            guards.noteAction()
            decision.noteWrite(call.name)
        }
        val step = Step(call, args, origin, before, writing, offScreen, verb, (d as? Decision.Traced)?.detail, followUp, reads, note = note,
            pacedMs = paced + dwelt.waitMs, idleScrolls = dwelt.idleScrolls, outward = site != null)
        // アクションが開始されたら、それを完了して書き留めます。たとえスクリプトがタイムアウトしたり、タスクがキャンセルされたとしても、途中で捕らえられたり、記録が失われたりすることはありません。
        // この画面の外部アクションを伝える機能 (Web ページのページ数の上限は閲覧のみです。PageDwell を参照)
        site?.let(tools::outwardStart)
        var done = false
        try {
            val outcome = if (policy.actionCapMs == null) ran(step) else withContext(NonCancellable) { ran(step) }
            if (screenPaced && outcome is ActionOutcome.Ran) tools.afterWrite(call.name, outcome.exec.content)
            if (site != null && outcome is ActionOutcome.Ran && outcome.exec.content["ok"] != JsonPrimitive(false)) {
                done = true
                pacing.noteDone(site, recorder.now())
            }
            return outcome
        } finally {
            site?.let { tools.outwardEnd(it, done) }
        }
    }

    private class Step(
        val call: Part.ToolCall, val args: JsonObject, val origin: Origin, val before: List<GuardVerdict>, val writing: Boolean,
        val offScreen: Boolean, val verb: Verb?, val traced: String?, val followUp: FollowUp, val reads: GuardScope?,
        /** ツールを与えずに結果が得られます（説明によるとターゲットが見つかりません）。 */
        val preset: ToolExecution? = null,
        /** 結果の説明を添付します (説明に従って一致した人)。 */
        val note: JsonObject? = null,
        /** 実行前に外部アクションの間隔とこの画面のリズムを待つ時間。この呼び出しの待機も含まれます。 */
        val pacedMs: Long = 0,
        /** は、実行前の一時停止中に数回ロールされました。走行記録を入力するだけです。 */
        val idleScrolls: Int = 0,
        /** 不可逆的な判断は、それが外部アクション (送信、送信) であることを認識します。このアクティビティを記録します。 */
        val outward: Boolean = false,
    )

    private suspend fun ran(s: Step): ActionOutcome {
        val call = s.call
        val policy = s.origin.policy
        val n = s.origin.step
        // ページ スクリプトの各アクションはここに戻り、入り口全体も完了します。スクリプト内の読み取りは、この呼び出しでのみカウントされます。
        val subVerdicts = mutableListOf<GuardVerdict>()
        val sub = if (tools.composite(call.name)) {
            val script = Origin.Script(n)
            val window = if (script.policy.isolatedReads) GuardScope(canStop = false) else null
            SubActions { c ->
                val o = run(c, script, FollowUp.NONE, window)
                subVerdicts += o.before
                if (o is ActionOutcome.Ran) subVerdicts += o.after
                o.execution()
            }
        } else null
        val started = recorder.now()
        // ステータスを変更するアクション: 実行前に台帳の位置を書き留めます。その後（この結果に伴う画面も含めて）再読したかどうかを回答前に確認します。
        val changes = tools.declaredRisk(call.name) != Risk.Read && (s.offScreen || ClassifiedRisk.changesState(s.verb))
        val mark = tools.ledgerMark()
        val raw = (s.preset ?: offScreen?.exhausted(call.name)?.let { ToolExecution(it) } ?: execute(s, sub))
            .let { r -> s.note?.let { r.with(content = JsonObject(r.content + it)) } ?: r }
            .let { r -> if (s.pacedMs > 0) r.with(waitMs = r.waitMs + s.pacedMs) else r }
            .let { r -> if (s.idleScrolls > 0) r.with(record = JsonObject(r.record + ("idleScrolls" to JsonPrimitive(s.idleScrolls)))) else r }
        // は障害を報告しましたが、インターフェイスが変更されたとは述べていません。このアクションによって何も変更はありません。回答する前にもう一度読む必要はありません。
        val failed = raw.content["ok"] == JsonPrimitive(false) && raw.changed != true
        if (changes && s.preset == null && !failed) recorder.noteChange(mark)
        val durationMs = recorder.now() - started
        val resolved = s.followUp.resolve(raw) ?: return ActionOutcome.Cancelled(s.before)
        tools.interfaces.checkAppAccess(tools.declared(call.name), after = true)

        val after = subVerdicts.toMutableList()
        after += if (s.offScreen) offScreen?.result(call.name, s.args, resolved.content).orEmpty() else guards.result(call.name, resolved.content)
        // アクションレポートの実際の効果が最初に記録され（効果なし、場所が変更されました）、次の画面では指紋が再度記録されません。
        ((resolved.content["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { !s.offScreen }?.let { after += guards.effect(it.content) }
        val elements = resolved.screenElements ?: resolved.content["elements"] as? JsonArray
        val beforeFrameProgress = guards.progressVersion
        elementsHash(elements)?.let { after += guards.frame("$n:${call.id}", it) }
        if (guards.progressVersion != beforeFrameProgress) after.removeAll { it.code == "no_progress" }
        if (!s.offScreen) {
            if (s.verb in PAGE_READ_VERBS) {
                // 画面の最後の変更（新しい画面、ページ全体の開始点の変更、新しい結果を与えるスクリプト）以降に読み取られていないコンテンツの読み取りは、再読み取りとしてカウントされません。
                val fp = elementsHash(elements) ?: resultFingerprint(resolved.content)
                val beforeReadProgress = guards.progressVersion
                after += if (s.reads != null) guards.read(call.name, guards.firstSeen(fp, s.reads), s.reads, fp)
                else guards.read(call.name, guards.firstSeen(fp), fingerprint = fp)
                if (guards.progressVersion != beforeReadProgress) after.removeAll { it.code == "no_progress" }
            } else if (s.writing && resolved.changed != false && resolved.content["ok"] == JsonPrimitive(true)) {
                guards.pageChanged()
            }
        }
        val (exec, content) = s.followUp.present(resolved, s.before + after)

        // 画面: モデルは 1 ステップで発信者に渡され、その順序で保存されます。サブステップインターフェイスは、インターフェイスが変更されたときにのみその場で保存されます。再生は保存されません
        val picture = if (policy.frames == Frames.Caller && s.verb != Verb.ViewPastScreens && !s.offScreen) exec.image ?: exec.frame else null
        val shotId = when (policy.frames) {
            Frames.Caller -> picture?.let { recorder.nextShot() }
            Frames.WhenChanged -> (exec.image ?: exec.frame)?.takeIf { exec.changed != false }?.let(recorder::saveFrame)
            Frames.None -> null
        }
        recorder.result(n, call.name, s.verb, content, exec.record, exec.changed, shotId, durationMs, policy.source, call.id, exec.waitMs)
        ActivityRecord.of(tools, call.name, s.args, exec, n, policy.source, call.id, recorder.now(), s.outward)?.let(recorder::event)
        if (policy.frames == Frames.WhenChanged && shotId != null) recorder.event(RunEvent.Media(recorder.now(), shotId, n, RunEvent.placeLabel(exec.place)))
        val seen = if (policy.routeTargets) routeTarget(call, s.verb, s.origin, exec) else null
        s.traced?.let { recorder.event(RunEvent.State(recorder.now(), "gate_unresolved", "${call.name} $it")) }
        return ActionOutcome.Ran(exec, s.before, after, content, durationMs, shotId.takeIf { picture != null }, picture, seen)
    }

    private suspend fun execute(s: Step, sub: SubActions?): ToolExecution {
        val call = if (s.offScreen && offScreen != null) offScreen.capTimeout(s.call, s.args) else s.call
        val cap = s.origin.policy.actionCapMs ?: return tools.execute(call, pass, s.origin.step, sub)
        // アクションには独自の上限があります
        return withTimeoutOrNull(cap) { tools.execute(call, pass, s.origin.step, sub) } ?: ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_timeout"); put("message", "操作が${cap / 1000}秒以内に完了しませんでした。続ける前に画面で結果を確認してください")
        })
    }

    /**
     * 再生がオンになっているときは、ルートのターゲット要素と前後のアンカー ポイントを覚えておいてください (アクションの後にもう 1 画面読んで、そこに戻ります)。
     * ターゲットを取得できませんが、アクションは有効です。ルートにステップが欠落していたり​​、不完全とマークされていてはならず、ルート全体が記録されません。
     */
    private suspend fun routeTarget(call: Part.ToolCall, verb: Verb?, origin: Origin, exec: ToolExecution): ScreenSnapshot? {
        if (!tools.captureTargets || tools.index[call.name]?.routable != true) return null
        // ポートに「パスワード ボックスではありません」と明確に表示されている場合にのみ、元のテキストを思い出すことができます。
        val secret = (exec.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
        val args = Redactor.args(verb, call.argsJson, secret)
        val effective = effectiveWrite(verb, exec.content)
        val source = origin.policy.source
        val none = Anchors(null, emptyList())
        val ui = tools.interfaces
        val captured = ui.lastActionTarget()
        if (captured == null) {
            if (effective) recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, null, none, none, true,
                callId = call.id, source = source, incomplete = true))
            return null
        }
        val seen = if (effective) ui.peek() else null
        val after = seen?.let(ui::anchorsAfter) ?: none
        recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, captured.first, captured.second, after, effective,
            callId = call.id, protected = ui.lastActionProtected(), source = source))
        return seen
    }

    /** はミカ自身に影響を与えます。モデルは通常どおり各ステップの指示を受け取り、実行記録に記録します。 */
    private fun ownApp(call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, followUp: FollowUp): ActionOutcome {
        val content = Refusals.content(Refusals.OWN_APP, origin.policy.refusals, before, null, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(Refusals.OWN_APP, content, before)
    }

    private fun refuse(code: String, origin: Origin, before: List<GuardVerdict>) =
        ActionOutcome.Refused(code, Refusals.content(code, origin.policy.refusals, before, null, null), before)

    private fun approvalUnavailable(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, followUp: FollowUp,
    ): ActionOutcome {
        val code = "approval_unavailable"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }

    /** 確認が拒否された、または本人へ確認できない場合は、[answer] を拒否結果として記録する。 */
    private fun declined(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, answer: ToolExecution, followUp: FollowUp,
        code: String,
    ): ActionOutcome {
        recorder.confirmation(hit, if (code == "confirmation_declined") "declined" else "denied")
        if (origin.policy.recordsRefusal) {
            followUp.refused(answer.content)
            recorder.result(origin.step, call.name, tools.verb(call.name), answer.content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, answer.content, before, hit)
    }

    private fun blocked(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, reason: String?, followUp: FollowUp,
    ): ActionOutcome {
        recorder.confirmation(hit, if (reason != null) "mismatch" else "required")
        val code = if (reason != null) "confirmation_mismatch" else "confirmation_required"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, reason)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }
}
