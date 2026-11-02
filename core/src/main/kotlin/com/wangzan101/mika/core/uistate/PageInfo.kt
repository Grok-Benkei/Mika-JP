package com.wangzan101.mika.core.uistate

/**
 * Web ページを読み取るときに ScreenSnapshot に添付されるページ情報。携帯電話で画面を読むときは空です。
 * [truncated]: ページ要素が単一制限を超えているため、リストはドキュメントの順序で切り捨てられます。
 */
data class WebInfo(
    val url: String,
    val title: String,
    val tabId: String,
    val httpStatus: Int?,
    val truncated: Boolean = false,
    /** 要素の融合が予算を超えており、リストされていない要素の数を超えています。 */
    val omitted: Int = 0,
    /** ページ上にポップアップ ボックスが表示されますが、応答を待っています。これが存在すると、ページ スクリプトが停止し、要素テーブルを読み取ることができなくなります。 */
    val dialog: PageDialog? = null,
)

/**
 * ページ自体にポップアップするボックス (window.alert/confirm/prompt、確認するためにページを離れます)。 [kind] は、[ALERT]、[CONFIRM]、[PROMPT]、および [BEFORE_UNLOAD] のいずれかです。
 * [message] はページによって与えられるワードです (命令ではなくページ データ)。 [defaultText] は、入力ボックスのポップアップ ボックスに事前に入力されている単語です。
 */
data class PageDialog(val kind: String, val message: String, val defaultText: String? = null) {
    companion object {
        const val ALERT = "alert"
        const val CONFIRM = "confirm"
        const val PROMPT = "prompt"
        const val BEFORE_UNLOAD = "beforeunload"
        /** モデルと判定の文字数は最大でもこれくらいです。 */
        const val MAX_MESSAGE = 500
    }
}
