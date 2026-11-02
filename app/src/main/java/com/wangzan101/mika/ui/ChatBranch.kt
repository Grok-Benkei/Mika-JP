package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage

/**
 * 現時点で [turn] ラウンド (0 から開始) を分岐点として使用できますか: ラウンドは範囲内にあり、このラウンドは終了し、ダイアログはブロックされていません ([blocked]: 実行中またはキューに入れられたタスクがあります。
 * が削除中、入力ボックスがビジー状態など、呼び出し側によって計算されます)。メニューに「ここから分岐」と表示されているかどうか、クリックした後に何かをするかどうかは、ただ見るだけです。
 */
internal fun canBranchAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    !blocked && turn in exchanges.indices && exchanges[turn].status.branchable

/** [turn] ラウンドで、この時点で「ここからもう一度質問してください」: 分岐 ([canBranchAt]) できますか。このラウンドでユーザーが言ったことは空ではありません。メニューとアクションを見てください。 */
internal fun canRetryAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    canBranchAt(exchanges, turn, blocked) && exchanges[turn].goal.isNotBlank()

/** メッセージキー `<prefix><轮次>` のラウンド。このタイプのメッセージではない場合、null になります。 */
private fun turnOf(key: String, prefix: String): Int? =
    if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull() else null

/**
 * の長押しメッセージを分岐点として使用できますか。ミカの回答 (`r<轮次>`) と [canBranchAt] のみがラウンドを返すために確立され、それ以外の場合は null になります。
 * ユーザーの発言、中断、ステータスカード、プロセス行はカウントされません。
 */
internal fun branchableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "r")?.takeIf { canBranchAt(exchanges, it, blocked) }

/** このラウンドは終了しました (成功、失敗、停止はすべてカウントされます)。実行中のもの、処理を待っているもの、キューに入っているものはカウントされません。 */
internal val ExchangeStatus.branchable: Boolean
    get() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> true
        else -> false
    }

/** 「ここからもう一度質問する」の結果: 新しい会話番号、事前入力されたテキスト、元のメッセージに再追加する必要がある添付ファイル/画面があるかどうか。 */
data class BranchRetry(val conversationId: String, val prefill: String, val needsReattach: Boolean)

/**
 * ユーザー自身の言葉のうち、長押しすることで「ここから再質問」できるもの: `u<轮次>` および [canRetryAt] はラウンドに戻り、それ以外の場合は null。
 * 間投詞 (`m…`)、返信、ステータス カードはカウントされません。
 */
internal fun retryableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "u")?.takeIf { canRetryAt(exchanges, it, blocked) }

/** 再質問できる元の文のテキスト: Web ページの説明、引用、添付ファイルの行、および画面の行を削除します。添付ファイル/画面のみを送信する場合は空になります。 */
internal fun retryText(raw: String): String =
    UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).words.trim()

/** 元のメッセージには添付ファイルまたは画面が含まれています。これらは、再度質問しても自動的に添付されず、インターフェースにより再度追加するよう求められます。 */
internal fun needsReattach(raw: String): Boolean {
    val attached = AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw)))
    return attached.hasAttachments || UserScreenMessage.parse(attached.words).hasScreen
}
