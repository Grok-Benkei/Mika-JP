package com.wangzan101.mika.core.userscreen

/**
 * ユーザーの文が現在の画面を参照している場合の書き方: `[[screen:<番号>]]` を 1 行に記述し、他の行は追記 (オプション)。
 * この文は会話、タスクに保存され、通常どおり中断または応答として使用されます。ダイアログ ページとカードはそれに応じてスクリーンショットのバブルとして描画され、モデルに与えるときに指示に変換されます ([UserScreenPrompt] を参照)。
 */
object UserScreenMessage {
    private val LINE = Regex("^\\[\\[screen:([A-Za-z0-9_-]{1,64})]]$")

    /** は 1 つの文に分解できます。[screens] は内部の画面番号 (出現順)、[words] は追記です (先頭と末尾の空白を削除し、空の文字列は空です)。 */
    data class Parsed(val screens: List<String>, val words: String) {
        val hasScreen: Boolean get() = screens.isNotEmpty()
    }

    fun line(id: String): String {
        require(LINE.matches("[[screen:$id]]")) { "bad screen id" }
        return "[[screen:$id]]"
    }

    /** 画面上のセリフが手前、追記が奥です。追記が空の場合はその行のみです。 */
    fun compose(id: String, words: String): String = listOf(line(id), words.trim()).filter(String::isNotEmpty).joinToString("\n")

    fun parse(text: String): Parsed {
        val screens = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) screens += m.groupValues[1] else rest += l
        }
        return Parsed(screens, if (screens.isEmpty()) text.trim() else rest.joinToString("\n").trim())
    }

    fun has(text: String): Boolean = text.lineSequence().any { LINE.matches(it.trim()) }
}
