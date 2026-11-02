package com.wangzan101.mika.core.userscreen

/** 画面上の単語の行: [x]/[y] がその中心で、クリックと同じ 0 ～ 999 の座標セット (左上 0,0)。 */
data class ScreenLine(val text: String, val x: Int, val y: Int)

/**
 * ユーザーから送信された「この時点の画面」: ユーザーがホーム画面でカプセルまたはカード内のスクリーンショット ボタンをダブルクリックしたときに見ている画面。
 * は、アルバムやファイルから選択した写真と同じものではありません。フロントエンド アプリ、キャプチャされた瞬間、画面上の文字で、「今これを見ています」と表示されます。
 * [packageName]/[appLabel] はフロントエンド アプリです (null として読み取ることはできません)。 [blocked] の場合、ページではスクリーンショットが許可されておらず (アンチスクリーンショット)、画面がなく、[lines] も空です。
 * [lines] は画面上の単語です (アクセシビリティ ツリーでは、ツリーが足りない場合に認識された単語が追加されます)、[fromOcr] には認識された単語が含まれます。
 */
data class UserScreen(
    val id: String,
    val takenAt: Long,
    val packageName: String?,
    val appLabel: String?,
    val blocked: Boolean = false,
    val lines: List<ScreenLine> = emptyList(),
    val fromOcr: Boolean = false,
) {
    /** そのアプリの呼び出し方法: 名前がある場合はその名前を使用し、名前がない場合はパッケージ名を使用します。 */
    val app: String get() = appLabel?.takeIf(String::isNotBlank) ?: packageName ?: UNKNOWN_APP

    companion object {
        const val UNKNOWN_APP = "不明なアプリ"
    }
}

/** 番号に従って、この会話でユーザーによって送信された画面を取得します。説明とスクリーンショットは利用できなくなる可能性があります (読み取ることができず、null を返します)。 */
interface UserScreenSource {
    suspend fun screen(id: String): UserScreen?

    /** スクリーンショット (JPEG または PNG)。ページのスクリーンショットが許可されていない場合、読み取れない場合は null になります。 */
    suspend fun image(id: String): ByteArray?
}

/** まだ送信されていないスクリーンショット: 画面 ([jpeg]、スクリーンショットが許可されていない場合は null) と説明 ([screen]、番号は会話で保存するときに決定されます)。 */
class CapturedScreen(val jpeg: ByteArray?, val screen: UserScreen, val sourcePackages: Set<String> = setOfNotNull(screen.packageName))
