package com.wangzan101.mika.core.prompt

/**
 * ツールの結果と、メイン ループでモデルに与えられるフィードバック テキスト (プロンプト、エラーの説明、リマインダー、参考資料のタイトル) はすべて、prompts/フィードバック/ の下にある .md ファイル内にあります。
 * 各エントリは「## key」の行で始まり、次のエントリの前の元のテキストが次のとおりです (複数行にすることもできます。先頭と末尾の空白行はカウントされません)。 {{name}} は呼び出し側によって入力されます。
 */
object Feedback {
    /** フィードバック ファイル;キーはすべてのファイル間で一意です。 */
    val FILES = listOf("tools", "loop")
    private val HEADING = Regex("^## ([a-z0-9_.]+)$")

    private val entries: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (f in FILES) parse(Prompts.read("feedback/$f.md")).forEach { (k, v) -> check(out.put(k, v) == null) { "duplicate feedback key $k" } }
        out
    }

    /** 1つのフィードバックファイルの中の各条（キー → 原文）を、出現順に; 最初の条より前の説明は含めない。 */
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var key: String? = null
        val body = mutableListOf<String>()
        fun flush() { key?.let { out += it to body.joinToString("\n").trim('\n') }; body.clear() }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line)
            if (m != null) { flush(); key = m.groupValues[1] } else if (key != null) body += line
        }
        flush()
        return out
    }

    operator fun get(key: String): String = Prompts.fill(raw(key), emptyMap())

    fun format(key: String, vararg vars: Pair<String, Any?>): String = Prompts.fill(raw(key), vars.toMap())

    fun has(key: String): Boolean = key in entries

    /** キーが [prefix] で始まる各条で、キーから接頭辞を取り除いたもの（たとえばエラーコードごとに与えられた説明）。 */
    fun section(prefix: String): Map<String, String> =
        entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    val keys: Set<String> get() = entries.keys

    private fun raw(key: String): String = checkNotNull(entries[key]) { "missing feedback $key" }
}
