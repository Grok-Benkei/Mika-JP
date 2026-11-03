package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.wangzan101.mika.core.tool.Verb
import org.junit.Test
import java.io.File

/**
 * アプリとともにリリースされたプロンプトに記載されている知識、スキル、ツール名を今すぐ登録する必要があります。
 * 画面プレフィックスが付いた名前 (phone_...、web_...)、名前変更前の古い名前 (browser_...、launch_app など)、およびバッククォート内の古い名前はすべてツールとして記載されています。
 * は古い名前の比較表も保持しています。古い名前はそれぞれ現在のツールに適しています。
 */
class ToolNamesInTextTest {
    private val roots = listOf("src/main/resources/knowledge", "src/main/resources/skills", "src/main/resources/prompts").map(::File)
    private val known = knownToolNames()
    private val legacy = legacyNames()
    private val prefixed = Regex("(?<![A-Za-z0-9_{.=&/-])(?:phone|web|browser)_[a-z0-9_]*[a-z0-9](?![A-Za-z0-9_])")
    private val word = Regex("(?<![A-Za-z0-9_`{.=&/-])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`}])")
    private val ticked = Regex("`([a-z][a-z0-9_]*)`")

    @Test fun everyToolMentionIsARegisteredTool() {
        val bad = mutableListOf<String>()
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile && it.extension in setOf("md", "json") }) {
            val text = f.readText()
            val mentions = prefixed.findAll(text).map { it.value } +
                word.findAll(text).map { it.value }.filter { legacy.replacements(it).isNotEmpty() } +
                ticked.findAll(text).map { it.groupValues[1] }.filter { legacy.replacements(it).isNotEmpty() }
            mentions.filter { it !in known }.distinct().forEach { bad += "${f.path}: $it" }
        }
        assertTrue("tool names that are not registered:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * 知識とスキルには、モデルの実行を指示するすべてが存在する必要があります: スクリプト パス (`scripts/x.py`、`sites/a.b/scripts/x.py`、単一ファイル名 `x.py`)
     * は、リスト内で実行可能としてマークされたスクリプトと一致する必要があります。コマンドとして使用される識別子 (バックティックの `a_b` の前後に「use / run / call / command / script」が続きます) 登録されたツール、ツール パラメータ、またはヘッダー キーの場合は、
     * 、または呼び出されたときに `a_b(` として書き込まれます。
     */
    @Test fun everyCommandMentionExists() {
        val bad = commandMentions(File("src/main/resources"))
        assertTrue("commands or scripts that do not exist:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun commandMentions(resources: File): List<String> {
        val runnable = com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog().runnableScripts()
        val byFileName = runnable.map { it.substringAfterLast('/') }.toSet()
        val allowed = known + allTools().flatMap { it.def.props.keys } + KnowledgeIndex.KEYS
        val bad = mutableListOf<String>()
        for (dir in listOf("knowledge", "skills")) for (f in File(resources, dir).walkTopDown().filter { it.isFile && it.extension == "md" }) {
            val rel = f.relativeTo(resources).invariantSeparatorsPath
            // エントリ ディレクトリ。run_bundled_script のスキル パラメータと同じ方法で記述されます: スキル/ドキュメント、スキル/ドキュメント
            val entry = rel.removePrefix("knowledge/").split('/').take(2).joinToString("/")
            val text = f.readText()
            for (m in scriptPath.findAll(text)) {
                val path = m.value.removePrefix("/opt/mika/skills/").let { if (it.startsWith("scripts/")) "$entry/$it" else it }
                if (path !in runnable) bad += "$rel: ${m.value}"
            }
            scriptFile.findAll(text).map { it.value }.filter { it !in byFileName }.forEach { bad += "$rel: $it" }
            for (m in tickedIdentifier.findAll(text)) {
                val name = m.groupValues[1]
                if (name in allowed) continue
                val before = text.substring(maxOf(0, m.range.first - 16), m.range.first).split('\n', '。', '；', ';').last()
                val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 9))
                if (callBefore.containsMatchIn(before) || callAfter.containsMatchIn(after)) bad += "$rel: $name"
            }
            called.findAll(text).map { it.groupValues[1] }.filter { it !in known }.forEach { bad += "$rel: $it(" }
        }
        return bad.distinct()
    }

    private val scriptPath = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh|ts)(?![A-Za-z0-9_])")
    private val scriptFile = Regex("(?<![A-Za-z0-9_./-])[a-z0-9_-]+\\.(?:py|mjs|sh)(?![A-Za-z0-9_])")
    private val tickedIdentifier = Regex("`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`")
    private val callBefore = Regex("用|跑|调用|运行|执行|通过|命令|脚本|工具|\\b(?:run|call|invoke|via|command|script|tool)s?\\b", RegexOption.IGNORE_CASE)
    private val callAfter = Regex("^(?:\\(|\\s*的?(?:工具|命令|脚本)|\\s*(?:command|script|tool)s?\\b)", RegexOption.IGNORE_CASE)
    private val called = Regex("(?<![A-Za-z0-9_.`])([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\(")

    @Test fun legacyTableFollowsTheRule() {
        assertEquals(listOf("phone_tap", "web_tap"), legacy.replacements("tap"))
        assertEquals(listOf("phone_launch_app"), legacy.replacements("launch_app"))
        assertEquals(listOf("web_navigate"), legacy.replacements("browser_navigate"))
        assertEquals(listOf("web_wait_for"), legacy.replacements("browser_wait"))
        // 名前は変更されていないため、テーブルにありません。
        assertTrue(legacy.replacements("finish").isEmpty() && legacy.replacements("run_command").isEmpty())
        // それぞれの古い名前は現在のツールと同等であり、同じ古い名前のツールは同じことを行います。ただし、知識とスキルに分けられるものは除きます。
        val split = setOf(setOf(Verb.KnowledgeSearch, Verb.SkillSearch), setOf(Verb.KnowledgeRead, Verb.SkillRead))
        for (old in legacy.names) {
            val now = legacy.replacements(old)
            assertTrue(old, now.isNotEmpty() && now.all { it in known })
            val verbs = now.map { knownTools().verb(it) }.toSet()
            assertTrue(old, verbs.size == 1 || verbs in split)
        }
    }

    @Test fun rewriteChangesOnlyToolMentions() {
        assertEquals("用 `phone_launch_app` 打开,再 `phone_tap` / `web_tap` 按钮;tap 一下", legacy.rewrite("用 `launch_app` 打开,再 `tap` 按钮;tap 一下"))
        assertEquals("先 web_navigate 再 phone_type_text / web_type_text", legacy.rewrite("先 browser_navigate 再 type_text"))
        assertEquals("not_a_tool 与 phone_tap 不动", legacy.rewrite("not_a_tool 与 phone_tap 不动"))
    }
}
