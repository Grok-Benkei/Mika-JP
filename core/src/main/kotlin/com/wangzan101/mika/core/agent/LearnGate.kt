package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.userscreen.UserScreenMessage

/**
 * 1 回の実行で学習が「完了」したとみなしてよいか: 個人のスキルとアプリの知識の草案、経験ルートと成功のカウントの記録、
 * これが、試用版のスキルとアプリの知識を正式な使用に移す唯一の方法です。
 *
 * は、答え ([OutcomeJudge]) を渡した後のエンディングを見ますが、最終検証は見ていません。答え ([TaskState.Succeeded]) を渡した後、エンディングは [RunOutcome.Done] です。
 * には、一度返された後に一致しない値はありません。検証済み ([TaskState.Succeeded.reviewed]) の場合は、合格したことを検証する必要があります。ブロックされましたが失敗しました。実行されていない場合はブロックされません。
 */
object LearnGate {
    fun done(end: TaskState, outcome: RunOutcome): Boolean =
        end is TaskState.Succeeded && outcome == RunOutcome.Done && end.unconfirmed.isEmpty() && !end.doubtful

    /**
     * このランはエクスペリエンス ルートとして記録されません。ルートはオープン、完了しており ([done])、ラン番号が付いています (ルートは配置されたイベントから取得されます)。
     * ユーザー画面でターゲットが記憶されません。意味は画面上にあり、次回の単語記憶のルートは一致しません。
     */
    fun shouldSaveRoute(routesOn: Boolean, end: TaskState, outcome: RunOutcome, runId: String?, goal: String): Boolean =
        routesOn && done(end, outcome) && runId != null && !UserScreenMessage.has(goal)
}
