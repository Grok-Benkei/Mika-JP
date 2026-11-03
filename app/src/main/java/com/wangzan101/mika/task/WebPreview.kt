package com.wangzan101.mika.task

/**
 * Web インターフェイスに表示されるピクチャ イン ピクチャの小さなウィンドウは何ですか (純粋なロジック、[TaskRun] は、押すと 0.5 秒ごとに更新されます)。
 *
 * の小さなウィンドウの画像は、Web ページの表示にぶら下がっている現在のラベルからのものです。バックグラウンド一時タグ(アドレス付き`web_read_page`)内のWebページのみを読み取る場合、
 * Web ページ表示にはラベルがなく、描画は単なる白い背景です。現時点では Web ページ上ではカウントされず、小さなウィンドウも表示されず、保留されたカプセルもダイアログ カードとして表示されます。
 * 表示されているページを開くと、通常どおり表示されます。ページがユーザーの手に渡ったとき (乗っ取られたとき)、Web ページ上にあるものとしてカウントされますが、画面は表示されません。
 */
enum class WebPreview(val open: Boolean) {
    /** は Web ページ上にありません: Web ページに含まれておらず、画面は移動しません (インターフェイスを変更するとクリアされます)。 */
    Off(false),

    /** は Web ページ上にありますが、表示されるラベルがありません (背景の一時ラベルでのみ読み取ることができます)。Web ページ上にありません。画面をクリアしてください。 */
    NoPage(false),

    /** Web ページでは、ページはユーザーの手にあり、Web ページ上でカウントされ、画面は表示されません。 */
    Hidden(true),

    /** Web ページ上に、最新のフレームを配置する表示ページがあります。 */
    Frames(true);

    companion object {
        fun of(onWeb: Boolean, hasTab: Boolean, handedOver: Boolean): WebPreview = when {
            !onWeb -> Off
            !hasTab -> NoPage
            handedOver -> Hidden
            else -> Frames
        }
    }
}
