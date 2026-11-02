package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * プロンプト ワード サーフェス ゴールド サンプル: 全文 (要約ではなく) を書くと、変更をレビューするときに違いを直接確認できます。
 * ゴールド サンプルは core/src/test/resources/prompt-surface/ で再生成されます。
 *   ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
object PromptSurface {
    private val dir = File(System.getProperty("mika.promptSurfaceDir") ?: "src/test/resources/prompt-surface")
    private val update = System.getProperty("mika.updateSnapshots") == "true"

    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("基準ファイルがありません: ${file.path}。./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots で生成してください")
        assertEquals("プロンプトのスナップショット $name が変わりました。意図した変更か確認してから -PupdateSnapshots で再生成してください", file.readText(), actual)
    }

    /** 機能の組み合わせ: システムは単語の全文を要求し、モデルに送信されたツールをリストします (名前 + 定義の概要、定義の全文はツール内にあります)。 */
    fun system(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { appendLine("${it.name} #${hash(spec(it))}") }
    }

    /** リクエストの全体像: システム プロンプト ワードの全文、次にモデルに送信される順序での各ツールの定義の全文。 */
    fun layer(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { append(spec(it)); appendLine() }
    }

    fun tools(all: List<ToolSpec>): String = buildString {
        all.forEach { append(spec(it)); appendLine() }
    }

    private fun spec(t: ToolSpec) = "## ${t.name}\n${t.description}\n${t.parametersJsonSchema}\n"

    /** 内部ロール (標準、検証、説明、位置付けなど) のプロンプト ワードの全文。 */
    fun roles(): String = buildString {
        for (name in ROLES) {
            appendLine("## $name")
            appendLine(role(name))
        }
    }

    private val ROLES = listOf("describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "stop_summary")

    private fun role(name: String): String =
        checkNotNull(PromptSurface::class.java.getResourceAsStream("/prompts/roles/$name.md")) { "missing role prompt $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 「ラベル→モデルに送る言葉」のセットを1つずつ書き出します。 */
    fun entries(items: List<Pair<String, String>>): String = buildString {
        items.forEach { (k, v) -> appendLine("### $k"); appendLine(v) }
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}
