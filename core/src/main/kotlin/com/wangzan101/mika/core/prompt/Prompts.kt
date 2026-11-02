package com.wangzan101.mika.core.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * App と一緒に配布されるプロンプトはすべてリソースディレクトリ prompts/ の下にあり、階層は prompts/README.md を参照:
 * システム/アイデンティティ (L0)、機能/各機能の 1 つのセクション (L1)、ツール/各ツールの説明、フィードバック/ツールとメイン ループからモデルへのフィードバック、
 * の役割/内部の役割 (標準、検証、説明、位置付けなどのリスト)。
 * テキスト内の {{name}} は発信者によって入力されます。省略した場合、エラーが直接報告され、プレースホルダーはモデルに送信されません。
 */
object Prompts {
    private val cache = ConcurrentHashMap<String, String>()

    /** リソースの元のテキスト (先頭と末尾の空白を削除)。 */
    fun read(path: String): String = cache.getOrPut(path) {
        checkNotNull(Prompts::class.java.getResourceAsStream("/prompts/$path")) { "missing prompts/$path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }

    fun system(name: String): String = read("system/$name.md")

    fun capability(name: String): String = read("capabilities/$name.md")

    fun role(name: String): String = read("roles/$name.md")

    /** 一連の入力ツールの簡単な説明 (prompts/entries/<ツール名>.md)。 */
    fun entry(tool: String): String = read("entries/$tool.md")

    /** ツールはモデル (prompts/tools/<file>.md) に指示を送信し、プレースホルダーに入力します。 */
    fun tool(file: String, vararg vars: Pair<String, Any?>): String = fill(read("tools/$file.md"), vars.toMap())

    /** ワンパス置換：入力値（画面上の文字、ユーザーの文字）に {{…}} があった場合でも、そのまま保持されます。 */
    fun fill(template: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(template) { m ->
        val k = m.groupValues[1]
        check(k in vars) { "unfilled placeholder {{$k}} in: ${template.take(80)}" }
        vars[k].toString()
    }

    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")
}
