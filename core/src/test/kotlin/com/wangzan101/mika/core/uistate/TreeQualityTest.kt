package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeQualityTest {
    private fun el(i: Int, name: String, bounds: List<Int>, clickable: Boolean = false) =
        UiElement("@e$i", "TextView", name, 0, 0, bounds, null, clickable, false, false)

    /** 名前付き要素の列を縦に、それぞれの高さが 60 メートルになるように配置します。 */
    private fun rows(n: Int, top: Int = 100) = (0 until n).map { i -> el(i + 1, "第${i + 1}行", listOf(50, top + i * 60, 950, top + i * 60 + 50), clickable = true) }

    private fun shot(elements: List<UiElement>, nodes: List<UiTreeNode> = emptyList(), protected: Boolean = false, unreadable: String? = null) =
        ScreenSnapshot("p1", "com.example", elements, 1080, 2400, protected = protected, unreadableApp = unreadable,
            windows = if (nodes.isEmpty()) emptyList() else listOf(UiWindowTree(1, "application", 1, null, "com.example", true, true, nodes)))

    private fun line(text: String, top: Int, confidence: Float? = null) = VisibleTextLine(text, listOf(100, top, 600, top + 40), confidence)

    // ---- 木々を見てください ----

    @Test fun protectedEmptyAndUnreadableTreesAreClassifiedWithoutOcr() {
        assertEquals(TreeCheck(TreeQuality.Protected), TreeAdequacy.ofTree(shot(rows(10), protected = true)))
        assertEquals(TreeCheck(TreeQuality.Unusable, "empty_elements"), TreeAdequacy.ofTree(shot(emptyList())))
        assertEquals(TreeCheck(TreeQuality.Unusable, "unreadable_window"), TreeAdequacy.ofTree(shot(emptyList(), unreadable = "com.example")))
        // ウィンドウもアプリもありません: 空白の画面、追加するものはありません
        assertEquals(TreeQuality.Adequate, TreeAdequacy.ofTree(ScreenSnapshot("p", null, emptyList(), 1080, 2400, hasWindow = false)).quality)
    }

    @Test fun unlabeledControlsArePartialOrUnusable() {
        val blank = (1..4).map { el(it, "", listOf(0, it * 100, 100, it * 100 + 50), clickable = true) }
        assertEquals(TreeCheck(TreeQuality.Unusable, "unlabeled_controls"), TreeAdequacy.ofTree(shot(blank)))
        assertEquals(TreeCheck(TreeQuality.Partial, "unlabeled_controls"), TreeAdequacy.ofTree(shot(blank + el(9, "标题", listOf(0, 0, 999, 80)))))
        // には、名前のないスクロール可能なコンテナが 1 つだけあります。ツリーには単語がありません。
        val list = UiElement("@e1", "RecyclerView", "", 0, 0, listOf(0, 0, 999, 999), null, false, false, true)
        assertEquals(TreeCheck(TreeQuality.Unusable, "no_text"), TreeAdequacy.ofTree(shot(listOf(list))))
    }

    @Test fun richTreeIsAdequateButSparseTreeNeedsOcr() {
        assertEquals(TreeCheck(TreeQuality.Adequate), TreeAdequacy.ofTree(shot(rows(8))))
        assertEquals(TreeCheck(null), TreeAdequacy.ofTree(shot(rows(3))))
    }

    @Test fun largeTextlessAreaNeedsOcrUnlessItOnlyBacksNamedElements() {
        val named = rows(8)
        val textNodes = named.map { UiTreeNode("TextView", it.bounds, text = it.name) }
        // 画面の下半分に文字のない大きなキャンバスがあり、名前の付いた要素がすべてその上にあります。
        val canvas = UiTreeNode("View", listOf(0, 600, 999, 999))
        assertEquals(TreeCheck(null), TreeAdequacy.ofTree(shot(named, textNodes + canvas)))
        // 画面全体の下に配置される背景画像。死角を除いて、名前を持つすべての要素がその中に含まれます。
        val backdrop = UiTreeNode("ImageView", listOf(0, 0, 999, 999))
        assertEquals(TreeCheck(TreeQuality.Adequate), TreeAdequacy.ofTree(shot(named, listOf(backdrop) + textNodes)))
    }

    // ---- 言葉を読んだ後 ----

    @Test fun ocrTextOutsideTheTreeDecidesPartialOrAdequate() {
        val unsure = TreeCheck(null)
        assertEquals(TreeCheck(TreeQuality.Partial, "text_outside_tree"), TreeAdequacy.withText(unsure, listOf(line("甲", 100), line("乙", 200), line("丙", 300))))
        assertEquals(TreeCheck(TreeQuality.Adequate), TreeAdequacy.withText(unsure, listOf(line("甲", 100))))
        // 単語を読みませんでした: まだわからないので、treeQuality とは書かないでください
        assertEquals(unsure, TreeAdequacy.withText(unsure, null))
        // ツリーは決定されており、変更されません。
        val empty = TreeCheck(TreeQuality.Unusable, "empty_elements")
        assertEquals(empty, TreeAdequacy.withText(empty, emptyList()))
    }

    @Test fun legibleDropsMisreadLines() {
        val lines = listOf(
            line("张三", 100), line("明天见", 150), line("12:30", 200), line("Tom", 250), line("OK", 300), line("¥89", 350),
            line("HA3)", 400), line("A", 450), line("・・・", 500), line("(1)", 550), line("5G", 600), line("模糊的字", 650, confidence = 0.2f),
        )
        assertEquals(listOf("张三", "明天见", "12:30", "Tom", "OK", "¥89"), VisibleText.legible(lines).map { it.text })
        // 画面は主にスペイン語で、短い一連の写真が表示されます
        assertEquals(listOf("HA3)", "5G", "Settings"), VisibleText.legible(listOf(line("HA3)", 100), line("5G", 200), line("Settings", 300))).map { it.text })
    }

    // ---- 画面読み取り結果 ----

    private class OcrScreen(var elements: List<UiElement>, val lines: List<VisibleTextLine>) : ScreenPort by FakeScreen() {
        var ocrReads = 0
        var shots = 0
        override suspend fun snapshot() = ScreenSnapshot("p1", "com.example", elements, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun readVisibleText(): List<VisibleTextLine> { ocrReads++; return lines }
        override suspend fun screenshotPng(): ByteArray { shots++; return JvmTestImageOps.encode(108, 240) { _, _ -> 128 } }
    }

    private val chat = listOf(
        VisibleTextLine("张三", listOf(200, 100, 400, 140)),
        VisibleTextLine("明天见", listOf(200, 150, 500, 190)),
        VisibleTextLine("李四", listOf(200, 250, 400, 290)),
        VisibleTextLine("HA3)", listOf(20, 100, 120, 190)),
    )

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun unusableTreeForTextOnlyModelGetsFilteredOcrWithCentersAndHint() = runTest {
        val s = OcrScreen(emptyList(), chat)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals("unusable", r.content.s("treeQuality"))
        assertEquals(Feedback["tree_poor"], r.content.s("hint"))
        val text = r.content["visibleText"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("张三", "明天见", "李四"), text.map { it.s("text") })
        assertEquals(listOf(300, 120), text.first()["center"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertNull(r.image)
    }

    @Test fun visionToolsAddFindVisuallyAdvice() = runTest {
        val r = toolDispatcher(OcrScreen(emptyList(), chat), vision = VisionService(says(), JvmTestImageOps))
            .executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        val hint = r.content.s("hint")!!
        assertTrue(hint, hint.startsWith(Feedback["tree_poor"]) && "phone_find_visually" in hint)
    }

    @Test fun adequateTreeSkipsOcr() = runTest {
        val s = OcrScreen(rows(8), chat)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(0, s.ocrReads)
        assertFalse("visibleText" in r.content)
        assertFalse("treeQuality" in r.content)
        assertFalse("hint" in r.content)
    }

    @Test fun seeingModelGetsScreenshotForUnusableTreeAndStillTheText() = runTest {
        val s = OcrScreen(emptyList(), chat)
        val r = toolDispatcher(s, visionEnabled = true, adaptiveVision = true).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals("empty_elements", r.content.s("imageReason"))
        assertEquals("unusable", r.content.s("treeQuality"))
        assertTrue(r.image != null)
        assertEquals(3, r.content["visibleText"]!!.jsonArray.size)
    }

    @Test fun elementListAndFreshLookReadTextOnDemandWhenTreeIsPoor() = runTest {
        val s = OcrScreen(emptyList(), chat)
        val d = toolDispatcher(s)
        // Phone_collect は list_elements を画面ごとに読み取ります: ツリーが十分でない場合でも、画面上のテキストは含まれます
        val listed = d.executeRead(Part.ToolCall("l", "phone_list_elements", "{}"))
        assertEquals(3, listed.content["visibleText"]!!.jsonArray.size)
        // 確認する前に画面上のテキストをもう一度読んでください
        val fresh = d.evidence().fresh.joinToString("\n") { it.text }
        assertTrue(fresh, "明天见" in fresh)
        // ツリーがいっぱいになると文字が読めなくなります
        s.elements = rows(8)
        val before = s.ocrReads
        d.evidence()
        assertEquals(before, s.ocrReads)
    }
}
