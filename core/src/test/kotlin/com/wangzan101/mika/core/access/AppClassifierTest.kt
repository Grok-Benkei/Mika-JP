package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppClassifierTest {
    @Test fun ignoresInventedPackagesAndUnknownCategories() {
        val parsed = AppClassifier.parse("""[{"package":"bank","category":"Finance","reason":"银行"},{"package":"invented","category":"Game"},{"package":"other","category":"invalid"}]""", setOf("bank", "other"))
        assertEquals(setOf("bank", "other"), parsed.keys)
        assertEquals(AppCategory.Finance, parsed["bank"]?.category)
        assertEquals("银行", parsed["bank"]?.reason)
        assertEquals(AppCategory.Unknown, parsed["other"]?.category)
    }

    @Test fun malformedReplyDoesNotProducePermissions() {
        assertTrue(AppClassifier.parse("not json", setOf("bank")).isEmpty())
        assertNull(AppAccessState(classifications = mapOf("bank" to AppClassification(AppCategory.Finance))).denial("bank"))
    }

    /** 旧来の言語引数にかかわらず、日本語の理由と固定の分類値を要求する。アプリの名前とパッケージは入力データとして渡す。 */
    @Test fun reasonLanguageIsSentWithTheApps() = runTest {
        val model = RecordingModel("""[{"package":"bank","category":"Finance","reason":"銀行の取引を行うアプリです。"}]""")
        val parsed = AppClassifier(model, language = "English").classify(listOf("bank" to "Bank App"))
        assertEquals(AppCategory.Finance, parsed["bank"]?.category)
        assertEquals("銀行の取引を行うアプリです。", parsed["bank"]?.reason)
        assertTrue("出力言語が日本語でない: ${model.user}", model.user.contains("\"language\":\"日本語\""))
        assertTrue("アプリの入力が欠けている: ${model.user}", model.user.contains("\"package\":\"bank\"") && model.user.contains("\"label\":\"Bank App\""))
        assertTrue("分類規則が欠けている", model.system.contains("Android アプリを、その主な用途で分類する"))
        assertTrue("理由の日本語固定が欠けている", model.system.contains("理由は常に日本語で書く"))
        assertTrue("分類の列挙値を翻訳しない", model.system.contains("Finance, Social, Game, Shopping, Productivity, Media, Other, Unknown"))
    }

    /** 言語引数や外国語のアプリ名で、日本語の出力方針や分類を切り替えない。 */
    @Test fun legacyLanguageDoesNotChangeJapaneseReasonsOrClassification() = runTest {
        val reply = """[{"package":"bank","category":"Finance","reason":"銀行の取引を行うアプリです。"}]"""
        val zhModel = RecordingModel(reply)
        val enModel = RecordingModel(reply)
        val zh = AppClassifier(zhModel, language = "Simplified Chinese").classify(listOf("bank" to "银行"))
        val en = AppClassifier(enModel, language = "English").classify(listOf("bank" to "Bank"))
        assertEquals(zh, en)
        assertEquals(AppCategory.Finance, en["bank"]?.category)
        for (model in listOf(zhModel, enModel)) assertTrue(model.user, "\"language\":\"日本語\"" in model.user)
    }

    @Test fun foreignReasonIsReplacedHonestlyWithoutChangingCategoryOrPermissions() = runTest {
        val reply = """[{"package":"social","category":"Social","reason":"This app is used to exchange messages with other people."}]"""
        val parsed = AppClassifier(RecordingModel(reply), language = "English").classify(listOf("social" to "Messenger"))
        assertEquals(setOf("social"), parsed.keys)
        assertEquals(AppCategory.Social, parsed["social"]?.category)
        assertEquals("モデルから日本語の分類理由が得られませんでした。分類は参考情報です。", parsed["social"]?.reason)
        assertNull(AppAccessState(classifications = parsed).denial("social"))
    }

    private class RecordingModel(private val reply: String) : ChatModel {
        var system: String = ""
        var user: String = ""
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            this.system = system
            user = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("") { it.text }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }
}
