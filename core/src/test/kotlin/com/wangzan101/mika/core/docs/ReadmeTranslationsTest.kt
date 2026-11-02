package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 旧言語別URLは残し、本文を複製せず日本語の正本へ案内する。
 * README.zh-CN.mdの生成領域は[ReadmeSectionsTest]が別に検証する。
 */
class ReadmeTranslationsTest {
    private val root = File("..").canonicalFile

    @Test fun legacyEnglishPathsStayAvailableAndPointToJapaneseDocuments() {
        val aliases = File(root, "docs").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".en.md") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals("既存の英語版URLを残し、すべてを案内ページとして検査する", ENGLISH_ALIASES, aliases)
        aliases.sorted().forEach { path ->
            assertAlias(path, File(path).name.removeSuffix(".en.md") + ".md")
        }
    }

    @Test fun legacyReadmePathsPointDirectlyToTheJapaneseRootReadme() {
        README_ALIASES.forEach { path ->
            assertAlias(path, "../../README.md")
            assertEquals(File(root, "README.md").canonicalFile, File(File(root, path).parentFile, "../../README.md").canonicalFile)
        }
    }

    @Test fun legacyGeneratedReadmeAlsoLinksToTheJapaneseRootReadme() {
        val path = "docs/readme/README.zh-CN.md"
        val file = File(root, path)
        assertTrue("生成領域を持つ旧READMEのURLを残す: $path", file.isFile)
        val links = LINK.findAll(file.readText(Charsets.UTF_8)).toList()
        assertTrue("$path から日本語の正本へ直接案内する", links.any { it.groupValues[2] == "../../README.md" })
    }

    @Test fun allLegacyPathsUseJapaneseGuidanceWithoutDuplicatedInstructions() {
        (ENGLISH_ALIASES + README_ALIASES).forEach { path ->
            val text = File(root, path).readText(Charsets.UTF_8)
            val paragraphs = text.trim().split(Regex("""\r?\n\s*\r?\n"""))
            assertEquals("$path は見出しと短い案内文だけにする", 2, paragraphs.size)
            assertTrue("$path の見出しは日本語で案内する", paragraphs.first().startsWith("# ") && "日本語" in paragraphs.first())
            assertTrue("$path の本文は日本語の正本を案内する", "日本語" in paragraphs.last())
            assertFalse("$path に画像や複製した実行手順を置かない", "![" in text || "```" in text)
            assertFalse("$path に旧翻訳署名・生成領域・HTMLを置かない", '<' in text || '>' in text)
            assertFalse("$path から外部の導入先へ案内しない", "://" in text)
        }
    }

    private fun assertAlias(path: String, target: String) {
        val alias = File(root, path)
        assertTrue("互換URLのファイルを残す: $path", alias.isFile)
        val links = LINK.findAll(alias.readText(Charsets.UTF_8)).toList()
        assertEquals("$path の案内リンクは正本への1件だけにする", 1, links.size)
        assertEquals("$path の正本リンク", target, links.single().groupValues[2])
        assertTrue("$path のリンク名も日本語で案内する", "日本語" in links.single().groupValues[1])
        val canonical = File(alias.parentFile, target).canonicalFile
        assertTrue("$path の正本が存在する: $target", canonical.isFile)
        assertTrue("$path の正本はリポジトリ内に置く", canonical.toPath().startsWith(root.toPath()))
        assertFalse("$path が別の英語版を経由しない", canonical.name.endsWith(".en.md"))
        assertFalse("$path が自分自身を参照しない", canonical == alias.canonicalFile)
    }

    companion object {
        private val ENGLISH_ALIASES = setOf(
            "docs/README.en.md",
            "docs/agent-quickstart.en.md",
            "docs/cli.en.md",
            "docs/computer-install.en.md",
            "docs/computer.en.md",
            "docs/dev/README.en.md",
            "docs/dev/knowledge-format.en.md",
            "docs/disclaimer.en.md",
            "docs/faq.en.md",
            "docs/features.en.md",
            "docs/install-with-agent.en.md",
            "docs/mcp.en.md",
            "docs/models.en.md",
            "docs/modes.en.md",
            "docs/setup-guide.en.md",
        )
        private val README_ALIASES = setOf("ja", "zh-Hant", "ko", "es")
            .map { "docs/readme/README.$it.md" }.toSet()
        private val LINK = Regex("""(?<!!)\[([^\]]+)]\(([^)\s]+)\)""")
    }
}
