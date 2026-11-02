package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.memory.NumberText

/**
 * スキルを降下させる際に今回は特定の値を削除します。対象と解答の引用符内の内容、2桁以上の数値列、および金額です。
 * 入稿時には検証で引用した元の画面テキストと今回入力したテキストも具体的な値としてみなします。中国語の名前は認識されず、ユーザーの識別には引用符が使用されます。
 * すべての文字は同じ方法で記述され (NFKC; 漢数字、アラビア数字の文字列に結合された区切られた数字)、比較と置換も同じ方法で実行される必要があります。
 */
object SkillRedactor {
    const val PLACEHOLDER = "<パラメータ>"

    private val QUOTED = listOf("「", "」", "『", "』", "“", "”", "\"", "\"", "‘", "’", "《", "》")
        .chunked(2).map { (open, close) -> Regex(Regex.escape(open) + "([^" + Regex.escape(close) + "\\n]+)" + Regex.escape(close)) }
    private val AMOUNT = Regex("[¥$]\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*(?:元|块钱|块|万元|万|美元)")
    private val DIGITS = Regex("\\d{2,}")
    private val ONLY_DIGITS = Regex("\\d+")
    private val PIECE_SPLIT = Regex("[\\s,.:;!?、。·]+")
    /** ドラフトのヘッダーのこれらの行は識別子であり、番号 (実行番号、パッケージ名) はスキャンされません。ヘッダー内でのみ除外されます。 */
    private val KEEP_LINES = Regex("^(?:name|apps|packages|domains|learned_from|source):")

    fun norm(s: String): String = NumberText.collapse(s)

    fun literals(goal: String, criteria: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        fun quoted(t: String) = QUOTED.forEach { re -> re.findAll(t).forEach { out += it.groupValues[1].trim() } }
        val g = norm(goal)
        quoted(g)
        out += numbers(g)
        criteria.map(::norm).forEach(::quoted)
        return keep(out)
    }

    /**
     * テキスト全体（引用された元の画面テキストと入力されたテキストを確認してください）と、空白や句読点で切り取られた数値、金額、段落が特定の値とみなされます。
     * (「王暁明: また明日」に登場する人物の名前が単独で登場する場合は置き換える必要があります)。
     */
    fun textLiterals(texts: List<String>): Set<String> =
        keep(texts.map(::norm).flatMap { t -> listOf(t.trim()) + numbers(t) + t.split(PIECE_SPLIT) })

    /** 長い値が短い値で分割されないように、最初に長い値を置き換えます。純粋な数値の全体を置き換えるだけです (「12」は「12306」には移動しません)。 */
    fun redact(md: String, literals: Set<String>): String {
        var out = norm(md)
        for (lit in keep(literals.map(::norm)).sortedByDescending { it.length }) {
            out = if (ONLY_DIGITS.matches(lit)) Regex("(?<!\\d)" + lit + "(?!\\d)").replace(out, PLACEHOLDER)
            else out.replace(lit, PLACEHOLDER)
        }
        return out
    }

    /**
     * 再度下書きの全文をスキャンします。残額と数値文字列を 2 桁以上に置き換えます (今回は注文番号、電話番号、価格がほとんどの値です)。
     * [keepDigits] は、アプリ名に付属する番号 (組み込みスキルの名前とエイリアスに表示されます) であり、予約されています。
     */
    fun scrubNumbers(md: String, keepDigits: Set<String>): String {
        val lines = norm(md).lines()
        val frontEnd = if (lines.firstOrNull()?.trimEnd() == "---") (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: -1 else -1
        return lines.mapIndexed { i, line ->
            if (i in 1 until frontEnd && KEEP_LINES.containsMatchIn(line)) line
            else DIGITS.replace(AMOUNT.replace(line) { m -> if (m.value.filter(Char::isDigit) in keepDigits) m.value else PLACEHOLDER }) { m ->
                if (m.value in keepDigits) m.value else PLACEHOLDER
            }
        }.joinToString("\n")
    }

    private fun numbers(t: String) = AMOUNT.findAll(t).map { it.value.trim() } + DIGITS.findAll(t).map { it.value }

    // 単語の値を置き換えると、テキスト (「mother」と「number」) が混乱します。純粋な数値はこの制限の対象になりません
    private fun keep(values: Iterable<String>) =
        values.map { it.trim() }.filter { it.isNotEmpty() && (it.length >= 2 || ONLY_DIGITS.matches(it)) }.toSet()
}
