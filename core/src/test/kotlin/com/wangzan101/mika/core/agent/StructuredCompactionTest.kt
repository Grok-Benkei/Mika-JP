package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class StructuredCompactionTest {
    private var seq = 0
    private val head = listOf(
        Message(Role.User, listOf(Part.Text("帮我查上海的天气")), Author.User),
        Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "记忆", "1. 喜欢摄氏度"))), Author.Mika),
    )

    private fun round(name: String, args: String = "{}", result: String = """{"ok":true,"changed":true}""", said: String? = null): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOfNotNull(said?.let { Part.Text(it) }, Part.ToolCall(id, name, args)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, result, isError = false)), Author.Mika),
        )
    }

    private fun taps(n: Int) = (1..n).flatMap { round("phone_tap", """{"target":"按钮$it"}""") }
    private fun summaries(ms: List<Message>) = ms.mapNotNull { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }

    @Test fun fourSectionsFromResults() {
        val h = head +
            round("phone_launch_app", """{"package":"com.weather"}""", """{"ok":true,"currentApp":"com.weather"}""", said = "先打开天气 App") +
            round("web_navigate", """{"url":"https://wttr.in/"}""", """{"ok":true,"url":"https://wttr.in/Shanghai"}""") +
            round("phone_tap", """{"target":"刷新"}""", """{"ok":false,"error":"ref_expired"}""", said = "刷新一下再读") +
            taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single()
        assertEquals(
            listOf(
                ContextCompressor.HEADER,
                ContextCompressor.SUMMARY_REMINDER,
                "Goal: 帮我查上海的天气",
                "Confirmed facts:",
                "- apps visited: com.weather",
                "- pages opened: https://wttr.in/Shanghai",
                "Progress:",
                "- Step 1 phone_launch_app(com.weather) → applied",
                "- Step 2 web_navigate → applied",
                "- Step 3 phone_tap(刷新) → ref_expired",
            ) + (4..14).map { "- Step $it phone_tap(按钮${it - 3}) → applied" } +
                "Next: continue from the most recent steps kept below (last stated plan before folding: 刷新一下再读)",
            s.lines(),
        )
    }

    @Test fun oneSummaryAcrossPinnedRoundsAndPairsIntact() {
        val takeover = round("hand_to_user", """{"reason":"login"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(3) + takeover + taps(2) + taps(12)
        val out = ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages
        // 最初の段落の代わりに概要が表示されます。引き継ぎの往復は後方にそのまま残ります
        assertEquals(1, summaries(out).size)
        assertTrue(out[2].plainText().startsWith(ContextCompressor.HEADER))
        assertEquals(takeover, out.subList(3, 5))
        val calls = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
        val results = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
        assertEquals(calls, results)
    }

    @Test fun progressRollsOlderStepsIntoCounts() {
        val h = head + (1..20).flatMap { i ->
            if (i % 5 == 0) round("phone_tap", """{"target":"坏$i"}""", """{"ok":false,"error":"ref_expired"}""") else round("phone_tap", """{"target":"好$i"}""")
        } + taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single().lines()
        // コンテキストは非常に小さく、最新の往復のみが残ります。31 ステップ、最後の 15 ステップがステップごとに、前の 16 ステップが一緒にカウントされます。
        assertTrue(s.toString(), "- 16 earlier steps: applied 13, ref_expired 3" in s)
        assertEquals(15, s.count { it.startsWith("- Step ") })
        assertTrue("- Step 17 phone_tap(好17) → applied" in s && s.none { it.startsWith("- Step 16") })
    }

    @Test fun plainSummaryWhenOff() {
        val h = head + taps(15)
        val s = summaries(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages).single()
        assertTrue("Goal: " !in s && s.lines()[1] == ContextCompressor.SUMMARY_REMINDER && s.lines()[2].startsWith("Step 1"))
    }

    @Test fun calibratedEstimateDecidesWhenToCompact() {
        val h = head + taps(20)
        val context = windowFor(uncalibrated(h))
        val c = ContextCompressor(TOOLS)
        assertEquals(h, c.compress(h, context).messages)
        // サービス プロバイダーは、概算の 2 倍の量を報告しました。同じ履歴が、調整された推定に従ってトリガー ポイントを超え、1 回圧縮されました。
        c.calibrate(1000, 2000)
        assertTrue(c.compress(h, context).foldedSteps > 0)
    }

    @Test fun compactionIsStickyForTheTask() {
        val all = head + taps(20)
        val context = windowFor(uncalibrated(all))
        val c = ContextCompressor(TOOLS)
        assertTrue(c.compress(all, context, fixedOverhead = 100).foldedSteps > 0)
        // はトリガー ポイント未満に戻り、圧縮された結果を引き続き使用します。新しいインスタンス (新しいタスク) は以前と同様に開始されます
        assertTrue(c.compress(all, context).foldedSteps > 0)
        assertEquals(0, ContextCompressor(TOOLS).compress(all, context).foldedSteps)
        c.forget()
        assertEquals(0, c.compress(all, context).foldedSteps)
    }

    @Test fun userTextInFoldedRoundsIsKept() {
        val said = Message(Role.User, listOf(Part.Text("顺便把亮度也调低"), Part.Image("PPPP")), Author.User)
        val h = head + round("phone_tap", """{"target":"设置"}""") + said + taps(14)
        for (structured in listOf(false, true)) {
            val out = ContextCompressor(TOOLS,structured = structured).compress(h, contextTokens = 100).messages
            assertTrue(summaries(out).isNotEmpty() && said in out)
        }
    }

    @Test fun summaryStaysBounded() {
        val long = "x".repeat(5000)
        val h = head + (1..200).flatMap { i ->
            round("tool_$i$long", """{"target":"$long"}""", """{"ok":false,"error":"$long$i","url":"https://e.com/$i$long","currentApp":"app$i"}""", said = long)
        } + taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single()
        assertTrue("${s.length}", s.length < 4000)
        assertTrue(long !in s)
        val plain = summaries(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages).single()
        assertTrue(plain.lines().drop(2).all { it.length < 120 })
    }

    @Test fun loopUsesStructuredSummaryWhenFlagOn() = runTest {
        val m = fakeModel("main", contextTokens = 100) {
            for (i in 1..14) call("wait", "seconds" to 1 + i / 100.0)
            answer("待機が完了しました")
        }
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", flags = SpeedFlags(structuredCompaction = true)).run("等一会儿")
        val s = summaries(m.requests.last().messages).single()
        assertTrue(s, "Goal: 等一会儿" in s && "Progress:" in s && "Next: " in s)
    }

    @Test fun calibratedRatioIsSmoothedAndBounded() {
        assertEquals(2.0, TokenEstimate.calibrated(null, 1000, 2000), 1e-9)
        assertEquals(1.0 * 0.7 + 2.0 * 0.3, TokenEstimate.calibrated(1.0, 1000, 2000), 1e-9)
        assertEquals(TokenEstimate.MAX_SCALE, TokenEstimate.calibrated(null, 10, 10_000), 1e-9)
        assertEquals(TokenEstimate.MIN_SCALE, TokenEstimate.calibrated(null, 10_000, 10), 1e-9)
        assertEquals(1.3, TokenEstimate.calibrated(1.3, 0, 500), 1e-9)
    }
}
