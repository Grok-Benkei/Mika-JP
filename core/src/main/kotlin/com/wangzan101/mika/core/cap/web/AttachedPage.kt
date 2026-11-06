package com.wangzan101.mika.core.cap.web

/**
 * ユーザーが、Mika のブラウザで、見ているページに向かって、この文をどのように書くかを尋ねると、最初の段落では、それがどのページであるかを説明します (固定英語、モデルは一目で理解できます。
 * ダイアログページ、カード識別（このセクション）、空行以降は今回ユーザーが発言した内容（そのまま）です。たとえば
 * ```
 * [Web page I'm viewing in Mika's browser. It is attached to this task as your current web tab.]
 * タイトル: ノイズキャンセリングヘッドフォン - 検索結果
 * URL: https://shop.example.com/s?k=ノイズキャンセリングイヤホン
 * Selected text:
 * |インイヤーノイズキャンセリングヘッドフォン B2 Pro
 * [End of page]
 *
 * このページで700のうちから1つ選択してください
 * ```
 * この文は会話内にそのまま保存され、タスクに渡されます。これがモデルから見えるものです。これは、タスクがブラウザ ページから開始されるときに伝達され、新しく選択された単語が間投詞に含まれるときにも伝達されます。
 * 言語プロンプト、回答の言語チェック、およびメモリ引用符は、説明 ([words]) の後にユーザーが入力した単語のみを認識します。
 * 選択した単語の各行は「|」で始まります説明の残りの行から分離されています。ページ タイトルと選択した単語は切り詰められます。
 */
object AttachedPage {
    const val HEADER = "[Web page I'm viewing in Mika's browser. It is attached to this task as your current web tab.]"
    const val END = "[End of page]"
    private const val TITLE = "Title: "
    private const val URL = "URL: "
    private const val SELECTED = "Selected text:"
    private const val SELECTED_LINE = "| "

    /** ページ タイトルには、これまでの単語を含めることができます。 */
    const val MAX_TITLE = 200

    /** 選択した単語は最大でこの単語数までモデルに与えることができます。単語が多すぎる場合は、単語が切り取られ、最後に「...」が追加されます。 */
    const val MAX_SELECTION = 2000

    /** URL は最大でもこの長さです (非常に長い追跡パラメーターは含まれません)。 */
    const val MAX_URL = 1000

    /** あなたが見ているページ: [selection] はユーザーが選択した単語です。選択されていない場合は null になります。 */
    data class Page(val url: String, val title: String, val selection: String? = null)

    /** は解凍された文です。[page] はそのページ (含まれていない場合は null)、[words] はユーザーが今回言ったことです (含まれていない場合は元の単語)。 */
    data class Parsed(val page: Page?, val words: String)

    /** 説明が最初にあり、その後に単語が続きます。言葉が空の場合は、説明だけが存在します。 */
    fun compose(page: Page, words: String): String {
        val lines = mutableListOf(HEADER)
        com.wangzan101.mika.core.security.EgressPolicy.siteHost(page.url)?.let { lines += URL + it }
        lines += "egress_blocked:browser_or_webview"
        lines += END
        val block = lines.joinToString("\n")
        val w = words.trim()
        return if (w.isEmpty()) block else "$block\n\n$w"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return Parsed(null, text)
        val end = lines.indexOfFirst { it.trim() == END }
        if (end < 0) return Parsed(null, text)
        var title = ""
        var url = ""
        val selected = mutableListOf<String>()
        var inSelection = false
        for (l in lines.subList(1, end)) {
            when {
                inSelection && l.startsWith(SELECTED_LINE.trimEnd()) -> selected += l.removePrefix(SELECTED_LINE.trimEnd()).removePrefix(" ")
                l.startsWith(TITLE) -> title = l.removePrefix(TITLE)
                l.startsWith(URL) -> url = l.removePrefix(URL)
                l.trim() == SELECTED -> inSelection = true
            }
        }
        val selection = selected.joinToString("\n").trim().ifEmpty { null }
        return Parsed(Page(url.trim(), title.trim(), selection), lines.drop(end + 1).joinToString("\n").trim())
    }

    /** ページの説明を削除した後。何もせずにそのまま返却してください。 */
    fun words(text: String): String = parse(text).words

    /** この文にはページの説明が含まれています。 */
    fun has(text: String): Boolean = text.lineSequence().firstOrNull()?.trim() == HEADER

    /** 選択された単語: 先頭と末尾の空白を削除し、各行の末尾の空白を削除し、[MAX_SELECTION] を超える場合は切り捨てて「...」を追加します。空の文字は null です。記述の終了行は内部には表示されません。 */
    fun clip(selection: String?): String? {
        val t = selection?.lines()?.joinToString("\n") { it.trimEnd() }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val cut = if (t.length <= MAX_SELECTION) t else t.take(MAX_SELECTION).trimEnd() + "…"
        return cut.replace(END, "[End]")
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
