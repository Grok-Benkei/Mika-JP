package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** パッケージ化された知識とスキルのドキュメントに記載されているスクリプトは、それぞれのディレクトリにある `scripts/manifest.json` のスクリプトと一致します。 */
class BundledScriptsManifestTest {
    /** リソース ルート ディレクトリ (その下にナレッジ/アプリ、ナレッジ/サイト、スキルがあります)。 */
    private val root = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).parentFile.parentFile

    /** ファイルが配置されるエントリ ディレクトリ (`knowledge/sites/zhihu.com`、`skills/research` など)。 */
    private fun entryDir(rel: String): String = rel.split('/').let { p -> p.take(if (p[0] == "knowledge") 3 else 2).joinToString("/") }

    private fun entryFiles(): Sequence<File> = sequenceOf("knowledge/apps", "knowledge/sites", "skills")
        .flatMap { File(root, it).walkTopDown().filter(File::isFile) }
    private val ref = Regex("scripts/[A-Za-z0-9_./-]+\\.(py|sh|js|ts|mjs)")

    /** エントリ ディレクトリ → マニフェスト;マニフェスト ファイルを読み取れない場合、これは失敗します。 */
    private val manifests: Map<String, List<ScriptEntry>> by lazy {
        entryFiles().filter { it.name == "manifest.json" && it.parentFile.name == "scripts" }.associate { f ->
            val dir = f.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath
            val text = f.readText()
            val entries = ScriptManifest.parse(text)
            assertTrue("$dir: manifest is empty or unreadable", entries.isNotEmpty())
            // 解析は安全でないパスを破棄します。ここでの要件はいずれも破棄されません
            assertEquals("$dir: every entry must be a safe relative path", Regex("\"file\"").findAll(text).count(), entries.size)
            dir to entries
        }
    }

    /** エントリ ディレクトリ → ドキュメントに記載されているスクリプト (LICENSE で始まるライセンス ファイルはカウントされません)。 */
    private fun references(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        entryFiles().filter { it.extension == "md" && !it.name.startsWith("LICENSE") }.forEach { f ->
            val dir = entryDir(f.relativeTo(root).invariantSeparatorsPath)
            ref.findAll(f.readText()).forEach { out.getOrPut(dir) { mutableSetOf() } += it.value }
        }
        return out
    }

    @Test fun everyReferencedScriptHasManifestEntry() {
        val refs = references()
        // スクリプトが記述されているディレクトリとマニフェストが配置されているディレクトリは同じバッチ内にあります。
        assertEquals(manifests.keys, refs.keys)
        for ((dir, scripts) in refs) {
            val listed = manifests[dir]?.map { it.file }.orEmpty()
            for (s in scripts) assertTrue("$dir mentions $s but its scripts/manifest.json does not list it", s in listed)
        }
        // 一方: リストにリストされているスクリプトはすべてドキュメントで言及されています
        for ((dir, entries) in manifests) {
            for (e in entries) assertTrue("$dir lists ${e.file} that no document mentions", e.file in refs[dir].orEmpty())
        }
    }

    @Test fun runnableScriptsExist() {
        val runnable = manifests.flatMap { (dir, es) -> es.filter { it.runnable }.map { dir to it } }
        // に含まれるスクリプト ファイル (リストを除くインデックス内の各ディレクトリ scripts/ の下の添付ファイル) は、リスト内で実行可能としてマークされているものとまったく同じです。
        val shipped = GuideCatalog.loadBundled().rows.flatMap { r ->
            r.files.filter { it.startsWith("scripts/") && it != "scripts/manifest.json" }.map { "${KnowledgeIndex.dirOf(r.kind)}/${r.name}" to it }
        }.toSet()
        assertEquals(shipped, runnable.map { (dir, e) -> dir to e.file }.toSet())
        for ((dir, e) in runnable) {
            val f = File(root, "$dir/${e.file}")
            assertTrue("$dir/${e.file} is marked runnable but missing", f.isFile)
            assertNotNull("$dir/${e.file}: no interpreter for this type", ScriptManifest.interpreterFor(e.file))
            val head = f.readLines().take(3).joinToString("\n")
            assertTrue("$dir/${e.file} needs an Apache-2.0 header", "SPDX-License-Identifier: Apache-2.0" in head)
            val script = f.readText()
            assertTrue("$dir/${e.file} に日本語の使い方と--helpが必要です", "使い方:" in script && "\"--help\"" in script)
        }
        // を実行できない理由を教えてください。アカウント認証の統一的な説明が必要
        for ((dir, es) in manifests) for (e in es.filter { !it.runnable }) {
            assertTrue("$dir/${e.file} has no reason", !e.reason.isNullOrBlank())
            if ("auth" in e.needs) assertEquals("$dir/${e.file}", AUTH_REASON, e.reason)
        }
        // スクリプト ディレクトリには、実行可能としてマークされたマニフェストとファイルのみがあります。
        for ((dir, es) in manifests) {
            val files = File(root, "$dir/scripts").listFiles()!!.map { "scripts/${it.name}" }.toSet()
            assertEquals(dir, es.filter { it.runnable }.map { it.file }.toSet() + "scripts/manifest.json", files)
        }
    }

    /** パッケージ化された各スクリプトには、実際に実行するためのテストが含まれています。そのディレクトリ (run_bundled_script のソース) とスクリプト パスが同時にテスト ファイルに表示されます。 */
    @Test fun everyShippedScriptHasATest() {
        val catalog = GuideCatalog.loadBundled()
        val tests = listOf("src/test", "../app/src/test", "../app/src/androidTest").map(::File)
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "BundledScriptsManifestTest.kt" }.toList() }
            .map { it.readText() }
        assertTrue("no test sources found", tests.isNotEmpty())
        val shipped = catalog.runnableScripts()
        assertEquals(shipped, catalog.scriptFiles().keys.filter { !it.endsWith("manifest.json") }.toSet())
        for (path in shipped) {
            val script = "scripts/" + path.substringAfter("/scripts/")
            val skill = path.substringBefore("/scripts/")
            assertTrue("$path has no test that runs it", tests.any { "\"$skill\"" in it && "\"$script\"" in it })
        }
    }

    @Test fun manifestNeedsAreInstalled() {
        for ((dir, es) in manifests) for (e in es) {
            // インターネットに接続されているかどうか: ニーズの net フィールドはネットワーク フィールドと一致しています (run_bundled_script はネットワークによってブロックされています)
            assertEquals("$dir/${e.file}: needs 'net' must match network", "net" in e.needs, e.network)
            if (!e.runnable) continue
            for (n in e.needs) {
                assertTrue("$dir/${e.file}: runnable scripts may only need apk: packages or net, not $n", n == "net" || n.startsWith("apk:"))
                if (n.startsWith("apk:")) assertTrue("$dir/${e.file} needs $n, which SANDBOX_PACKAGES lacks", n.removePrefix("apk:") in SANDBOX_PACKAGES)
            }
        }
    }

    private companion object {
        const val AUTH_REASON = "需要账号授权,Mika 暂不支持"
    }
}
