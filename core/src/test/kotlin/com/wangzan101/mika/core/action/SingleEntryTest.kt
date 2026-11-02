package com.wangzan101.mika.core.action

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 実行エントリ境界: [com.wangzan101.mika.core.tool.ActionPass] のコンストラクターは内部的なものであり、アプリ モジュールによって作成できません。
 * コアでは core.action のみが作成でき、不可逆判定と承認チェックを調整できるのは core.action のみです。
 * パッケージの依存関係は一方向のみです。core.action と core.guard は、それらをスケジュールする core.agent に依存しません。
 */
class SingleEntryTest {
    private val action = File("src/main/kotlin/com/wangzan101/mika/core/action").canonicalPath + File.separator
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))

    /** ソース コード内のコード行 (コメント行全体はカウントされません。行内の文字列と URL は通常どおりカウントされます)。 */
    private fun codeLines(): List<Triple<File, Int, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f, i + 1, line)
            }
        }.toList()
    }

    private fun outsideAction(f: File) = !f.canonicalPath.startsWith(action)

    private fun offenders(pattern: Regex) = codeLines()
        .filter { (f, _, line) -> outsideAction(f) && pattern.containsMatchIn(line) }
        .map { (f, n, line) -> "${f.path}:$n: ${line.trim()}" }

    @Test fun sourcesAreFound() {
        assertTrue("ソースディレクトリが見つかりません（作業ディレクトリは core/ を使ってください）", roots.first().isDirectory)
        assertTrue(codeLines().any { (f, _, line) -> !outsideAction(f) && "ActionPass()" in line })
    }

    @Test fun onlyTheExecutorHoldsThePass() {
        val bad = offenders(Regex("""\bActionPass\s*\(""")).filterNot { "class ActionPass" in it }
        assertTrue("次の箇所が実行入口を通さずに実行証跡を生成しています:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun onlyTheExecutorDecides() {
        val bad = offenders(Regex(
            """IrreversibleDetector\s*\.\s*(needsCheck|classify|hasLabelAt|unresolved)\b|\.\s*permits\s*\(|\.\s*noteWrite\s*\(|\bIrreversibleDetector\s*[,)]"""
        ))
        assertTrue("次の箇所が実行入口を通さずに直接判定しています:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun actionAndGuardDoNotDependOnTheLoop() {
        val bad = listOf("action", "guard").flatMap { pkg ->
            File("src/main/kotlin/com/wangzan101/mika/core/$pkg").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().filter { it.startsWith("import com.wangzan101.mika.core.agent.") }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("実行入口とガードが主ループへ逆向きに依存しています:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
