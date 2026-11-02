package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.BatchRunner
import com.wangzan101.mika.core.testing.PromptSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.assembly.allTools

/** の階層化ルールは、prompts/ (prompts/README.md を参照): 上級管理者はツール名について言及しておらず、各ツールには説明があり、冗長なファイルはなく、フィードバックは自己完結型です。各レイヤーは大文字で表記されます。 */
class PromptLayoutTest {
    private val root = File("src/main/resources/prompts")
    private fun files(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
    private fun size(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.sumOf { it.readBytes().size }

    private val toolNames = allTools().map { it.name } + BatchRunner.spec(BATCHABLE).name
    /** は、一般的な英語の単語 system/ のツール名でもあります。通常通り言葉として使用され、点呼にはカウントされません。 */
    private val plainWords = setOf("phone_screenshot", "phone_back", "wait", "phone_tap", "phone_scroll", "phone_swipe")

    @Test fun systemLayerNamesNoTools() {
        for (f in files("system")) {
            val text = File(root, "system/$f").readText()
            val named = toolNames.filter { it !in plainWords && Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
            assertTrue("system/$f names tools $named; tool rules belong in capabilities/ or tools/", named.isEmpty())
        }
    }

    /** 一般的なツールの一般的なルールは、いかなる能力を持つツールにも名前を付けないことです (その能力がない場合でもツールは表示されます)。 */
    @Test fun generalNamesNoCapabilityTool() {
        val text = File(root, "capabilities/general.md").readText()
        val named = allTools().filter { it.capability != null }.map { it.name }
            .filter { Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
        assertTrue("capabilities/general.md names capability tools $named; put them in that capability's section", named.isEmpty())
    }

    @Test fun everyToolHasExactlyOneDescriptionFile() {
        val used = allTools().associate { it.name to it.promptFile } + (BatchRunner.spec(BATCHABLE).name to "do_steps")
        val defs = allTools().associate { it.name to it.description } + (BatchRunner.spec(BATCHABLE).name to BatchRunner.spec(BATCHABLE).description)
        for ((tool, file) in used) {
            assertTrue("$tool: missing prompts/tools/$file.md", File(root, "tools/$file.md").isFile)
            // 説明はこのドキュメントからのものです。テンプレート内のプレースホルダーは、「任意のテキスト」に置き換えた後に照合できます。
            val template = File(root, "tools/$file.md").readText().trim()
            val pattern = template.split(Regex("\\{\\{[a-z0-9_]+}}")).joinToString(".+") { Regex.escape(it) }
            assertTrue("$tool does not load prompts/tools/$file.md", Regex(pattern).matches(defs.getValue(tool)))
        }
        assertEquals("orphan or missing files in prompts/tools", used.values.map { "$it.md" }.toSet(), files("tools"))
    }

    /** エントリ ツール (能力グループの収集時にも提供される) にはそれぞれ短い説明があり、冗長性はありません。短い説明では、このタスクに登録されているツールの名前のみが記載されています。 */
    @Test fun everyEntryToolHasShortDescription() {
        val entries = allTools().filter { it.entry != null }.map { it.name }
        assertEquals(setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command"), entries.toSet())
        assertEquals(entries.map { "$it.md" }.toSet(), files("entries"))
        for (e in entries) {
            val text = File(root, "entries/$e.md").readText()
            val named = Regex("(?<![A-Za-z_])(?:phone|web)_[a-z_]+").findAll(text).map { it.value }.filter { it !in toolNames }.toList()
            assertTrue("entries/$e.md names unknown tools $named", named.isEmpty())
        }
    }

    @Test fun noOrphanFiles() {
        assertEquals(setOf("identity.md", "principles.md"), files("system"))
        assertEquals((listOf("phone", "app-access", "batch", "web", "sandbox", "general", "inline-screen", "channels", "groups", "handover")).map { "$it.md" }.toSet(), files("capabilities"))
        assertEquals(Feedback.FILES.map { "$it.md" }.toSet(), files("feedback"))
        val roles = setOf("app_classifier", "describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "outcome_judge", "user_screen", "stop_summary", "conversation_title")
        assertEquals(roles.map { "$it.md" }.toSet(), files("roles"))
        roles.forEach { assertTrue(Prompts.role(it).isNotBlank()) }
        assertEquals(setOf("README.md"), root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet())
    }

    /** フィードバック ディレクトリ内の各キーには使用中のコードがあります (エラー コードの説明によると、セクション全体がプレフィックスに従って取得されます)。 */
    @Test fun noOrphanFeedbackKeys() {
        val code = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = Feedback.keys.filter { k -> "\"$k\"" !in code && !(k.startsWith("failure.") && "\"failure.\"" in code) }
        assertTrue("feedback keys not used by any code: $unused", unused.isEmpty())
    }

    /**
     * 各フィードバックは自己完結型です。先頭または末尾に空白はありません (先頭と末尾の改行は読むときに削除され、それらで区切られたスペルは静かに単語を失います)。
     * コードはフィードバックを受け取らず、+ または文字列テンプレートを使用して他の単語で綴られます。スペルを入力したい場合は、テンプレートに区切りを書いて、{{占位符}} と記入してください。
     */
    @Test fun feedbackEntriesAreSelfContained() {
        for (f in Feedback.FILES) for ((k, v) in Feedback.parse(File(root, "feedback/$f.md").readText())) {
            assertTrue("feedback $k starts or ends with whitespace", v == v.trim())
        }
        val problems = mutableListOf<String>()
        for (file in File("src/main/kotlin").walk().filter { it.extension == "kt" }) {
            val s = file.readText()
            for (m in Regex("""Feedback(\[|\.format\()""").findAll(s)) {
                val end = closing(s, m.range.last)
                val before = s.substring(0, m.range.first).trimEnd()
                val after = s.substring(end + 1).trimStart()
                if (before.endsWith("+") || after.startsWith("+") || before.endsWith("\${")) problems += "${file.name}: ${s.substring(m.range.first, end + 1).take(60)}"
            }
            // フィードバックの名前 (val X get() = Feedback...、fun x(...) = Feedback.format...) をスペルに使用することはできません
            val names = Regex("""val ([A-Z_0-9]+): String get\(\) = Feedback""").findAll(s).map { it.groupValues[1] } +
                Regex("""fun ([a-zA-Z0-9]+)\([^)]*\) = Feedback\.format""").findAll(s).map { it.groupValues[1] }
            for (n in names.toSet()) {
                val used = Regex("""(\+\s*$n\b)|(\b$n\s*\+)|(\$$n\b)|(\$\{$n\b)""")
                // 他のファイルも使用できます
                File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { g ->
                    used.find(g.readText())?.let { problems += "${g.name}: ${it.value} concatenates feedback $n" }
                }
            }
        }
        assertTrue("feedback used as part of a concatenation: $problems", problems.isEmpty())
    }

    /** 左括弧 [open] から開始して、一致する右括弧を検索します。文字列をスキップします。 */
    private fun closing(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i++; while (s[i] != '"') { if (s[i] == '\\') i++; i++ } }
                '(', '[' -> depth++
                ')', ']' -> if (--depth == 0) return i
            }
            i++
        }
        error("unbalanced at $open")
    }

    /**
     * 各レイヤーのサイズ (バイト、UTF-8) は、プロンプト ワード サーフェスのゴールド サンプル (prompt-surface/sizes.txt) に書き込まれます。厳密な上限は設定されていません。
     * の成長は、提出された差分で確認できます。レビューの際に適切かどうかを判断できます。
     */
    @Test fun layerSizesRendered() {
        val text = buildString {
            for (layer in listOf("system", "capabilities", "tools", "entries", "feedback", "roles")) {
                appendLine("$layer: ${size(layer)} bytes, ${files(layer).size} files")
            }
        }
        print(text)
        PromptSurface.check("sizes", text)
    }
}
