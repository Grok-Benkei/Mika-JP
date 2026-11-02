package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicensesTest {
    /** 単体テストの作業ディレクトリは app/ です。ウェアハウスのルート ディレクトリは上位レベルにあります。 */
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
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("gpl-sources.tar.gz"))
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("PRoot") && Licenses.GPL_SOURCE_OFFER.contains("talloc"))
        assertTrue(byName.getValue("Alpine Linux").note.contains("gpl-sources.tar.gz"))
        assertEquals("0BSD", byName.getValue("XZ for Java").license)
    }

    /** [オープン ソース ライセンス] ページには、組み込みオペレーティング環境のソフトウェア パッケージが 1 つずつリストされ、それぞれにライセンスが付いています。 */
    @Test
    fun runtimePackagesListed() {
        val manifest = File("src/main/assets/runtime/packages.json")
        val entries = Licenses.runtimePackages(manifest.readText())
        assertTrue(entries.size > 40)
        listOf("busybox", "musl", "python3", "nodejs", "py3-pillow", "py3-pypdf").forEach { n -> assertTrue(n, entries.any { it.name == n }) }
        entries.forEach { assertTrue(it.name, it.license.isNotBlank() && it.note.startsWith("バージョン ")) }
    }
}
