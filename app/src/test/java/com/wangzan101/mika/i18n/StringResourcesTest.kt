package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 日本語専用フォークのリソース検証。
 * 既定（フォールバック）の `values/` も日本語であり、`values-ja/` と
 * 条目・プレースホルダ・複数形が一致する。日本語以外の文字列パックは存在しない。
 */
class StringResourcesTest {
    private val fallback = ResFileTexts.japanese
    private val ja = ResFileTexts.japaneseQualified

    /** 標了 translatable="false" の条目只在默认文件里。 */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun defaultFallbackIsJapaneseAndMatchesJa() {
        // translatable=false は既定にだけあってもよい。それ以外は完全一致する。
        assertEquals(fallback.strings.keys - fixed, ja.strings.keys - fixed)
        assertEquals(fallback.plurals.keys, ja.plurals.keys)
        assertTrue(fallback.strings.size > 300)
        assertEquals("ja", fallback.strings.getValue("ui_locale"))
        assertEquals("ja", ja.strings.getValue("ui_locale"))
    }

    @Test
    fun placeholdersMatch() {
        for (name in ja.strings.keys - fixed - setOf("date_month_day", "date_year_month_day")) {
            assertEquals(name, args(fallback.strings.getValue(name)), args(ja.strings.getValue(name)))
        }
        for (name in ja.plurals.keys) {
            val expected = args(ja.plurals.getValue(name).getValue("other"))
            fallback.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun japanesePluralsOnlyUseOther() {
        for ((name, forms) in ja.plurals) assertEquals(name, setOf("other"), forms.keys)
    }

    @Test
    fun noNonJapaneseStringPacksRemain() {
        val dirs = File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(setOf("values-ja", "values-night"), dirs)
    }

    @Test
    fun localesConfigOnlyOffersJapanese() {
        val text = File("src/main/res/xml/locales_config.xml").readText()
        assertTrue(text.contains("android:name=\"ja\""))
        assertTrue(!text.contains("zh") && !text.contains("\"en\"") && !text.contains("\"ko\""))
    }

    @Test
    fun everyTagResolvesToJapanese() {
        val likely = { _: String -> "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        for (tag in listOf(null, "", "zh-Hans", "zh-Hant", "zh", "en", "en-US", "fr-FR", "ja-JP", "ko-KR", "es-419", "pt-BR", "id-ID", "in-ID")) {
            assertEquals("tag=$tag", AppLanguage.Japanese, of(tag))
        }
        for (l in AppLanguage.entries) assertEquals(l.toString(), AppLanguage.Japanese, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
