package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCPや定期実行など承認を得られない実行元で、保存された決済操作を再生してもタスク全体を異常終了させません。承認不可をモデルへ返し、未完了として説明させます。 */
class ReplayerDeniedApprovalTest {
    @Test fun deniedPaymentStepHandsBackToTheModelInsteadOfCrashing() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        // 実行元の provenance を維持し、遠隔実行の支払いを ApprovalPolicy に従って拒否します。
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink),
            provenance = RunProvenance(RunOrigin.Mcp, Attendance.Unattended))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("denied payment step should hand back to the model, got $out", out is ReplayOutcome.Diverged)
        assertTrue("the denied tap must not run", s.taps.isEmpty())
    }

    /** 対照として、その場で操作するUIでは既存の confirmation_required 経路でモデルへ戻ることを確認します。 */
    @Test fun confirmationRequiredStepStillStopsForConfirmation() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("UI replay should stop for confirmation, got $out", out is ReplayOutcome.StoppedForConfirmation)
        assertTrue(s.taps.isEmpty())
    }
}
