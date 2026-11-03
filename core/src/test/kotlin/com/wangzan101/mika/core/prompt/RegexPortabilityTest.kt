package com.wangzan101.mika.core.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android の通常のエンジンは、コンピューター上では Java よりも厳密です。エスケープされていない `{` および `}` ({n}、{n,m} 回ではありません) はコンピューター上でコンパイルできます。
 * は、電話機にロードされるとすぐにエラーをスローします。ここでは、ソース コード内のすべてのハードコーディングされた通常のリテラルをスキャンし、コンピューター上でこのカテゴリをブロックします。
 */
class RegexPortabilityTest {
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))
    // は、呼び出し括弧の直後に続くリテラルのみを調べます。三重引用符はそのまま残り、Kotlin エスケープによって通常の文字列が復元されます。
    private val call = Regex("""(?:Regex|Pattern\.compile)\(\s*(\"\"\"[\s\S]*?\"\"\"|"(?:[^"\\]|\\.)*")""")
    private val toRegex = Regex("""("(?:[^"\\]|\\.)*")\.toRegex\(""")

    @Test
    fun literalPatternsHaveNoBareBraces() {
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@mapIndexedNotNull null
                    (call.findAll(line) + toRegex.findAll(line)).map { pattern(it.groupValues[1]) }
                        .firstOrNull { hasBareBrace(it) }
                        ?.let { "${f.path}:${i + 1}: $it" }
                }.toList()
            }
        }
        assertTrue("正規表現に未エスケープの波括弧があり、Androidではコンパイルに失敗します:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun detectorCatchesTheClass() {
        assertTrue(hasBareBrace("""\{\{([a-z0-9_]+)}}"""))
        assertTrue(hasBareBrace("""\{\d+}"""))
        assertTrue(!hasBareBrace("""\{\{([a-z0-9_]+)\}\}"""))
        assertTrue(!hasBareBrace("""\p{L}{1,8}\s{2}[{}]"""))
    }

    private fun pattern(lit: String) =
        if (lit.startsWith("\"\"\"")) lit.removeSurrounding("\"\"\"")
        else lit.removeSurrounding("\"").replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** エスケープされた中括弧、\p{…}、[…] 文字グループ、{n}/{n,}/{n,m} 回などの属性を削除した後、中括弧はそのままにしておきます。 */
    private fun hasBareBrace(pattern: String): Boolean {
        var p = pattern.replace(Regex("""\\[pP]\{[A-Za-z_=]+\}"""), "")
        p = p.replace(Regex("""\\[{}\\]"""), "")
        p = p.replace(Regex("""\[(?:\\.|[^\]])*]"""), "")
        p = p.replace(Regex("""\{\d+(,\d*)?}"""), "")
        return '{' in p || '}' in p
    }
}
