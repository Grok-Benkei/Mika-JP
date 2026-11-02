package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private var n = 0
private fun call(name: String, args: JsonObject) = Part.ToolCall("s${++n}", name, args.toString())
private fun tools(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
private fun cmd(command: String) = tools(call("run_command", buildJsonObject { put("command", command) }))
private fun finish() = com.wangzan101.mika.core.testing.answerReply("計算が終わりました")
private fun toolResults(m: FakeModel): List<JsonObject> = m.requests.last().filter { it.role == Role.Tool }
    .flatMap { it.parts.filterIsInstance<Part.ToolResult>() }.map { Json.parseToJsonElement(it.content).jsonObject }

class AgentLoopSandboxTest {
    private val sb = FakeSandbox()
    private fun loop(model: FakeModel, gate: suspend ((TaskState?) -> Unit) -> Unit = {}) =
        AgentLoop(model, toolDispatcher(FakeScreen(), sandbox = sb), "BASE", gate = gate)

    @Test fun sandboxToolsSkipGate() = runTest {
        var gated = 0
        sb.next = { CommandResult(1, false, false, 10, "err", 0, false) }
        // 6 つの異なるコマンドの失敗: 画面のヒューズの失敗 (5 回連続失敗) はカウントされません。
        val m = FakeModel(*(1..6).map { cmd("false $it") }.toTypedArray(),
            tools(call("write_file", buildJsonObject { put("path", "a.txt"); put("content", "1") })), finish())
        val r = loop(m) { gated++ }.run("算一下")
        assertTrue(r.toString(), r is TaskState.Succeeded)
        assertEquals(0, gated)
        assertEquals(6, sb.runs.size)
        assertTrue(toolResults(m).none { it.containsKey("guard") })
    }

    @Test fun fiveIdenticalFailuresStop() = runTest {
        sb.next = { CommandResult(2, false, false, 10, "No such file", 0, false) }
        val m = FakeModel(cmd("python3 x.py"), repeatLast = true)
        val r = loop(m).run("跑脚本")
        assertTrue(r.toString(), r is TaskState.Failed)
        r as TaskState.Failed
        assertTrue(r.reason, r.reason.startsWith("操作を停止しました（sandbox_loop）"))
        assertEquals(FailKind.Guard, r.kind)
        assertEquals(5, sb.runs.size)
        val results = toolResults(m)
        assertTrue(results[1].containsKey("guard").not())
        val guard = results[2]["guard"]!!.jsonArray.single().jsonObject
        assertEquals("sandbox_loop", guard["code"]!!.jsonPrimitive.content)
        assertEquals("warn", guard["severity"]!!.jsonPrimitive.content)
    }

    @Test fun budgetExhausted() = runTest {
        sb.next = { CommandResult(0, false, false, 20 * 60_000L, "ok", 0, false) }
        val m = FakeModel(cmd("a"), cmd("b"), cmd("c"), finish())
        val r = loop(m).run("长任务")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(2, sb.runs.size)
        val last = toolResults(m).last()
        assertEquals("sandbox_budget_exhausted", last["error"]!!.jsonPrimitive.content)
    }

    @Test fun timeoutCappedToRemainingBudget() = runTest {
        sb.next = { CommandResult(0, false, false, 25 * 60_000L, "ok", 0, false) }
        val m = FakeModel(cmd("a"), tools(call("run_command", buildJsonObject { put("command", "b"); put("timeoutSec", 600) })), cmd("c"), finish())
        loop(m).run("长任务")
        assertEquals(60_000L, sb.runs[0].timeoutMs)
        // には 25 分かかりましたが、残りは 5 分のみです
        assertEquals(300_000L, sb.runs[1].timeoutMs)
        // は 50 分後に実行されなくなります
        assertEquals(2, sb.runs.size)
    }
}
