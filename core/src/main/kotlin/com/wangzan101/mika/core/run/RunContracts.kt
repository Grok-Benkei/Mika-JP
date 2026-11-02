package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.Turn

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/** 1 回の実行に対する不変のデプロイメント情報。後続のステップには、機能、リソース予算、承認スナップショットが含まれます。 */
data class RunSpec(
    val id: RunId,
    val goal: String,
    val threadId: String,
    val priorTurns: List<Turn> = emptyList(),
    val provenance: RunProvenance = RunProvenance(),
    /** null はデフォルトの機能を示します。 sub-Run は厳密なサブセットを与えることができます。 */
    val capabilities: Set<String>? = null,
    /** 条件チェック 実行では読み取りのみが可能です。例外は、読み取りオブジェクトを取得するための準備アクションとしての Web ナビゲーションです。 */
    val readOnly: Boolean = false,
    val parent: RunId? = null,
    val stepBudget: Int? = null,
) {
    init { require(goal.isNotBlank() && threadId.isNotBlank()) }
}

/** は、このセッションからの実行に与えられる入力です。制御チャネルをキャンセルすると、割り込みキューで待たなくなります。 */
sealed interface RunInput {
    /**
     * は以下を追加しました: まだ待機中のモデル リクエストをキャンセルし、既存の履歴の後に再度リクエストします (実行されたものはすべて保持されます)。実行中のツールは通常どおりに完了し、結果が記憶されます。
     * 待機は、削除後に何も失われることなく早期に終了します ([waitOrSteer] を参照)。
     */
    data class Steer(val messageId: String, val text: String) : RunInput
    /** と [Steer] は同じ道 (旧入口の名前) を進みます。 */
    data class Redirect(val messageId: String, val text: String) : RunInput
    data class Answer(val text: String, val askId: String? = null) : RunInput
    data class Confirm(val choice: ConfirmChoice, val askId: String? = null) : RunInput
    data class HandBack(val result: com.wangzan101.mika.core.tool.HandBack, val askId: String? = null) : RunInput
    data object Cancel : RunInput
}

enum class InputReceipt { Accepted, NoPendingInteraction, NextTask, Closed }

sealed interface RunPhase {
    data object Queued : RunPhase
    data object Preparing : RunPhase
    data object Running : RunPhase
    data class WaitingUser(val askId: String? = null) : RunPhase
    data class Parked(val askId: String) : RunPhase
    data class Paused(val reason: String) : RunPhase
    data object Finishing : RunPhase
    data object Aftercare : RunPhase
    data object Ended : RunPhase
    data object Interrupted : RunPhase
}
