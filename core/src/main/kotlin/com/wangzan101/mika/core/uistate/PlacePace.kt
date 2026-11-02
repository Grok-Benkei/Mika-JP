package com.wangzan101.mika.core.uistate

/** サイトの知識とアプリの知識では、`pace` がこの Web サイトまたはアプリの閲覧速度要件について説明します (アプリのリズムについては、[com.wangzan101.mika.core.cap.phone.AppPacing] を参照してください)。 */
enum class PlacePace {
    /** 特に記載はありません。 */
    NORMAL,
    /** この Web サイトまたはアプリでは閲覧速度が制限されています (閲覧速度が速すぎたり多すぎるとブロックされます)。各ページの一時停止時間が長くなり、1 つのタスクで表示されるページが小さくなり、画面が小さくなります。 */
    CAREFUL;

    companion object {
        /** サイトナレッジヘッダーの値 (`careful`、null として書き込まれません)。 */
        fun of(value: String?): PlacePace = if (value == "careful") CAREFUL else NORMAL
    }
}
