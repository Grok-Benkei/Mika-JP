package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.Refusals
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.DeclareResult
import com.wangzan101.mika.core.general.RequestConfirmation
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * などのユーザー: フォローアップの質問に回答し、受諾のために引き渡し、回答の確認を行います。待機期間中、待機ステータスは外部に表示され、待機後、[running] (呼び出し元によって指定) に置き換えられます。
 * ユーザーの決定 (またはタイムアウト、スキップ) によって、この呼び出しのツールの結果が決まります。インターフェイスは、[answerInput] というメソッドを介してユーザーの回答を渡します。
 */
internal class UserExchange(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    /** ユーザーが応答を確認し、同意しないを押すまで待機する時間制限。 null の場合は、永久に待機します。 */
    private val confirmTimeoutMs: Long?,
    private val inputTimeoutMs: Long,
    /** ユーザーが今回言ったこと (memory_save の引用検証に使用);収集されない場合は null。 */
    private val utterances: () -> UserUtterances?,
    private val setState: (TaskState) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    private var threadId = "local"
    private var provenance = RunProvenance()
    private var interaction: Interaction = LocalInteraction
    @Volatile var activeAsk: Delivery.Ask? = null
        private set
    @Volatile private var takeoverReply: CompletableDeferred<HandBack>? = null
    @Volatile private var inputReply: CompletableDeferred<String>? = null
    @Volatile private var confirmationReply: CompletableDeferred<ConfirmChoice>? = null
    /** 回答をモデルに与えられたものに変更します (ユーザー画面を使用するときの説明に変更します。[UserScreenTurns.asText] を参照)。デフォルトはそのままです。 */
    var answerText: suspend (String) -> String = { it }

    fun begin(thread: String, run: RunProvenance, channel: Interaction = LocalInteraction) {
        threadId = thread
        provenance = run
        interaction = channel
        activeAsk = null
    }

    private fun ask(kind: Delivery.Ask.Kind, text: String): Delivery.Ask =
        Delivery.Ask(UUID.randomUUID().toString(), threadId, UUID.randomUUID().toString(), kind, text)

    private suspend fun announce(ask: Delivery.Ask): DeliveryState {
        activeAsk = ask
        return interaction.publish(ask)
    }

    private fun resolve(ask: Delivery.Ask) {
        if (activeAsk?.askId == ask.askId) activeAsk = null
        interaction.resolved(ask.askId)
    }

    private fun matches(id: String?): Boolean = id == null || activeAsk?.askId == id

    fun resumeAfterTakeover(result: HandBack, askId: String? = null): Boolean =
        if (matches(askId)) takeoverReply?.complete(result) ?: false else false

    /** は、待機中の質問がない場合 (タイムアウト、回答済み)、false を返します。 */
    fun answerInput(text: String, askId: String? = null): Boolean =
        if (matches(askId)) inputReply?.complete(text) ?: false else false

    fun answerConfirmation(choice: ConfirmChoice, askId: String? = null): Boolean =
        if (matches(askId)) confirmationReply?.complete(choice) ?: false else false

    /** ミッションは終了しました: もう答えを待つ必要はありません。 */
    fun clear() {
        takeoverReply = null
        inputReply = null
        confirmationReply = null
        activeAsk = null
    }

    /** ユーザーが [inputTimeoutMs] までの質問に答えるまで待ちます。タイムアウト後に null を返します。ユーザーの答えは確認コードか何かである可能性があるため、覚えないでください。 */
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String? {
        if (provenance.attendance == Attendance.Unattended) return null
        val pending = CompletableDeferred<String>().also { inputReply = it }
        val ask = ask(Delivery.Ask.Kind.Input, prompt)
        activeAsk = ask
        setState(TaskState.WaitingInput(prompt, options))
        emit(RunEvent.State(now(), "waiting_input", detail(prompt)))
        return try {
            if (announce(ask) == DeliveryState.Failed) null
            else withTimeoutOrNull(inputTimeoutMs) { pending.await() }
        } finally {
            inputReply = null
            resolve(ask)
        }.also { if (it == null) emit(RunEvent.State(now(), "no_answer", detail(prompt))) else utterances()?.add(UserWords.said(com.wangzan101.mika.core.userscreen.UserScreenMessage.parse(it).words, tools)) }?.let { answerText(it) }
    }

    suspend fun input(o: Outcome.Input, running: TaskState): ToolExecution {
        val answer = awaitAnswer(o.prompt, o.options)
        // タスク内でユーザーが指定した事実も検証の基礎となります。
        answer?.let { tools.noteUserAnswer(o.prompt, it) }
        setState(running)
        return ToolExecution(buildJsonObject {
            if (answer != null) { put("ok", true); put("answer", answer) }
            else { put("ok", false); put("error", "no_answer"); put("message", INPUT_TIMEOUT_MESSAGE); put("recoverable", true); put("counts_as_failure", false) }
        })
    }

    /** ユーザーが返却するのを待ちます。タスクがキャンセル（中止）された場合は null を返します。返されない場合、トランザクションが完了したとは言えません。スキップやタイムアウトは失敗とみなされませんが、モデルは真実を伝えます。 */
    suspend fun takeover(o: Outcome.Takeover, running: TaskState): ToolExecution? {
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) return null
        val pending = CompletableDeferred<HandBack>().also { takeoverReply = it }
        val ask = ask(Delivery.Ask.Kind.Takeover, o.message)
        activeAsk = ask
        setState(TaskState.WaitingTakeover(o.reason, o.message, o.item, o.amount, o.address))
        emit(RunEvent.State(now(), "waiting_takeover", detail(o.message)))
        val since = now()
        val back = try {
            if (announce(ask) == DeliveryState.Failed) return null
            if (provenance.attendance == Attendance.Reachable) withTimeoutOrNull(inputTimeoutMs) { pending.await() }
            else pending.await()
        } finally { takeoverReply = null; resolve(ask) } ?: return null
        back.note?.let { utterances()?.add(it) }
        tools.noteTakeover(o.reason, back)
        val outcome = if (back.userFinished && back.outcome == HandBackOutcome.complete) "user_finished" else back.outcome.name
        emit(RunEvent.Takeover(now(), o.reason, outcome, back.durationMs ?: (now() - since), back.inputCount))
        if (back.outcome == HandBackOutcome.aborted) return null
        setState(running)
        return ToolExecution(buildJsonObject {
            put("outcome", back.outcome.name)
            if (back.outcome == HandBackOutcome.complete) {
                put("ok", true)
                put("durationMs", back.durationMs ?: (now() - since))
                back.inputCount?.let { put("inputCount", it) }
                back.stoppedAt?.let { put("stoppedAt", it) }
                put("note", HANDBACK_NOTE)
                if (back.userFinished) { put("userFinished", true); put("instruction", USER_FINISHED_PROMPT) }
                back.note?.takeIf { it.isNotBlank() }?.let { put("userNote", it) }
            } else {
                put("ok", false)
                put("reason", if (back.outcome == HandBackOutcome.skipped) "takeover_skipped" else "takeover_timeout")
                put("counts_as_failure", false)
                put("note", NO_HANDBACK_NOTE)
            }
        })
    }

    /** 申請内容がフロントデスクと一致している場合は、ユーザーの回答を待ちます (キャンセル期限に達したら [同意しない] を押してください)。ツールの結果はユーザーの判断によるものです。 */
    suspend fun confirm(o: Outcome.Confirm, step: Int, running: TaskState): ToolExecution {
        val g = confirmations ?: return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "unknown_tool"); put("tool", RequestConfirmation.name)
            put("message", Feedback["confirmation_unavailable"]); put("recoverable", true)
        })
        val r = o.request
        fun event(decision: String?) = RunEvent.Confirmation(now(), r.action.name, detail(r.target), detail(r.content), r.amount?.let(::detail), decision)
        fun error(code: String, message: String, recoverable: Boolean) = ToolExecution(buildJsonObject {
            put("ok", false); put("error", code); put("message", message)
            put("recoverable", recoverable); put("counts_as_failure", false)
        })
        when (g.declare(r, o.foregroundApp)) {
            DeclareResult.Mismatch -> {
                emit(event("mismatch"))
                return ToolExecution(buildJsonObject {
                    put("ok", false); put("error", "confirmation_mismatch")
                    put("message", Feedback.format("confirm_app_mismatch", "app" to r.app, "foreground" to (o.foregroundApp ?: "unknown")))
                    put("recoverable", true)
                })
            }
            DeclareResult.Declined -> {
                emit(event("declined_before"))
                return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
            }
            DeclareResult.Unsupported -> return error("payment_requires_takeover", Refusals.PAYMENT_TAKEOVER, recoverable = true)
            DeclareResult.Pending -> Unit
        }
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_unattended"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        // 支出や画面外の変更への同意は本人が端末上で行う。チャネル・定時実行・MCPからのタスクでは確認しない。
        if (provenance.origin != RunOrigin.Ui) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_remote"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        val pending = CompletableDeferred<ConfirmChoice>().also { confirmationReply = it }
        val ask = ask(Delivery.Ask.Kind.Confirm, "${r.action.name}: ${r.target} ${r.content}")
        activeAsk = ask
        setState(TaskState.WaitingConfirmation(r))
        emit(event(null))
        // 誰も応答しない場合 (電話の隣にいない場合)、[同意しない] をクリックしてください。自動的に同意されることはありません。
        val timeout = if (provenance.attendance == Attendance.Reachable) confirmTimeoutMs ?: 15 * 60_000L else confirmTimeoutMs
        val answer = try {
            if (announce(ask) == DeliveryState.Failed) null
            else if (timeout == null) pending.await() else withTimeoutOrNull(timeout) { pending.await() }
        } finally { confirmationReply = null; resolve(ask) }
        val choice = answer ?: ConfirmChoice.Deny
        g.decide(choice, step)
        setState(running)
        emit(event(when {
            answer == null -> "timed_out"
            choice.approved -> "approved"
            else -> "declined"
        }))
        return if (choice.approved) ToolExecution(buildJsonObject { put("ok", true); put("approved", true); put("message", CONFIRMATION_APPROVED) })
        else error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    companion object {
        val INPUT_TIMEOUT_MESSAGE: String get() = Feedback["no_answer_message"]
        val HANDBACK_NOTE: String get() = Feedback["handback_note"]
        val NO_HANDBACK_NOTE: String get() = Feedback["no_handback_note"]
        val USER_FINISHED_PROMPT: String get() = Feedback["user_finished_prompt"]
        val CONFIRMATION_DECLINED: String get() = Feedback["confirmation_declined"]
        val CONFIRMATION_APPROVED: String get() = Feedback["confirmation_approved"]
    }
}
