package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.PageDialog

/** ページのポップアップ ボックスに返信: ブラウザは、ページの実行を続行する前にこの文を待ちます。各実装は 1 回だけ呼び出されます。 */
interface DialogReply {
    /** OK; [text] は入力ボックス プロンプト (プロンプト) にのみ使用されます。 */
    fun accept(text: String?)
    fun dismiss()
}

sealed interface DialogAnswer {
    /** 現時点では返信待ちのポップアップはありません。 */
    data object NoDialog : DialogAnswer
    /** [textIgnored]: テキストが指定されていますが、このポップアップ ボックスは入力ボックス ポップアップ ボックスではないため、テキストは使用されません。 */
    data class Answered(val dialog: PageDialog, val accepted: Boolean, val textIgnored: Boolean) : DialogAnswer
}

/**
 * ラベルおよびその他の応答 (アラート、確認、プロンプト、ページ終了確認) を含むページ ポップアップ ボックス。
 * ポップアップ ボックスが表示されるときは、ユーザーがクリックするのではなく、書き留められるだけです。ラベルはバックグラウンドにあり、モデル ステップは処理されず、ハングしたままになります。次回画面を読み取るときに、[answer] または [close] まで表示されます。
 * スレッドを占有しないことに注意してください。ブラウザはこのページのスクリプトを停止するだけで、インターフェイス スレッドは通常どおり残ります。
 * ページ上に同時に存在するポップアップ フレームは 1 つだけです。別のフレーム (別のフレーム) が来た場合は、「キャンセル」を押して古いフレームを削除し、新しいフレームだけを残します。スレッドセーフ。
 */
class PageDialogs {
    private var pending: Pair<PageDialog, DialogReply>? = null

    /** 現時点で応答を待っているポップアップ ボックス。そうでない場合は null です。 */
    val current: PageDialog? @Synchronized get() = pending?.first

    fun open(dialog: PageDialog, reply: DialogReply) {
        val old = synchronized(this) { pending.also { pending = dialog to reply } }
        old?.second?.dismiss()
    }

    /** は応答してクリアします。応答待ちのポップアップがない場合は何もしません。 */
    fun answer(accept: Boolean, text: String?): DialogAnswer {
        val (dialog, reply) = synchronized(this) { pending.also { pending = null } } ?: return DialogAnswer.NoDialog
        val prompt = dialog.kind == PageDialog.PROMPT
        if (!accept) reply.dismiss()
        else reply.accept(if (prompt) text ?: dialog.defaultText.orEmpty() else null)
        return DialogAnswer.Answered(dialog, accept, textIgnored = text != null && (!prompt || !accept))
    }

    /** ラベルが閉じられてページが変更された場合: ページがスタックしないように、[キャンセル] を押して戻ります。 */
    fun close() {
        val old = synchronized(this) { pending.also { pending = null } }
        old?.second?.dismiss()
    }
}
