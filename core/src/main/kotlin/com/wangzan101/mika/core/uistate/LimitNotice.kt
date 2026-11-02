package com.wangzan101.mika.core.uistate

/**
 * アクションの後に「上限に達しました」という新しいメッセージがインターフェイスに表示されました。今日の時間が使い果たされました。操作の頻度が高すぎます。明日もう一度お試しください。
 * は、アクションの後に表示される短いテキスト (プロンプト バー、ポップアップ ウィンドウ、ページ ポップアップ ボックス) のみを認識します。アクションの前に画面上にあるテキスト (説明テキスト、ヘルプ ページ) はカウントされません。
 * 長い段落はカウントされません。ウェブサイトやアプリでは認識せず、方法のみで認識します。
 */
object LimitNotice {
    /** 比較時に空白を削除し、大文字と小文字を区別しません。 */
    val WORDS = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限",
        "明天再来", "明日再来", "明天再试", "明日再试", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "reachedthedailylimit", "reachedyourdailylimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )

    /** 「通信制限に達しました」と「1日の配信制限を超えました」という単語がいくつか挟まれています。 */
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")

    /** 「短いテキスト」(空白を削除した後の単語数) の上限を計算します。プロンプト バーおよびポップアップ ウィンドウ内の 1 文または 2 文です。 */
    private const val MAX_CHARS = 120

    /** [before] はアクション前の画面です (読み取れない場合は null となり、画面上のすべてが新しいものとみなされます)。新しく出現した文を返します。そうでない場合は null です。 */
    fun find(before: ScreenSnapshot?, after: ScreenSnapshot?): String? {
        after ?: return null
        val earlier = before?.let(::texts).orEmpty().map(::compact).toSet()
        return texts(after).firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun texts(s: ScreenSnapshot): List<String> =
        s.elements.filter { !it.editable && !it.hint && it.name.isNotBlank() }.map { it.name } + listOfNotNull(s.web?.dialog?.message)

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}
