package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.classify
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.interfaces

/** は、画面が読み取られるたびに次の要素リストに変更されます。各画面のポート番号は @e1 から始まり、snapshotId は p1、p2... になります。同じ名前の要素の位置は変更されません。 ポートの 2 番目の画面の */
private class ScriptedScreen(private vararg val screens: List<String>) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var reads = 0
    var result: ActionResult = ActionResult.Ok()
    private fun build(i: Int) = ScreenSnapshot("p${i + 1}", "com.x", screens[minOf(i, screens.size - 1)].mapIndexed { n, name ->
        UiElement("@e${n + 1}", "button", name, 500, 100 + 200 * name.hashCode().mod(10), listOf(0, 0, 10, 10), null, true, false, false)
    }, 1080, 2400)
    override suspend fun snapshot(): ScreenSnapshot = build(reads++)
    override suspend fun peek(): ScreenSnapshot = build(maxOf(0, reads - 1))
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $snapshotId"; return result }
    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult { calls += "clear $ref $snapshotId"; return result }
}

class ToolDispatcherStableRefTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.refOf(name: String) =
        this["elements"]!!.jsonArray.map { it.jsonObject }.single { it.s("name") == name }.s("ref")

    @Test fun observeShowsModelRefsAndSnapshotIds() = runTest {
        val s = ScriptedScreen(listOf("搜索", "返回"), listOf("新的", "搜索", "返回"))
        val d = toolDispatcher(s, stableRefs = true)
        val a = d.execute(call("phone_observe")).content
        val b = d.execute(call("phone_list_elements")).content
        assertEquals("p1", a.s("snapshotId")); assertEquals("p2", b.s("snapshotId"))
        assertFalse(a.toString().contains("搜索"))
        assertFalse(b.toString().contains("新的"))
        val aRefs = a["elements"]!!.jsonArray.map { it.jsonObject.s("ref") }
        val bRefs = b["elements"]!!.jsonArray.map { it.jsonObject.s("ref") }
        assertTrue(aRefs.all { it in bRefs })
        assertEquals(aRefs.size + 1, bRefs.size)
    }

    @Test fun tapTranslatesToPortRef() = runTest {
        val s = ScriptedScreen(listOf("搜索", "返回"), listOf("新的", "搜索", "返回"))
        val d = toolDispatcher(s, stableRefs = true)
        d.execute(call("phone_observe")); d.execute(call("phone_observe"))
        val r = d.execute(call("phone_tap", """{"ref":"@e2","snapshotId":"p2"}"""))
        // モデルの @e2 (リターン) は @e3 です
        assertEquals(listOf("tapRef @e3 p2"), s.calls)
        assertEquals("true", r.content.s("ok"))
        assertNull(r.content["relocated"])
        assertNull(r.content["portRef"]) // ポート番号がモデルに付与されていません
        assertEquals("@e3", r.record.s("portRef"))
        assertEquals("p2", r.record.s("portSnapshotId"))
        d.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"p2","text":"x"}"""))
        d.execute(call("phone_clear_text", """{"ref":"@e1","snapshotId":"p2"}"""))
        assertEquals(listOf("tapRef @e3 p2", "type @e2 p2", "clear @e2 p2"), s.calls)
    }

    @Test fun olderSnapshotStillCurrentIsRelocated() = runTest {
        val s = ScriptedScreen(listOf("搜索", "返回"), listOf("新的", "搜索", "返回"))
        val d = toolDispatcher(s, stableRefs = true)
        d.execute(call("phone_observe")); d.execute(call("phone_observe"))
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""))
        assertEquals(listOf("tapRef @e2 p2"), s.calls)
        assertEquals("true", r.content.s("relocated"))
    }

    @Test fun staleRefReturnsStaleError() = runTest {
        val s = ScriptedScreen(listOf("搜索", "返回"), listOf("新的"))
        val d = toolDispatcher(s, stableRefs = true)
        d.execute(call("phone_observe")); d.execute(call("phone_observe"))
        for (args in listOf("""{"ref":"@e1","snapshotId":"p1"}""")) {
            val r = d.execute(call("phone_tap", args))
            assertEquals("ref_expired", r.content.s("error"))
            assertTrue(r.content.s("message"), "状態を再度読み、新しい ref と snapshotId を使う" in r.content.s("message"))
        }
        assertEquals("ref_expired", d.execute(call("phone_type_text", """{"ref":"@e2","snapshotId":"p1","text":"x"}""")).content.s("error"))
        assertTrue(s.calls.isEmpty())
    }

    @Test fun gateSeesModelRefsInTheirSnapshot() = runTest {
        val s = ScriptedScreen(listOf("发送", "返回"), listOf("新的", "发送", "返回"))
        val d = toolDispatcher(s, stableRefs = true)
        d.execute(call("phone_observe")); d.execute(call("phone_observe"))
        val args = Json.parseToJsonElement("""{"ref":"@e1","snapshotId":"p2"}""").jsonObject
        val snap = d.interfaces.actionTarget(d.declared("phone_tap"), args).aimed
        assertNotNull(snap); assertEquals("p2", snap!!.snapshotId)
        assertEquals("发送", snap.elements.single { it.ref == "@e1" }.name)
        assertNotNull(IrreversibleDetector.classify("phone_tap", args, snap, d.interfaces.peek()))
        // 古い snapshotId ですが、番号は最新の画面にあります: 最新の画面に従って判断します (実際のクリックは最新の画面です)
        val old = Json.parseToJsonElement("""{"ref":"@e1","snapshotId":"p1"}""").jsonObject
        assertTrue(d.interfaces.actionTarget(d.declared("phone_tap"), old).aimed!!.elements.any { it.name == "新的" })
        assertNotNull(IrreversibleDetector.classify("phone_tap", old, d.interfaces.actionTarget(d.declared("phone_tap"), old).aimed, d.interfaces.peek()))
    }

    @Test fun flagOffPassesRefsThrough() = runTest {
        val s = ScriptedScreen(listOf("搜索"))
        val d = toolDispatcher(s)
        assertEquals("p1", d.execute(call("phone_observe")).content.s("snapshotId"))
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""))
        assertEquals(listOf("tapRef @e1 p1"), s.calls)
        assertTrue(r.record.isEmpty())
    }

    @Test fun runRecordKeepsBothRefs() {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
            override fun media(mediaId: Int, png: ByteArray) {}
        }
        fun reply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
        val m = FakeModel(
            reply(Part.ToolCall("a", "phone_observe", "{}")),
            reply(Part.ToolCall("b", "phone_observe", "{}")),
            reply(Part.ToolCall("c", "phone_tap", """{"ref":"@e2","snapshotId":"p2"}""")),
            com.wangzan101.mika.core.testing.answerReply("完了しました"),
        )
        val s = ScriptedScreen(listOf("搜索", "返回"), listOf("新的", "搜索", "返回"))
        kotlinx.coroutines.runBlocking {
            AgentLoop(m, toolDispatcher(s, stableRefs = true), "BASE", sink = sink).run("看看")
        }
        val tap = events.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_tap" }
        val rec = Json.parseToJsonElement(tap.resultJson).jsonObject
        assertEquals("@e3", rec.s("portRef")); assertEquals("p2", rec.s("portSnapshotId"))
        assertFalse(m.requests.any { msgs -> msgs.any { msg -> msg.parts.any { it is Part.ToolResult && "portRef" in it.content } } })
    }

    @Test fun gateBlockedResultRecordsPortRef() {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
            override fun media(mediaId: Int, png: ByteArray) {}
        }
        fun reply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
        val m = FakeModel(
            reply(Part.ToolCall("a", "phone_observe", "{}")),
            reply(Part.ToolCall("b", "phone_observe", "{}")),
            reply(Part.ToolCall("c", "phone_tap", """{"ref":"@e1","snapshotId":"p2"}""")),
            com.wangzan101.mika.core.testing.answerReply("送信していません"),
        )
        val s = ScriptedScreen(listOf("提交订单", "返回"), listOf("新的", "提交订单", "返回"))
        kotlinx.coroutines.runBlocking {
            AgentLoop(m, toolDispatcher(s, stableRefs = true), "BASE", sink = sink,
                confirmations = com.wangzan101.mika.core.gate.ConfirmationGate()).run("看看")
        }
        assertTrue(s.calls.isEmpty())
        val tap = events.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_tap" }
        val rec = Json.parseToJsonElement(tap.resultJson).jsonObject
        assertEquals("@e2", rec.s("portRef")); assertEquals("p2", rec.s("portSnapshotId"))
    }
}
