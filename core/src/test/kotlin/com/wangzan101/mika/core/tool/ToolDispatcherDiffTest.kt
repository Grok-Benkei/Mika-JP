package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.agent.HistoryPruner
import com.wangzan101.mika.core.agent.visibleReads
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

/** は、画面が読み取られるたびに次の要素リストに変更されます (最後の要素を読み取った後も同じままです)。同じ名前の要素の位置は変更されません。 */
private class ListScreen(private vararg val screens: List<String>) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var protectedPage = false
    var changing = false
    var shots = 0
    override suspend fun screenshotPng(): ByteArray { shots++; return byteArrayOf(9, 9) }
    var reads = 0
    var result: ActionResult = ActionResult.Ok(changed = true)
    private fun build(i: Int) = ScreenSnapshot("p${i + 1}", "com.x", screens[minOf(i, screens.size - 1)].mapIndexed { n, name ->
        UiElement("@e${n + 1}", "button", name, 500, 100 + 150 * (name.hashCode().mod(12)), listOf(0, 0, 10, 10), null, true, true, false)
    }, 1080, 2400, protected = protectedPage, changedDuringRead = changing)
    override suspend fun snapshot(): ScreenSnapshot = build(reads++)
    override suspend fun peek(): ScreenSnapshot = build(maxOf(0, reads - 1))
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun back(): ActionResult { calls += "back"; return result }
    override suspend fun waitStable(timeoutMs: Long): Boolean { calls += "waitStable $timeoutMs"; return true }
}

class ToolDispatcherDiffTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val ten = (1..10).map { "项$it" }

    @Test fun phoneObserveDoesNotCutOffElementsAfterTwoHundred() = runTest {
        val s = ListScreen((1..240).map { "项目$it" })
        val o = toolDispatcher(s).execute(call("phone_observe")).content
        assertEquals(240, o["elements"]!!.jsonArray.size)
        assertFalse(o["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun tapResultCarriesScreenDiff() = runTest {
        val s = ListScreen(ten, ten.filter { it != "项3" } + "新的")
        val d = toolDispatcher(s, inlineScreen = true)
        val o = d.execute(call("phone_observe")).content
        assertEquals("p1", o.s("snapshotId"))
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"p1"}""")).content
        assertEquals(listOf("tapRef @e1 p1", "waitStable 1500"), s.calls)
        val screen = r["screen"]!!.jsonObject
        assertFalse(screen["full"]!!.jsonPrimitive.boolean)
        assertEquals("p2", screen.s("snapshotId")); assertEquals("p1", screen.s("base"))
        val added = screen["added"]!!.jsonArray.single().jsonObject
        assertEquals("button", added.s("role"))
        assertFalse("name" in added)
        assertFalse(screen.toString().contains("新的"))
        val removed = screen["removed"]!!.jsonArray.single().jsonObject
        assertEquals("@e3", removed.s("ref")); assertEquals("button", removed.s("role"))
        assertFalse(screen.toString().contains("项3"))
        // 次のアクションの番号は、画面内のこの画面から取得されます
        d.execute(call("phone_tap", """{"ref":"@e11","snapshotId":"p2"}"""))
        assertEquals("tapRef @e10 p2", s.calls[2])
    }

    @Test fun failedActionHasNoScreen() = runTest {
        val s = ListScreen(ten).apply { result = ActionResult.Failed("no_app_open") }
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        val r = d.execute(call("phone_back")).content
        assertEquals("no_app_open", r.s("error")); assertNull(r["screen"])
        assertEquals(1, s.reads)
        // 画面読み取りツール（画面なし）
        assertNull(d.execute(call("phone_list_elements")).content["screen"])
    }

    @Test fun sendsFullWhenBaseNotVisible() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        val a = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(a["full"]!!.jsonPrimitive.boolean)
        // モデルの表示読み取り画面には前の画面がありません (s2): 全数量を送信
        d.setVisibleObservations(setOf("p1"))
        val b = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertTrue(b["full"]!!.jsonPrimitive.boolean)
        assertEquals(10, b["elements"]!!.jsonArray.size)
        // は前の画面 (s3、フルサイズ) で確認できます。別の違いが見つかりました
        d.setVisibleObservations(setOf("p3"))
        val c = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(c["full"]!!.jsonPrimitive.boolean)
        assertEquals("p3", c.s("base"))
    }

    @Test fun sendsFullWhenLastFullScreenNotVisible() = runTest {
        // の違いは、最新の全画面を読み取ることによってのみ理解できます。切り取られる (または折りたたまれる) と、前の画面がまだ存在している場合でも、全画面が読み取られます。
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        d.execute(call("phone_back"))
        d.setVisibleObservations(setOf("p2"))
        assertTrue(d.execute(call("phone_back")).content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
    }

    @Test fun flagOffHasNoScreen() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s)
        d.execute(call("phone_observe"))
        assertNull(d.execute(call("phone_back")).content["screen"])
        // ポートはすでにアクションでインターフェイスを待機しています (報告されていますが変更されていません): 効果を待機しなくなり、効果を判断するときに別のアクションが実行されます。
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun inlineScreenIsRecallableAndRecorded() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, visionEnabled = true, recordFrames = true, inlineScreen = true)
        d.execute(call("phone_observe"))
        val r = d.execute(call("phone_back"))
        val id = r.content["screen"]!!.jsonObject.s("screenshotId")
        assertEquals("2", id)
        assertNull(r.frame); assertNull(r.image); assertTrue(r.imageIds.isEmpty())
        assertEquals(10, r.screenElements!!.size)
        assertFalse(r.screenElements.toString().contains("项"))
        val back = d.execute(call("view_past_screens", """{"ids":[$id]}"""))
        assertEquals("true", back.content.s("ok")); assertTrue(back.images.isEmpty())
    }

    @Test fun inlineScreenFollowsProtectedAndPaymentRules() = runTest {
        val p = ListScreen(ten).apply { protectedPage = true }
        val d = toolDispatcher(p, visionEnabled = true, recordFrames = true, inlineScreen = true)
        val r = d.execute(call("phone_back"))
        assertNull(r.frame); assertEquals(0, p.shots)
        assertEquals("true", r.content["screen"]!!.jsonObject.s("protected"))
        // 支払いページはモデルによって読み取って確認できます
        val pay = ListScreen(listOf("确认支付", "¥89.00"))
        val v = toolDispatcher(pay, visionEnabled = true, recordFrames = true, inlineScreen = true)
        val b = v.execute(call("phone_back"))
        assertNull(b.frame)
        assertFalse(v.keepsRecallImages())
        assertEquals("text_withheld", b.content["screen"]!!.jsonObject.s("reason"))
        assertFalse("确认支付" in b.content.toString())
    }

    @Test fun noFrameWithoutRecordingOrVision() = runTest {
        val s = ListScreen(ten)
        val r = toolDispatcher(s, inlineScreen = true).execute(call("phone_back"))
        assertNull(r.frame); assertEquals(0, s.shots)
        assertTrue(r.content["screen"]!!.jsonObject.containsKey("screenshotId"))
    }

    @Test fun sendsFullUnlessWholeChainVisible() = runTest {
        // 実際の切断: 切断ラインが切断される前の差分 d1 と d2、ライン前の最も近い全量 c1 は保持され、差分チェーンは切断されます。次回は全額を送金する必要があります
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        val h = mutableListOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika))
        suspend fun step(id: String, name: String, args: String = "{}"): JsonObject {
            val c = Part.ToolCall(id, name, args)
            val r = d.execute(c).content
            h += Message(Role.Assistant, listOf(c), Author.Mika); h += Message(Role.Tool, listOf(Part.ToolResult(id, r.toString(), false)), Author.Mika)
            return r
        }
        step("c1", "phone_observe")
        (1..2).forEach { step("d$it", "phone_back") }
        // スクリーンなしの往復はカットラインを d2 に押し込みます
        (1..4).forEach { step("w$it", "wait", """{"seconds":0.$it}""") }
        val visible = visibleReads(HistoryPruner.prune(h, TOOLS, HistoryPruner.Cut(2)))
        assertTrue("$visible", "p1" in visible && "p2" !in visible && "p3" !in visible)
        d.setVisibleObservations(visible)
        assertTrue(step("d3", "phone_back")["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
        d.setVisibleObservations(visibleReads(HistoryPruner.prune(h, TOOLS, HistoryPruner.Cut(2))))
        assertFalse(step("d4", "phone_back")["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
    }

    @Test fun dumpElementsIsDiffBase() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_list_elements"))
        val screen = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(screen["full"]!!.jsonPrimitive.boolean); assertEquals("p1", screen.s("base"))
    }

    @Test fun protectedScreenHidesElementsButPaymentScreenShowsThem() = runTest {
        val p = ListScreen(ten)
        val d = toolDispatcher(p, inlineScreen = true)
        d.execute(call("phone_observe"))
        p.protectedPage = true
        val a = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("true", a.s("protected")); assertTrue("hand_to_user" in a.s("hint"))
        assertNull(a["elements"]); assertNull(a["added"])
        p.protectedPage = false
        // を実行し、フルボリュームから再起動します
        assertTrue(d.execute(call("phone_back")).content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
        val pay = toolDispatcher(ListScreen(listOf("确认支付", "¥89.00")), inlineScreen = true)
        val b = pay.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("text_withheld", b.s("reason"))
        assertTrue(b["elements"]!!.jsonArray.isNotEmpty())
        assertTrue(b["elements"]!!.jsonArray.all { "name" !in it.jsonObject })
        assertFalse("确认支付" in b.toString())
        assertFalse("¥89.00" in b.toString())
    }

    @Test fun changingScreenCarriesNote() = runTest {
        val s = ListScreen(ten).apply { changing = true }
        val screen = toolDispatcher(s, inlineScreen = true).execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("true", screen.s("screenChangedDuringRead"))
        assertTrue(screen.s("note"), "読んでいる間に状態が変化" in screen.s("note") && "頼りにする前に再度確認する" in screen.s("note"))
    }

    @Test fun evidenceSkipsRepeatedReads() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, vision = VisionService(says(), JvmTestImageOps), inlineScreen = true)
        d.execute(call("phone_observe"))
        repeat(5) { d.execute(call("phone_back")) }
        val reads = d.evidence().read(ObsKind.Read, ObsKind.Reply)
        // 同じ画面を 5 回読み取り、本文は出さず、理由は 1 回だけ覚える
        assertTrue(reads.none { "项1" in it })
        assertEquals(1, reads.count { "text_withheld" in it })
    }

    @Test fun fullScreenAfterFiveDiffs() = runTest {
        val d = toolDispatcher(ListScreen(ten), inlineScreen = true)
        d.execute(call("phone_observe"))
        fun full(r: ToolExecution) = r.content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean
        repeat(5) { assertFalse(full(d.execute(call("phone_back")))) }
        assertTrue(full(d.execute(call("phone_back"))))
        assertFalse(full(d.execute(call("phone_back"))))
    }
}
