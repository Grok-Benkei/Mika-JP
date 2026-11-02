package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** README.md および docs/readme/README.zh-CN.md で生成された段落 (組み込みナレッジ、サービス プロバイダーのデフォルト) はソースと一致しています。矛盾している場合は、`./gradlew updateReadme` を実行します。 */
class ReadmeSectionsTest {
    private val root = File("..").canonicalFile

    @Test fun readmeMatchesGenerated() {
        val readme = File(root, ReadmeSections.README).readText()
        val expected = ReadmeSections.render(readme, ReadmeSections.sections(root, japanese = true))
        assertEquals("README.md の自動生成箇所が古くなっています。実行: ./gradlew updateReadme", expected, readme)
    }

    @Test fun legacyPathReadmeAlsoMatchesJapaneseGeneratedText() {
        val readme = File(root, ReadmeSections.README_ZH).readText()
        val expected = ReadmeSections.render(readme, ReadmeSections.sections(root, english = false, japanese = true))
        assertEquals("${ReadmeSections.README_ZH} の自動生成箇所が古くなっています。実行: ./gradlew updateReadme", expected, readme)
    }

    @Test fun renderReplacesOnlyBetweenMarkers() {
        val text = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        assertEquals(
            "前\n${ReadmeSections.start("x")}\n新\n${ReadmeSections.end("x")}\n后\n",
            ReadmeSections.render(text, mapOf("x" to "新")),
        )
    }

    @Test fun missingMarkerFails() {
        val e = assertThrows(IllegalStateException::class.java) { ReadmeSections.render("没有标记", mapOf("x" to "新")) }
        assertTrue(e.message.orEmpty(), "必要なマーカー" in e.message.orEmpty())
    }

    @Test fun readsPresetsFromSource() {
        val src = """
            ProviderPreset("甲", ProviderConfig.Kind.OpenAiCompat, "https://a.example/v1", caps()),
            ProviderPreset("乙", ProviderConfig.Kind.Gemini, "https://b.example", caps(vision = true)),
            ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
        """.trimIndent()
        assertEquals(
            listOf(
                ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
                ReadmeSections.Preset("乙", "Gemini", "https://b.example"),
                ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
            ),
            ReadmeSections.presets(src),
        )
    }

    /** によってカスタマイズできるインターフェイスは、デフォルトで表示される順序ではなく、最後にリストされる (インターフェイス上の順序と同じ) 応答としても書き込まれます。 */
    @Test fun customKindsListResponsesLast() {
        val presets = listOf(
            ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
            ReadmeSections.Preset("乙", "OpenAiResponses", "https://b.example/v1"),
            ReadmeSections.Preset("丙", "Gemini", "https://c.example"),
            ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
        )
        assertTrue(ReadmeSections.providers(presets).endsWith("甲、乙、丙 などのプロバイダーを内蔵しています。その他は「カスタム」で接続できます。対応API: OpenAI互換、Gemini、OpenAI Responses。"))
        assertEquals(ReadmeSections.providers(presets), ReadmeSections.providersEn(presets))
    }
}
