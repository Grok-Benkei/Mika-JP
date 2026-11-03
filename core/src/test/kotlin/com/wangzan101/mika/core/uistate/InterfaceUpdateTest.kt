package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceUpdateTest {
    private var port = 0
    /** 同じ名前の要素の位置は変更されません。 */
    private fun shot(names: List<String>, pkg: String = "com.x") = ScreenSnapshot("p${++port}", pkg,
        names.mapIndexed { i, n -> UiElement("@e${i + 1}", "button", n, 500, 100 + 80 * n.hashCode().mod(20), listOf(0, 0, 10, 10), null, true, false, false) }, 1000, 2000)
    private fun JsonObject.full() = this["full"]!!.jsonPrimitive.boolean
    private fun names(n: Int) = (1..n).map { "项$it" }

    @Test fun addedRemovedChangedCounted() {
        val st = RefStabilizer()
        val a = st.next(shot(names(10)))
        // 項目 2 を削除し、新しい項目を追加します。項目 1 の境界が変更されました (位置は変更されず、番号も同じままです)
        val els = shot(names(10).filter { it != "项2" } + "新的").let { s ->
            s.copy(elements = s.elements.map { if (it.name == "项1") it.copy(bounds = listOf(0, 0, 20, 20)) else it })
        }
        val b = st.next(els)
        val d = InterfaceUpdate.between(a, b, baseVisible = true)
        assertFalse(d.full())
        assertEquals("s2", d["snapshotId"]!!.jsonPrimitive.content)
        assertEquals("s1", d["base"]!!.jsonPrimitive.content)
        assertEquals("com.x", d["currentApp"]!!.jsonPrimitive.content)
        assertEquals(listOf("新的"), d["added"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf(a.elements.single { it.name == "项2" }.ref), d["removed"]!!.jsonArray.map { it.jsonObject["ref"]!!.jsonPrimitive.content })
        assertEquals("项2", d["removed"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val changed = d["changed"]!!.jsonArray.single().jsonObject
        assertEquals("项1", changed["name"]!!.jsonPrimitive.content)
        assertNull(changed["was"]) // 名は変更されません
        assertEquals(8, d["unchanged"]!!.jsonPrimitive.int)
        assertNull(d["elements"])
    }

    @Test fun appChangeSendsFull() {
        val st = RefStabilizer()
        val a = st.next(shot(names(10)))
        val b = st.next(shot(names(10), pkg = "com.y"))
        val d = InterfaceUpdate.between(a, b, baseVisible = true)
        assertTrue(d.full()); assertEquals(10, d["elements"]!!.jsonArray.size)
    }

    @Test fun largeDiffSendsFull() {
        val st = RefStabilizer()
        val a = st.next(shot(names(10)))
        val b = st.next(shot(names(4) + (1..6).map { "换了$it" }))
        val d = InterfaceUpdate.between(a, b, baseVisible = true)
        assertTrue(d.full()); assertEquals(10, d["elements"]!!.jsonArray.size)
        assertNull(d["added"])
    }

    @Test fun nullBaseSendsFull() {
        val b = RefStabilizer().next(shot(names(3)))
        val d = InterfaceUpdate.between(null, b, baseVisible = true)
        assertTrue(d.full()); assertNull(d["base"])
        assertEquals("s1", d["snapshotId"]!!.jsonPrimitive.content)
    }

    @Test fun invisibleBaseSendsFull() {
        val st = RefStabilizer()
        val a = st.next(shot(names(10)))
        val b = st.next(shot(names(10)))
        assertTrue(InterfaceUpdate.between(a, b, baseVisible = false).full())
        assertFalse(InterfaceUpdate.between(a, b, baseVisible = true).full())
    }

    @Test fun diffUsesFullListsThenTruncates() {
        val st = RefStabilizer()
        val a = st.next(shot(names(10)))
        // 10 番目のみが変更されました。最初の 2 つの比較を押すと見逃されます。
        val b = st.next(shot(names(10)).let { s -> s.copy(elements = s.elements.map { if (it.name == "项10") it.copy(bounds = listOf(0, 0, 30, 30)) else it }) })
        val d = InterfaceUpdate.between(a, b, baseVisible = true, maxElements = 2)
        assertFalse(d.full())
        assertEquals(listOf("项10"), d["changed"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(9, d["unchanged"]!!.jsonPrimitive.int)
        val f = InterfaceUpdate.between(null, b, baseVisible = true, maxElements = 2)
        assertEquals(2, f["elements"]!!.jsonArray.size); assertTrue(f["truncated"]!!.jsonPrimitive.boolean)
    }
}
