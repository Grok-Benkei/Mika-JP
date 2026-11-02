package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportCompareTest {
    private fun s(id: String, passed: Boolean, falseSuccess: Boolean = false) =
        TaskScore(id, passed, falseSuccess, unverified = false, failedChecks = emptyList(), failureClass = null)

    private val base = listOf(s("a", true), s("b", true), s("c", true), s("d", false))

    @Test fun deltasAndGate() {
        val treated = listOf(s("a", true), s("b", true), s("c", false), s("d", false))
        val report = EvalReport.compare(base, treated, mapOf("send-note" to 3, "set-alarm" to 1))
        assertTrue(report.contains("| 達成 | 3/4 | 2/4 | -1 |"))
        assertTrue(report.contains("| 誤った成功報告 | 0/4 | 0/4 | 0 |"))
        assertTrue(report.contains("- send-note:3 回"))
        assertTrue(report.contains("合格:"))
        assertTrue(EvalReport.gate(base, treated).passed)

        // 2 つの質問がありません: 失敗しました
        val worse = listOf(s("a", true), s("b", false), s("c", false), s("d", false))
        val g = EvalReport.gate(base, worse)
        assertFalse(g.passed)
        assertEquals(1, g.reasons.size)
        assertTrue(g.reasons.single().contains("2件少なく"))

        // 誤って成功した質問があります: 失敗し、質問番号が表示されます
        val lying = listOf(s("a", true), s("b", true), s("c", true), s("d", false, falseSuccess = true))
        val g2 = EvalReport.gate(base, lying)
        assertFalse(g2.passed)
        assertTrue(g2.reasons.single().contains("d"))
        assertTrue(EvalReport.compare(base, lying, emptyMap()).contains("不合格:"))
    }

    @Test fun mediansWhenObserved() {
        fun o(steps: Int, ms: Long) = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), steps, ms, 0, 0, 0, null)
        val r = EvalReport.compare(base, base, emptyMap(), listOf(o(10, 60_000), o(20, 80_000)), listOf(o(8, 50_000), o(12, 70_000)))
        assertTrue(r.contains("| ステップ数の中央値 | 15 | 10 | -5 |"))
        assertTrue(r.contains("| 所要時間の中央値（秒） | 70 | 60 | -10 |"))
        assertTrue(r.contains("学習したスキルは使用されませんでした。"))
    }

    @Test fun tokensPairedByTaskId() {
        fun o(input: Long, cached: Long, output: Long) =
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 1, input, output, cached, null)
        // ベースライン a b c d、スキルがあれば、1 ラウンドで c しか実行できませんでした。a と c よりも優れているだけです。
        val treated = listOf(s("c", true), s("a", true))
        val r = EvalReport.compare(base, treated, emptyMap(),
            listOf(o(1000, 200, 50), o(99_999, 0, 9_999), o(3000, 600, 70), o(99_999, 0, 9_999)), listOf(o(2400, 1800, 60), o(800, 600, 40)))
        assertTrue(r, r.contains("| 入力トークン（対応する2件の中央値） | 2000 | 1600 | -400 |"))
        assertTrue(r, r.contains("| 出力トークン（対応する2件の中央値） | 60 | 50 | -10 |"))
        assertTrue(r, r.contains("| キャッシュ利用率 %（対応する2件の中央値） | 20 | 75 | +55 |"))
    }
}
