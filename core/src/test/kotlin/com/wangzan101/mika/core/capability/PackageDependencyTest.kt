package com.wangzan101.mika.core.capability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 依存関係の方向: 機能 → コア。コアは機能を参照せず、機能は相互に参照しません。アセンブリ層のみが複数の機能を同時に認識します。
 * ツール エンジン (コア/ツール) はインターフェイス状態 (コア/uistate) を参照しません。インターフェイス状態はインターフェイス機能に属し、エンジンはそれを SurfaceLayer を通じてのみ使用します。
 * アプリの cap/ の下にある機能ディレクトリも相互参照しません (コア内の対応する機能パッケージを除く)。ソースコード内のインポート名と完全な名前に従って検索します。コメントの行全体はカウントされません。
 */
class PackageDependencyTest {
    private val core = File("src/main/kotlin/com/wangzan101/mika/core")
    private val app = File("../app/src/main/java/com/wangzan101/mika")
    private val ref = Regex("""com\.wangzan101\.mika\.(core\.)?(cap\.([a-z0-9_]+)|assembly|uistate)\b""")

    private class Use(val file: File, val line: Int, val text: String, val target: String)

    /** [root] のソース コードで参照される機能パッケージとアセンブリ レイヤー ([Use.target] は cap.<capability> または core.assembly)。 */
    private fun uses(root: File): List<Use> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
        f.readLines().asSequence().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("package ")) null else Triple(i, line, t)
        }.flatMap { (i, line, _) ->
            ref.findAll(line).map { m -> Use(f, i + 1, line.trim(), (m.groupValues[1] + m.groupValues[2]).removeSuffix(".")) }
        }.toList()
    }.toList()

    /** [f] が配置されている機能ディレクトリ名。 cap/ の下にない場合は null です。 */
    private fun capOf(root: File, f: File): String? {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        return if (rel.startsWith("cap/")) rel.removePrefix("cap/").substringBefore('/') else null
    }

    private fun report(bad: List<Use>, root: File) = bad.joinToString("\n") { "${it.file.relativeTo(root)}:${it.line}: ${it.text}" }

    @Test fun sourcesAreFound() {
        assertTrue("ソースディレクトリが見つかりません（作業ディレクトリは core/ を使ってください）", core.isDirectory && File(core, "cap").isDirectory)
    }

    @Test fun coreDoesNotKnowCapabilities() {
        val bad = uses(core).filter { u ->
            val rel = u.file.relativeTo(core).invariantSeparatorsPath
            !rel.startsWith("cap/") && !rel.startsWith("assembly/") && (u.target.startsWith("core.cap.") || u.target == "core.assembly")
        }
        assertTrue("コアが機能層または組み立て層を参照しています:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun toolEngineDoesNotKnowInterfaceState() {
        val bad = uses(core).filter { u -> u.file.relativeTo(core).invariantSeparatorsPath.startsWith("tool/") && u.target == "core.uistate" }
        assertTrue("ツールエンジンが画面状態を参照しています:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun capabilitiesDoNotKnowEachOther() {
        val bad = uses(core).filter { u ->
            val own = capOf(core, u.file) ?: return@filter false
            u.target == "core.assembly" || (u.target.startsWith("core.cap.") && u.target != "core.cap.$own")
        }
        assertTrue("機能間で相互参照しているか、組み立て層を参照しています:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun appCapabilitiesDoNotKnowEachOther() {
        if (!File(app, "cap").isDirectory) return
        val bad = uses(app).filter { u ->
            val own = capOf(app, u.file) ?: return@filter false
            val other = u.target.removePrefix("core.").removePrefix("cap.")
            u.target.contains("cap.") && other != own
        }
        assertTrue("App内の機能ディレクトリが相互参照しています:\n" + report(bad, app), bad.isEmpty())
    }
}
