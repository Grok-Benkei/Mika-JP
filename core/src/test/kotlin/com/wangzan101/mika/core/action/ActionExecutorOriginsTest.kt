package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.wangzan101.mika.core.assembly.toolDispatcher

/** には、1 つの画面上にボタン (名前はユースケースによって付けられます) と通常のボタンが 1 つだけあります。実際にクリックした内容を書き留めます。 */
private class OneButton(label: String) : ScreenPort by FakeScreen() {
    val taps = mutableListOf<String>()
    private val shown = listOf(
        UiElement("@e1", "button", label, 900, 2300, listOf(800, 940, 999, 999), null, true, false, false),
        UiElement("@e2", "button", "返回", 50, 50, listOf(0, 0, 100, 40), null, true, false, false),
    )
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.chat", shown, 1080, 2400)
    override suspend fun peek() = snapshot()
    override suspend fun tapRef(ref: String, snapshotId: String) = com.wangzan101.mika.core.uistate.ActionResult.Ok(changed = true).also { taps += ref }
}

/**
 * お金を使うという同じアクションは、どのパスから来たとしても (モデルの単一ステップ、一度に複数のステップ、ページ スクリプト、エクスペリエンス再生)、実行の入り口では同じ結論が得られます。
 * も実行されません。実行レコード内の確認イベントにも同じことが当てはまります。費用がかからないアクション (送信、削除、送信、および通常のクリック) は通常どおり実行され、レコード形状にはソース フィールドが欠落しているだけです。
 */
@RunWith(Parameterized::class)
class ActionExecutorOriginsTest(private val label: String, private val action: IrrevAction, private val origin: Origin) {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }

    private suspend fun setup(): Pair<OneButton, ActionExecutor> {
        val screen = OneButton(label)
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        return screen to ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))
    }

    @Test fun paymentIsRefusedTheSameWay() = runTest {
        val (screen, executor) = setup()
        val out = executor.run(Part.ToolCall("c1", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
        out as ActionOutcome.Refused
        assertEquals("confirmation_required", out.code)
        assertEquals(action, out.hit?.action)
        assertEquals(label, out.hit?.matched)
        assertTrue("操作は実行されていないはずです", screen.taps.isEmpty())
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>().map { it.copy(atMs = 0) }
        assertEquals(listOf(RunEvent.Confirmation(0, action.name, label, null, null, "required")), confirmations)
        // 実行されなかったアクションは実行結果として記録されません。モデルのシングルステップインターセプト記述自体がモデルに与えられた結果であり、通常どおり 1 つのレコードが記録されます。
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(results.all { it.changed == null && it.callId == "c1" && "confirmation_required" in it.resultJson })
        assertEquals(if (origin.policy.recordsRefusal) 1 else 0, results.size)
    }

    @Test fun ordinaryActionRunsAndIsRecordedTheSameWay() = runTest {
        val (screen, executor) = setup()
        val out = executor.run(Part.ToolCall("c2", "phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""), origin)
        assertTrue("$out", out is ActionOutcome.Ran)
        assertEquals(listOf("@e2"), screen.taps)
        val r = events.filterIsInstance<RunEvent.ToolResult>().single()
        assertEquals(listOf(origin.step, "phone_tap", "c2", origin.policy.source, true), listOf(r.step, r.tool, r.callId, r.source, r.changed))
        assertTrue(events.none { it is RunEvent.Confirmation })
    }

    @Test fun outwardActionsThatSpendNoMoneyRunWithoutAsking() = runTest {
        for (free in listOf("发送", "删除", "提交")) {
            val screen = OneButton(free)
            val tools = toolDispatcher(screen)
            tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
            val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))
            val out = executor.run(Part.ToolCall("c4", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
            assertTrue("$free $out", out is ActionOutcome.Refused && out.code == "confirmation_required")
            assertTrue(screen.taps.isEmpty())
        }
        assertTrue(events.any { it is RunEvent.Confirmation })
    }

    @Test fun autonomousActionRunsWithoutApproval() = runTest {
        val screen = OneButton(label)
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink), autonomous = true)
        val out = executor.run(Part.ToolCall("c3", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
        assertTrue("$label $origin $out", out is ActionOutcome.Refused)
        assertTrue(screen.taps.isEmpty())
    }

    companion object {
        private val ORIGINS = listOf(Origin.Model(3), Origin.Batch(3), Origin.Script(3), Origin.Replay)
        private val ACTIONS = listOf("确认支付" to IrrevAction.pay, "提交订单" to IrrevAction.order)

        @JvmStatic
        @Parameterized.Parameters(name = "{0} via {2}")
        fun cases(): List<Array<Any>> = ACTIONS.flatMap { (l, a) -> ORIGINS.map { arrayOf(l, a, it) } }
    }
}
