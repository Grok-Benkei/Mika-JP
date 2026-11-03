package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMeterTest {
    private val task = Message(Role.User, listOf(Part.Text("帮我查天气")), Author.User)
    private var seq = 0
    private fun call() = Message(Role.Assistant, listOf(Part.ToolCall("c${++seq}", "phone_tap", """{"target":"按钮"}""")), Author.Mika)
    private fun result(pad: Int = 400) = Message(Role.Tool, listOf(Part.ToolResult("c$seq", """{"ok":true,"pad":"${"x".repeat(pad)}"}""", false)), Author.Mika)

    @Test fun noUsageYetEstimatesMessagesAndOverheadOnce() {
        val h = listOf(task, call(), result())
        // ステップ 1: アンカー ポイントはありません。メッセージ + 固定オーバーヘッドが一緒に推定され、同じ比率が乗算されます。
        assertEquals(UsageMeter.estimate(TokenEstimate.of(h) + 11_000, 1.2), UsageMeter.of(h, 11_000, 1.2))
    }

    @Test fun anchorsOnTheLatestReplyWithUsage() {
        val first = call()
        val r1 = result()
        val second = call()
        val r2 = result()
        val h = listOf(task, first, r1, second, r2)
        val usage = mapOf(first to UsageMeter.Anchor(Usage(10_000, 50), 9_000), second to UsageMeter.Anchor(Usage(12_000, 40), 9_000))
        // 実際の入力 (当時のプロンプト ワードとツールを含む) + 応答の出力 + 後続のメッセージの推定。固定オーバーヘッドは変更されておらず、追加はありません
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)), 1.0), UsageMeter.of(h, 9_000, 1.0, usage::get))
        // 以降、固定オーバーヘッドが変更されました (ツールのセットが拡張されました): 変更された部分のみが追加されました
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)) + 500, 1.0), UsageMeter.of(h, 9_500, 1.0, usage::get))
    }

    @Test fun thinkingTokensAreNotContextGrowth() {
        val reply = call()
        val h = listOf(task, reply)
        // の出力は 3000 で、2900 はサービス プロバイダーによって個別に報告されます。次の入力として 100 のみが送信されます。
        val anchor = UsageMeter.Anchor(Usage(inputTokens = 8_000, outputTokens = 3_000, reasoningTokens = 2_900), 0)
        assertEquals(8_100, UsageMeter.of(h, 0, 1.0) { if (it === reply) anchor else null })
        assertEquals(0, UsageMeter.resent(Usage(1, 10, reasoningTokens = 50)))
    }

    @Test fun anchoredOverheadIsNotCountedTwiceInTheTrigger() {
        // 32K ローカル モデル、プロンプト ワードおよびツール 11K: 実際の使用量にはこれら 11K が含まれます。トリガー ポイントは一度減らされることはなくなり、ステップごとに押されることもなくなります。
        val window = 32_000
        val overhead = 11_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var input = 12_000
        var compactions = 0
        repeat(10) {
            val reply = call()
            h += reply
            h += result(pad = 200)
            val anchor = UsageMeter.Anchor(Usage(input, 30), overhead)
            val size = UsageMeter.of(h, overhead, c.scale) { if (it === reply) anchor else null }
            if (c.compress(h, window, size, fixedOverhead = overhead).foldedSteps > 0) compactions++
            input += 100
        }
        assertEquals(0, compactions)
        // はトリガーポイント (18K) を通過した後に押されます。
        val reply = call()
        h += reply
        h += result()
        val over = UsageMeter.of(h, overhead, c.scale) { if (it === reply) UsageMeter.Anchor(Usage(18_500, 30), overhead) else null }
        assertTrue(over > ContextLimits.of(window).trigger)
        assertTrue(c.compress(h, window, over, fixedOverhead = overhead).messages != h)
    }

    @Test fun firstStepWithHeavyOverheadOnSmallWindowDoesNotCompactEveryStep() {
        // は実際には使用されておらず、固定オーバーヘッドがトリガー ポイントを超えています。一度押すとバック​​ラッシュがあり、各ステップで再度押すことはありません。
        val window = 32_000
        val overhead = 16_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var rewrites = 0
        repeat(30) {
            h += call()
            h += result()
            if (c.compress(h, window, fixedOverhead = overhead).rewritten) rewrites++
        }
        assertTrue("$rewrites", rewrites in 1..3)
    }
}
