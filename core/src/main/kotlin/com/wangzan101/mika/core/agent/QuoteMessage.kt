package com.wangzan101.mika.core.agent

/**
 * ユーザーが前のメッセージを引用する場合の書き方: 引用の最初の行は、それが誰の引用であるかを示します (固定英語、モデルは一目で理解できます。ダイアログ ページはこの行を認識します)。
 * すると、「>」で始まる各行が引用内容、空行以降が今回ユーザーが発言した内容になります。たとえば
 * ```
 * > [Quoting your earlier reply]
 * > 明日の朝は小雨が降るでしょう...
 *
 * 傘は必要ですか?
 * ```
 * この文はそのままダイアログに保存され、タスクに引き継がれます（新規タスク、割り込みも同様）。これがモデルから見えるものです。ダイアログ ページでは、引用されたコンテンツがバブルの上に小さな明るい色の部分として描画されます。
 * によって引用された段落: 言語プロンプトと回答の言語チェックはカウントされません。記憶とスキルの引用は、ユーザー自身が言ったことを引用した場合にのみカウントされます ([UserWords] を参照)。昔の会話の単語にはこのセリフがなく、そのまま表示されます。
 */
object QuoteMessage {
    /** 誰の言葉を引用：ミカの答えはやはりユーザー自身の言葉です。 */
    enum class From(val header: String) {
        Mika("> [Quoting your earlier reply]"),
        You("> [Quoting my earlier message]"),
    }

    /** の引用内容は、せいぜい多くの単語でしかモデルに伝えることができません。それ以上ある場合は切り取られ、最後に「…」が追加されます。 */
    const val MAX_CHARS = 1000

    data class Quote(val from: From, val text: String)

    /** の分割文: [quote] は引用されたもの (引用されていない場合はヌル)、[words] はユーザーが今回言ったことです (引用されていない場合は元の単語)。 */
    data class Parsed(val quote: Quote?, val words: String)

    /** で引用された内容: 先頭と末尾の空白を削除し、[MAX_CHARS] を超える場合は切り捨てて「...」を追加します。 */
    fun clip(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS).trimEnd() + "…"
    }

    /** 引用が最初にあり、単語が続きます。引用がない場合、または引用内容が空の場合は原文です。 */
    fun compose(quote: Quote?, words: String): String {
        val body = quote?.let { clip(it.text) }?.takeIf { it.isNotEmpty() } ?: return words
        val block = (listOf(quote.from.header) + body.lines().map { if (it.isBlank()) ">" else "> $it" }).joinToString("\n")
        return if (words.isEmpty()) block else "$block\n\n$words"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        val from = From.entries.firstOrNull { it.header == lines.firstOrNull()?.trim() } ?: return Parsed(null, text)
        var i = 1
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].startsWith(">")) {
            quoted += lines[i].removePrefix(">").removePrefix(" ")
            i++
        }
        return Parsed(Quote(from, quoted.joinToString("\n").trim()), lines.drop(i).joinToString("\n").trim())
    }

    /** 参照を削除した後。参照がない場合はそのまま返却されます。 */
    fun words(text: String): String = parse(text).words
}
