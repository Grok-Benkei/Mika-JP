package com.wangzan101.mika.core.script

/**
 * モデルまたはサイトコマンドで記述された JS ソースコードの字句チェック。
 *
 * [usesImport]: 文字列、コメント、通常のリテラル以外に `import` という単語はありますか (動的インポートでは、読み取り専用パッケージをバイパスして、グローバル スコープでモジュールが実行されます)。
 * `/` が除算記号なのか正規表現の始まりなのか、辞書を見ただけでははっきりしないことがあります (`)`、`}`、`++` の後、および of/yield/await などの単語の後。これらはキーワードまたは変数名である可能性があります)。
 * このとき、両方の読み取り方法を実行します。どちらの読み取り方法も、コード内の読み取りインポートとしてカウントされます。発音 (位置、`/` の発音、括弧スタック) を押すと重複が削除され、重複しません。
 * 読み取り不可能な読み取り方法 (通常または文字列が閉じずに行末に到達する) が無効です。失敗するよりは、誤ってブロックする方が良いでしょう。
 */
object JsSource {
    private enum class Slash { Regex, Divide, Both }

    private data class State(val pos: Int, val slash: Slash, val stack: List<Char>)

    /** の後の `/` は、正規表現で始まるキーワードである必要があります。 */
    private val BEFORE_EXPRESSION = setOf("return", "typeof", "instanceof", "in", "new", "delete", "void", "throw", "case", "do", "else")
    /** はキーワードまたは変数名です。次の `/` は両方の方法で読み取ることができます。 */
    private val EITHER = setOf("of", "yield", "await", "let", "async", "get", "set", "static")

    fun usesImport(code: String): Boolean {
        val seen = HashSet<State>()
        val work = ArrayDeque<State>()
        work += State(0, Slash.Regex, emptyList())
        while (work.isNotEmpty()) {
            val s = work.removeLast()
            if (!seen.add(s)) continue
            if (Lexer(code, s) { work += it }.run()) return true
        }
        return false
    }

    /** は、[start] から始まる読み取り値を読み取ります。あいまいな `/` に遭遇すると、2 つの読み取り値を [fork] に渡して停止します。インポートを読み取ると true が返されます。 */
    private class Lexer(private val code: String, start: State, private val fork: (State) -> Unit) {
        private var i = start.pos
        private var slash = start.slash
        private val stack = start.stack.toMutableList()
        private val n = code.length

        fun run(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && peek(1) == '/' -> { while (i < n && !lineEnd(code[i])) i++ }
                    c == '/' && peek(1) == '*' -> { val e = code.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                    c == '/' -> when (slash) {
                        Slash.Divide -> { i++; slash = Slash.Regex }
                        Slash.Regex -> { if (!regex()) return false }
                        Slash.Both -> {
                            fork(State(i, Slash.Regex, stack.toList()))
                            fork(State(i, Slash.Divide, stack.toList()))
                            return false
                        }
                    }
                    c == '\'' || c == '"' -> { if (!string(c)) return false; slash = Slash.Divide }
                    c == '`' -> { i++; if (!template()) return false }
                    c == '{' -> { stack += '{'; i++; slash = Slash.Regex }
                    c == '}' -> {
                        i++
                        if (stack.lastOrNull() == '`') { stack.removeAt(stack.lastIndex); if (!template()) return false }
                        else { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); slash = Slash.Both }
                    }
                    c == ')' -> { i++; slash = Slash.Both }
                    c == ']' -> { i++; slash = Slash.Divide }
                    (c == '+' || c == '-') && peek(1) == c -> { i += 2; slash = Slash.Both }
                    c.isDigit() || (c == '.' && peek(1)?.isDigit() == true) -> { while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++; slash = Slash.Divide }
                    identStart(c) -> {
                        val word = word()
                        if (word == "import") return true
                        slash = when (word) {
                            in BEFORE_EXPRESSION -> Slash.Regex
                            in EITHER -> Slash.Both
                            else -> Slash.Divide
                        }
                    }
                    else -> { i++; slash = Slash.Regex }
                }
            }
            return false
        }

        private fun peek(k: Int): Char? = code.getOrNull(i + k)
        private fun lineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
        private fun identStart(c: Char) = c == '$' || c == '_' || c == '\\' || c.isLetter()
        private fun identPart(c: Char) = identStart(c) || c.isDigit() || c == '‌' || c == '‍'

        /** は識別子です。 `\uXXXX` と `\u{…}` はエスケープされて比較されます。 */
        private fun word(): String {
            val out = StringBuilder()
            while (i < n && identPart(code[i])) {
                if (code[i] == '\\' && peek(1) == 'u') {
                    val braced = peek(2) == '{'
                    val from = i + if (braced) 3 else 2
                    val to = if (braced) code.indexOf('}', from).takeIf { it > 0 } ?: n else minOf(from + 4, n)
                    code.substring(from, to).toIntOrNull(16)?.let { out.appendCodePoint(it) }
                    i = if (braced) minOf(to + 1, n) else to
                } else out.append(code[i++])
            }
            return out.toString()
        }

        /** 文字列リテラル。行の終わりまで閉じていない場合 (行の継続ではない場合) は false です。 */
        private fun string(q: Char): Boolean {
            i++
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == q -> { i++; return true }
                    lineEnd(c) -> return false
                    else -> i++
                }
            }
            return false
        }

        /** テンプレート文字列のテキスト部分。現在位置からバックティック (終了) または `${` (スタックにプッシュ、コードに戻る) を読み取ります。 */
        private fun template(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { i++; slash = Slash.Divide; return true }
                    c == '$' && peek(1) == '{' -> { stack += '`'; i += 2; slash = Slash.Regex; return true }
                    else -> i++
                }
            }
            return false
        }

        /** 通常のリテラル (文字クラスの `/` を含む) およびフラグ。行末で閉じていない場合は false になります。 */
        private fun regex(): Boolean {
            i++
            var inClass = false
            while (i < n) {
                val c = code[i]
                when {
                    lineEnd(c) -> return false
                    c == '\\' -> i += 2
                    c == '[' -> { inClass = true; i++ }
                    c == ']' -> { inClass = false; i++ }
                    c == '/' && !inClass -> {
                        i++
                        while (i < n && identPart(code[i])) i++
                        slash = Slash.Divide
                        return true
                    }
                    else -> i++
                }
            }
            return false
        }
    }
}
