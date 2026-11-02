package com.wangzan101.mika.core.agent

/**
 * ユーザー メッセージに添付ファイルを記述する方法: メッセージの最後に、各添付ファイルには「Attachment:/workspace/inbox/<name>(<size>)」という行が含まれます。これらの行は会話内にそのまま保存され、タスクに渡されます。 したがって、
 * ダイアログ ページは、添付ファイルを写真グリッドとファイル カード (アプリの AttachmentMessage) に描画します。言語プロンプト、言語チェックと回答の記憶、およびスキルの引用は、これらの行には含まれません ([UserWords] を参照)。
 * 昔の会話のメッセージもこのように書かれています。
 */
object AttachmentLines {
    private const val PREFIX = "添付:"

    /** の名前には角括弧を含めることができます (「レポート (1).csv」)。サイズは角括弧の最後のペア内の「数値 + B/KB/MB」のみを認識します。パスはサンドボックスの /workspace の下にあります。 */
    private val LINE = Regex("^(?:添付|附件):(/workspace/.+)\\((\\d+(?:\\.\\d+)? (?:B|KB|MB))\\)$")

    /** は、解凍された文です。 [files] は、各添付ファイルのパスとメッセージに記述されたサイズ (「2 KB」) を出現順に示します。 [words] は、添付行以外の単語です (先頭と末尾の空白を削除します)。 */
    data class Parsed(val files: List<Pair<String, String>>, val words: String)

    fun line(path: String, size: String): String = "$PREFIX$path($size)"

    /** 単語が最初に来て、添付ファイルごとに 1 行が続きます。言葉が空の場合は、添付行のみが存在します。 */
    fun compose(text: String, files: List<Pair<String, String>>): String =
        (listOf(text).filter { it.isNotEmpty() } + files.map { (p, s) -> line(p, s) }).joinToString("\n")

    fun parse(text: String): Parsed {
        val files = mutableListOf<Pair<String, String>>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) files += m.groupValues[1] to m.groupValues[2] else rest += l
        }
        if (files.isEmpty()) return Parsed(emptyList(), text.trim())
        return Parsed(files, rest.joinToString("\n").trim())
    }

    /** 添付行を削除すると、添付行なしで返されます。 */
    fun words(text: String): String = parse(text).let { if (it.files.isEmpty()) text else it.words }
}
