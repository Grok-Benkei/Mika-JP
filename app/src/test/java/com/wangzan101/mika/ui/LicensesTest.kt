package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicensesTest {
    /** 単体テストの作業ディレクトリ app/ と、リポジトリ直下の両方に対応する。 */
    private val notices = File("../licenses/THIRD_PARTY_NOTICES.md").takeIf { it.isFile } ?: File("licenses/THIRD_PARTY_NOTICES.md")

    @Test
    fun everyNoticeEntryShown() {
        val headings = notices.readLines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
        assertTrue("notices has headings", headings.size >= 16)
        val shown = Licenses.ENTRIES.map { it.name }
        headings.forEach { assertTrue("missing on licenses screen: $it", it in shown) }
        assertEquals("Mika", shown.first())
    }

    @Test
    fun gplSourceOfferPresent() {
        val byName = Licenses.ENTRIES.associateBy { it.name }
        assertEquals("Apache-2.0", byName.getValue("Mika").license)
        assertEquals("GPL-2.0-or-later", byName.getValue("PRoot").license)
        assertEquals("LGPL-3.0-or-later", byName.getValue("talloc").license)
        assertEquals("Apache-2.0", byName.getValue("Apache Commons Compress").license)
        listOf("OkHttp", "Okio", "kotlinx.coroutines", "kotlinx.serialization", "Kotlin標準ライブラリ", "AndroidX（Jetpack Composeを含む）")
            .forEach { assertEquals(it, "Apache-2.0", byName.getValue(it).license) }
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("corresponding-sources.tar.gz"))
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("PRoot") && Licenses.GPL_SOURCE_OFFER.contains("talloc"))
        assertTrue(byName.getValue("Alpine Linux").note.contains("corresponding-sources.tar.gz"))
        assertEquals("0BSD", byName.getValue("XZ for Java").license)
    }

    /** 「ライセンスと著作権表示」に、同梱実行環境の各パッケージとライセンスを表示する。 */
    @Test
    fun runtimePackagesListed() {
        val manifest = File("src/main/assets/runtime/packages.json")
        val entries = Licenses.runtimePackages(manifest.readText())
        assertTrue(entries.size > 40)
        listOf("busybox", "musl", "python3", "nodejs", "py3-pillow", "py3-pypdf").forEach { n -> assertTrue(n, entries.any { it.name == n }) }
        entries.forEach { assertTrue(it.name, it.license.isNotBlank() && it.note.startsWith("バージョン ")) }
    }

    @Test fun bundledOriginalsKeepTheSourceLicenseAndCopyrightBytes() {
        val root = requireNotNull(notices.canonicalFile.parentFile?.parentFile)
        val assets = File("src/main/assets")
        val originals = mapOf(
            "licenses/NOTICE.txt" to File(root, "NOTICE"),
            "licenses/THIRD_PARTY_NOTICES.md" to notices,
        ) + listOf("GPL-2.0.txt", "GPL-3.0.txt", "LGPL-3.0.txt").associate { name ->
            "licenses/$name" to File(root, "licenses/$name")
        }
        originals.forEach { (asset, source) ->
            assertArrayEquals(asset, source.readBytes(), File(assets, asset).readBytes())
        }
        // 既存の同梱原文は、リポジトリの LICENSE にある付録を含まない版。そのバイト列も保持する。
        val apache = File(assets, "licenses/Apache-2.0.txt").readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(apache)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594", digest)
    }

    @Test fun japaneseGuideExplainsItsStatusAndOffersEveryBundledOriginal() {
        assertTrue(Licenses.JAPANESE_GUIDE.contains("日本語の参考要約"))
        assertTrue(Licenses.JAPANESE_GUIDE.contains("正式な翻訳ではありません"))
        assertTrue(Licenses.JAPANESE_GUIDE.contains("英語の原文"))
        assertEquals(Licenses.DOCUMENTS.size, Licenses.DOCUMENTS.map { it.id }.toSet().size)
        val offered = Licenses.DOCUMENTS.map { it.asset }.toSet()
        File("src/main/assets/licenses").listFiles()!!.filter { it.isFile }.forEach { file ->
            assertTrue("原文への入口がありません: ${file.name}", "licenses/${file.name}" in offered)
        }
        Licenses.DOCUMENTS.forEach { document ->
            assertTrue(document.id, document.title.isNotBlank() && document.summary.isNotBlank() && document.source.isNotBlank())
            assertTrue(document.asset, File("src/main/assets", document.asset).isFile)
        }
    }
}
