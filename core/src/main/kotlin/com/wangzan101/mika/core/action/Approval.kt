package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance

/**
 * アクションのリスク レベル (承認レコードに記録され、これに基づいて検証され、タスクが外部のことを実行したことがわかります)。 [Read] インターフェイス間の表示または移動のみを行います。 [Change] インターフェイス上で項目を変更します (クリック、入力)。
 * [External] 他の人に何かを与える (送信、送信); [Irreversible] 取り消すことはできません (支払い、注文、削除)。 [Unknown] クリックした内容がはっきりと見えません。
 */
enum class ActionRisk { Read, Change, External, Irreversible, Unknown;

    /** は画面の外の世界に影響を与えます (影響するかどうかはわかりません)。 */
    val outward: Boolean get() = this == External || this == Irreversible || this == Unknown
}

/** 支出を伴う場合は [payment]、画面外の操作で確認が必要な場合は [ask] を指定します。同意は内容が完全一致する1回だけに有効です。 */
data class Risk(val level: ActionRisk, val payment: Boolean = false, val ask: ConfirmationRequest? = null)

/** 確認カードでのユーザーの選択: 今回は同意するか、同意しません。 */
enum class ConfirmChoice { Once, Deny;

    val approved: Boolean get() = this != Deny
}

/** クエリ / 自律性: 現時点では自律モードですか (途中で自律モードに切り替わった場合、実行中のタスクは次のステップからそれに続きます)。設定により提供されますが、テストでは固定値に変更されます。 */
fun interface ApprovalSettings {
    /** 自律モード: 最初に支払いを要求することもありません。 */
    fun autonomous(): Boolean

    companion object {
        /** 確認モード（既定）では支出と [Risk.ask] の操作をユーザーへ確認します。 */
        val ASK = ApprovalSettings { false }
    }
}

sealed interface Approval {
    /** 質問する必要はありません。 */
    data object Allow : Approval
    /** は今回はユーザーの同意が必要です（一回限りの同意がある場合はチェックして使用してください）。 */
    data object Ask : Approval
    /** ユーザーにこの操作の許可を求めることはできないため、アクションを停止する必要があります。 */
    data object Deny : Approval
}

/** 支出とツールが申告した確認要求を判定します。確認モードで本人へ聞けない無人・非UIタスクでは実行しません。 */
object ApprovalPolicy {
    fun decide(risk: Risk, run: RunProvenance = RunProvenance()): Approval = when {
        !risk.payment && risk.ask == null -> Approval.Allow
        run.attendance == Attendance.Unattended || run.origin != RunOrigin.Ui -> Approval.Deny
        else -> Approval.Ask
    }
}
