package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.prompt.Feedback

/**
 * ツリーに表示されていない入力ボックスに入力します (アプリは独自のインターフェイスを描画します。ツリーには入力ボックスはありませんが、フォーカスは保持されている可能性があります)。
 * 文字がクリップボードに貼り付けられ、ボックス内の文字を読み取ることができません。これらは、入力の前後に画面上で読み取られた文字 (OCR) とのみ比較できます。
 * は入力メソッドを必要としません。全選択キーと貼り付けキーはこの画面上のフォーカスのあるウィンドウに送信され、フォーカスのあるフレームは入力メソッドなしで貼り付けられます。
 */
object UnseenField {
    private const val NO_FOCUS = "no_focus"
    /** あるポイントで入力すると、貼り付ける前後のポイントは変更されません。そのポイントは入力ボックスではありません。 */
    const val POINT_NOT_TYPED = "point_not_typed"

    /**
     * 貼り付け後に報告される内容。 [keyboardUp]: 貼り付ける前に入力メソッドが画面上で再生されています (フォーカスは確かに特定のボックスにあります)。
     * 入力方法がなく、前画面と後画面の文字がまったく変わっていません (MISMATCH): ほとんどのフォーカスがフレーム上にありません。no_focus が報告されます。最初にフレームをモデリングし、やみくもに再接続しないでください。
     * インプットメソッドの再生時に検証結果が引き継がれます（インプットメソッドが入力されていない場合は、インプットツールが報告します）。
     * [atPoint]: 最初にモデルによって指定された点をクリックしてから貼り付けました。前後のポイントは変わっていませんが、そのポイントが単語をキャッチできませんでした (再生の入力方法に関係なく)。レポートは [POINT_NOT_TYPED] です。モデル内でフレームを再度検索してください。自動的には再試行されません。
     */
    fun typed(
        text: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean, keyboardUp: Boolean, atPoint: Boolean = false,
    ): ActionResult {
        val v = verify(text, before, after, changed)
        if (v == Verification.MISMATCH && atPoint) return ActionResult.Failed(POINT_NOT_TYPED, Feedback["type.point_not_typed"])
        if (v == Verification.MISMATCH && !keyboardUp) return ActionResult.Failed(NO_FOCUS, Feedback["type.unseen_not_focused"])
        return ActionResult.Ok(changed = changed, verification = v, hint = Feedback["type.unseen_pasted"])
    }

    /** すべてを選択して削除キーを押した後に報告される内容: 入力メソッドは再生されず、インターフェイスはまったく変更されません。 [typed]と同じです。まずモデルのボックスをクリックしてください。残りはそのままお渡しし、清算方法を添付します。 [atPoint] は [typed] と同じです。 */
    fun cleared(r: ActionResult, keyboardUp: Boolean, atPoint: Boolean = false): ActionResult = when {
        r !is ActionResult.Ok -> r
        r.changed == false && atPoint -> ActionResult.Failed(POINT_NOT_TYPED, Feedback["clear.point_not_cleared"])
        r.changed == false && !keyboardUp -> ActionResult.Failed(NO_FOCUS, Feedback["clear.unseen_not_focused"])
        else -> r.copy(hint = Feedback["clear.unseen_cleared"])
    }

    /** はそのポイントをクリックし、[pkg] に変更しました (リンクとボタンをクリックしました): 入力する必要はありません。戻ってフレームを再度見つけてください。 */
    fun tapSwitched(pkg: String): ActionResult = ActionResult.Failed(POINT_NOT_TYPED, Feedback.format("type.point_switched", "pkg" to pkg))

    /** チェック対象の単語 (空白と句読点を削除した後) がこの長さを超える場合は、先頭と末尾の [PROBE] 文字のみを確認します。 長い文章 OCR は、中間の単語を誤解することがよくあります。 */
    private const val LONG = 16
    private const val PROBE = 6

    /**
     * チェック結果: 入力後、入力前よりも 1 回多く単語が画面に表示されます ([Verification.MATCH])。単語は前後で読み取られますが、単語は同じであり、インターフェイスと画面は移動しません。これは不一致 (入力されていません) です。
     * 残り（今回は単語を読んでいなかったか、変更されていましたが単語が見つかりませんでした）は不明です、推測はできません。 [changed]: 入力の前後でインターフェイスまたは画面に変化があります。
     */
    fun verify(typed: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean): Verification {
        val want = key(typed)
        if (after != null && before != null && want.isNotEmpty()) {
            val probes = if (want.length <= LONG) listOf(want) else listOf(want.take(PROBE), want.takeLast(PROBE))
            val a = joined(after); val b = joined(before)
            if (probes.all { count(a, it) > count(b, it) }) return Verification.MATCH
        }
        if (!changed && after != null && before != null && joined(after) == joined(before)) return Verification.MISMATCH
        return Verification.UNKNOWN
    }

    /** は、中心が [box] (入力メソッド ウィンドウなどの 0 ～ 999 の [l,t,r,b]) に該当する行を削除します。キーボード上の単語と候補単語はボックスにカウントされません。 */
    fun outside(lines: List<VisibleTextLine>, box: List<Int>?): List<VisibleTextLine> {
        if (box == null || box.size != 4) return lines
        return lines.filterNot { l ->
            l.bounds.size == 4 && (l.bounds[0] + l.bounds[2]) / 2 in box[0]..box[2] && (l.bounds[1] + l.bounds[3]) / 2 in box[1]..box[3]
        }
    }

    /** 上から下、左から右に接続します。 OCR は文を複数の行に分割し、句読点やスペース、文字、数字、漢字のみを誤解します。 */
    private fun joined(lines: List<VisibleTextLine>): String =
        lines.sortedWith(compareBy({ it.bounds.getOrElse(1) { 0 } }, { it.bounds.getOrElse(0) { 0 } })).joinToString("") { key(it.text) }

    private fun key(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }
}
