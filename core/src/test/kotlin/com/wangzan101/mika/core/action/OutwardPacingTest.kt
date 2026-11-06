package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.DeclareResult
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 外部アクション間の間隔: 初回は等しくなく、間隔が十分ではなく、他の Web サイトは個別にカウントされます。間隔には上限と下限があり、回数に応じて長くなります。実行エントリはそれに応じて待機し、待機時間は待機として記録されます。 */
class OutwardPacingTest {
    private val limits = OutwardPacing.Limits()

    @Test fun firstActionOnASiteDoesNotWait() {
        val p = OutwardPacing.human(Random(1))
        assertEquals(0, p.waitBefore("example.com", 0))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("other.com", 10))
        assertEquals(0, OutwardPacing.Off.also { it.noteDone("example.com", 0) }.waitBefore("example.com", 1))
    }

    @Test fun gapIsWithinTheFirstRangeAndCountsElapsedTime() {
        repeat(50) { seed ->
            val p = OutwardPacing.human(Random(seed))
            p.noteDone("example.com", 1_000)
            val gap = p.waitBefore("example.com", 1_000)
            assertTrue("$gap", gap in limits.firstGapMs)
            // ページを読んで次のステップを考えるのに費やした時間もインターバルに含まれます。
            assertEquals(gap - 3_000, p.waitBefore("example.com", 4_000))
            assertEquals(0, p.waitBefore("example.com", 1_000 + gap))
        }
    }

    @Test fun gapGrowsWithCountAndNeverPassesTheCap() {
        val p = OutwardPacing.human(Random(3))
        var t = 0L
        for (n in 1..40) {
            p.noteDone("example.com", t)
            val gap = p.waitBefore("example.com", t)
            val lo = (limits.firstGapMs.first + (n - 1) * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
            assertTrue("action $n: $gap", gap in lo..limits.maxGapMs)
            t += gap
        }
    }

    @Test fun oldActionsFallOutOfTheWindow() {
        val p = OutwardPacing.human(Random(5), OutwardPacing.Limits(windowMs = 60_000))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("example.com", 61_000))
    }

    @Test fun sitesAreRegistrableDomainsOnTheWebAndPackagesOnThePhone() {
        assertEquals("example.com", OutwardPacing.siteOf("www.example.com", web = true))
        assertEquals("example.com", OutwardPacing.siteOf("m.example.com", web = true))
        assertEquals("com.chat.app", OutwardPacing.siteOf("com.chat.app", web = false))
        assertEquals(null, OutwardPacing.siteOf(null, web = false))
    }

    /** 1 つの画面: 1 つの「今すぐ通信」ボタンと 1 つの通常のボタン。クリックした内容を書き留めます。 */
    private class GreetPage : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        private val shown = listOf(
            UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false),
            UiElement("@e2", "button", "下一个", 900, 1300, listOf(800, 500, 999, 560), null, true, false, false),
        )
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.jobs", shown, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps += ref }
    }

    // も、単独で実行する場合の判断に従って分離されます。2 回目の「即時通信」までに十分な時間待機します。これは、この呼び出しの待機として記録され、操作時間には含まれません。通常のクリックはカウントされません
    @Test fun executorWaitsBetweenOutwardTapsAndRecordsTheWait() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val screen = GreetPage()
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val pacing = OutwardPacing.human(Random(9))
        val gate = ConfirmationGate()
        fun allowSend() {
            val req = ConfirmationRequest(IrrevAction.submit, "立即沟通", "", null, "com.jobs")
            assertEquals(DeclareResult.Pending, gate.declare(req, "com.jobs"))
            gate.decide(ConfirmChoice.Once, 1)
        }
        val executor = ActionExecutor(tools, OperationGuards(), gate, gate = {}, recorder = ActionRecorder(sink, { currentTime }), autonomous = true, pacing = pacing)
        suspend fun tap(id: String, ref: String) = executor.run(Part.ToolCall(id, "phone_tap", """{"ref":"$ref","snapshotId":"s1"}"""), Origin.Model(1))
        allowSend()
        tap("c1", "@e1")
        val gap = pacing.waitBefore("com.jobs", currentTime)
        assertTrue("$gap", gap in limits.firstGapMs)
        val before = currentTime
        tap("c2", "@e2")
        val spent = currentTime - before
        allowSend()
        tap("c3", "@e1")
        assertEquals(listOf("@e1", "@e2", "@e1"), screen.taps)
        val results = events.filterIsInstance<RunEvent.ToolResult>().associateBy { it.callId }
        assertEquals(0L, results.getValue("c1").waitMs)
        assertEquals(0L, results.getValue("c2").waitMs)
        // 通常のクリック時間もインターバルに含まれ、不足分のみを補います。
        assertEquals(gap - spent, results.getValue("c3").waitMs)
        assertTrue(results.getValue("c3").durationMs < results.getValue("c3").waitMs)
        // アクティビティ上の外部アクションの数をマークします。これを押すと、停止後に指示を与えるときに送信された回数がカウントされます。
        val outward = events.filterIsInstance<RunEvent.Activity>().associate { it.callId to it.outward }
        assertEquals(mapOf("c1" to true, "c2" to false, "c3" to true), outward)
    }
}
