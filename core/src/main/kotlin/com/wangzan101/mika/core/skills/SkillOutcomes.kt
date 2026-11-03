package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState

@Suppress("EnumEntryName")
/**
 * [verified]: 完了 ([LearnGate])、トライアルはそれに応じて公式に変換されます (保存された名前が使用され、古いレコードは引き続き読み取られます)。
 * [unverified]: 判定は完了しましたが、検証は失敗しました。 [unreviewed]: 判定が完了していないため、スキルが有効であるかどうかが示されず、無効化された場合はカウントされません。
 */
enum class SkillResult { verified, unverified, failed, verifier_rejected, unreviewed }

/**
 * 結果カードの下のスキル プロンプト: 「ミカがスキルを学びました:... · 見てください」 ([draftId])、
 * 「このスキルは最近機能していません。そのため、無効にして復元し、削除する必要があります。」 ([suspended] はスキル名です)。
 */
data class SkillNote(val text: String, val draftId: String? = null, val suspended: String? = null)

/** 最近の使用実績と個人スキルの自動無効化（機能しない場合は無効化）。 */
object SkillOutcomes {
    const val KEEP = 5
    private const val WINDOW = 3
    private const val BAD_IN_WINDOW = 2
    /** 結果カードと検証結果: スキルに従いましたが検証結果が返され、verifier_rejected と判定されました。 */
    const val VERIFIER_REJECTED_MARK = "検証に合格しませんでした"

    /** は結果を 1 回追加し、最新の [KEEP] 時間のみを保持します。ルールに従って無効にされる場合があります。 */
    fun record(s: PersonalSkill, r: SkillResult, followed: Boolean): PersonalSkill {
        val outcomes = (s.outcomes + r.name).takeLast(KEEP)
        if (s.status == SkillStatus.trial) return s.copy(outcomes = outcomes, status = when (r) {
            SkillResult.verified -> SkillStatus.active
            SkillResult.failed, SkillResult.verifier_rejected -> SkillStatus.suspended
            else -> SkillStatus.trial
        })
        val suspend = s.status == SkillStatus.active &&
            shouldSuspend(outcomes, followed && r == SkillResult.verifier_rejected)
        return s.copy(outcomes = outcomes, status = if (suspend) SkillStatus.suspended else s.status)
    }

    /** 過去 3 回（未検証はカウントされません）のうち 2 回は検証されなかった、または今回は検証後に返されました。 */
    fun shouldSuspend(all: List<String>, lastFollowedRejected: Boolean): Boolean {
        val outcomes = all.filter { it != SkillResult.unreviewed.name }
        return lastFollowedRejected || outcomes.takeLast(WINDOW).count { it != SkillResult.verified.name } >= BAD_IN_WINDOW
    }

    /**
     * タスクの終了に対応する結果。キャンセルされた場合は記録されません（null）。 [outcome] は、答え ([RunOutcome]) を提出した後に判定されるエンディングです。
     * 完了していないか、部分的にしか完了していないと正直に述べた回答は失敗とみなされます。成功したと判断されなかったものは、スキルが有効かどうかを示すものではなく、未レビューとみなされます。
     * が完了した場合は [LearnGate] として検証済みとしてカウントされます (検証されていないものとしてカウントされます)。検証済みで失敗した場合は未検証としてカウントされます。
     * 唯一の失敗は、諦めてステップを使い果たすことです。モデルエラー、環境の問題、動作保護の停止、プロセスの中断は未レビューとしてカウントされます。
     */
    fun resultOf(end: TaskState, outcome: RunOutcome): SkillResult? = when (end) {
        is TaskState.Succeeded -> when {
            outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible -> SkillResult.failed
            // 一度返却した後も正しくないところがあり、スキル通りにやっても以前のチェックして返却した時と同じように正しく出来ない（正しくないと判断される）
            end.unconfirmed.isNotEmpty() -> SkillResult.verifier_rejected
            outcome == RunOutcome.Unknown -> SkillResult.unreviewed
            LearnGate.done(end, outcome) -> SkillResult.verified
            else -> SkillResult.unverified
        }
        is TaskState.Failed -> when {
            VERIFIER_REJECTED_MARK in end.reason || "核查未通过" in end.reason -> SkillResult.verifier_rejected
            end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit -> SkillResult.failed
            // モデルエラー、実行環境の問題、動作保護停止、プロセス中断: 指示に従って機能するかどうかとは関係ありません。
            else -> SkillResult.unreviewed
        }
        else -> null
    }
}
