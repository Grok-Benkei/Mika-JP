package com.wangzan101.mika.core.action

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

/** [payment] が true の場合、このステップにはお金がかかります (支払い、注文、または画面に金額がある場合の外部アクション)。質問するときに最初にユーザーに尋ねるだけです。 */
data class Risk(val level: ActionRisk, val payment: Boolean = false)

/** 確認カードでのユーザーの選択: 今回は同意するか、同意しません。 */
enum class ConfirmChoice { Once, Deny;

    val approved: Boolean get() = this != Deny
}

/** クエリ / 自律性: 現時点では自律モードですか (途中で自律モードに切り替わった場合、実行中のタスクは次のステップからそれに続きます)。設定により提供されますが、テストでは固定値に変更されます。 */
fun interface ApprovalSettings {
    /** 自律モード: 最初に支払いを要求することもありません。 */
    fun autonomous(): Boolean

    companion object {
        /** 要求する (デフォルト): 最初にのみ支払いを要求します。 */
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

/** レビューと決定: 純粋な機能。支払いのみを要求する必要があります。誰も対応できない場合（無人、サブタスク）、または携帯電話を使用していない場合（チャネル、スケジュール済み、MCP が開始された場合）、支払いは行われません。 */
object ApprovalPolicy {
    fun decide(risk: Risk, run: RunProvenance = RunProvenance()): Approval = when {
        !risk.payment -> Approval.Allow
        run.attendance == Attendance.Unattended || run.origin != RunOrigin.Ui -> Approval.Deny
        else -> Approval.Ask
    }
}
