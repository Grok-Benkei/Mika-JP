package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTest {
    private val secret = "这是回答全文里独有的一句话"

    private fun pair(id: String, outcome: String, passed: Boolean, steps: Int, wallMs: Long, falseSuccess: Boolean = false, cls: FailureClass? = null, marker: String? = null) =
        TaskScore(id, passed, falseSuccess, outcome == "unverified", if (passed) emptyList() else listOf("0:answer_contains"), cls, marker) to
            Observed(outcome, secret, listOf("屏幕文字"), null, emptyMap(), emptyMap(), steps, wallMs, 1_000_000, 200_000, 400_000, null)

    private val scores = listOf(
        pair("a-ok", "succeeded", true, 4, 10_000),
        pair("b-false", "succeeded", false, 6, 20_000, falseSuccess = true, cls = FailureClass.false_success),
        pair("c-unv", "unverified", false, 8, 30_000, cls = FailureClass.unknown),
        pair("d-write", "succeeded", true, 10, 40_000, marker = "Mika评测-r1"),
    )

    @Test fun containsMetricsAndNoAnswers() {
        val md = EvalReport.markdown("r1", "deepseek-flash", scores, listOf("e-xhs" to "未安装 com.xingin.xhs"), null)
        assertFalse(secret in md)
        assertFalse("屏幕文字" in md)
        listOf("r1", "deepseek-flash", "達成率", "2/4", "誤った成功報告の割合", "1/3", "1/4", "未確認の成功", "ステップ数", "所要時間", "トークン",
            "false_success", "a-ok", "b-false", "e-xhs", "未安装 com.xingin.xhs", "Mika评测-r1").forEach {
            assertTrue(it, it in md)
        }
        assertTrue(md, "| 入力トークン | 4000000 |" in md)
    }

    @Test fun perTaskVerdictShown() {
        val (s, o) = pair("v-ok", "succeeded", true, 4, 10_000)
        val (s2, o2) = pair("v-unv", "unverified", false, 4, 10_000)
        val md = EvalReport.markdown("r1", "m", listOf(s to o.copy(verdict = "pass"), s2 to o2.copy(verdict = "cannot_verify")), emptyList(), null)
        assertTrue(md, "検証" in md)
        assertTrue(md, md.lines().any { it.startsWith("| v-ok |") && "pass" in it })
        assertTrue(md, md.lines().any { it.startsWith("| v-unv |") && "cannot_verify" in it })
    }

    @Test fun perTaskTokensAndCacheRatio() {
        val (s, o) = pair("t-a", "succeeded", true, 4, 10_000)
        val (s2, o2) = pair("t-b", "succeeded", true, 4, 10_000)
        val md = EvalReport.markdown("r1", "m", listOf(s to o, s2 to o2.copy(inputTokens = 1000, cachedTokens = 900, outputTokens = 7)), emptyList(), null)
        assertTrue(md, md.lines().any { it.startsWith("| t-a |") && it.endsWith("| 1000000 | 40.0% | 200000 |") })
        assertTrue(md, md.lines().any { it.startsWith("| t-b |") && it.endsWith("| 1000 | 90.0% | 7 |") })
        // 概要: トークンによって重み付けされたヒット率、および各質問のヒット率の中央値
        assertTrue(md, "| キャッシュ利用率（cached/input） | 40.0% |" in md)
        assertTrue(md, "| タスクごとのキャッシュ利用率の中央値 | 65% |" in md)
    }

    @Test fun costOnlyWithPrices() {
        val none = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertFalse("費用" in none)
        val priced = EvalReport.markdown("r1", "m", scores, emptyList(), Prices(inputPerM = 1.0, outputPerM = 2.0, cachedPerM = 0.5))
        // 各質問: キャッシュされていない入力 0.6M×1 + キャッシュされた 0.4M×0.5 + 出力 0.2M×2 = 1.2; 4 つの質問 4.8
        assertTrue("費用" in priced)
        assertTrue(priced, "4.8" in priced)
    }

    @Test fun medianAndP90() {
        assertEquals(5.0, EvalReport.median(listOf(9.0, 1.0, 5.0)), 0.0)
        assertEquals(2.5, EvalReport.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(9.0, EvalReport.p90((1..10).map { it.toDouble() }), 0.0)
        assertEquals(7.0, EvalReport.p90(listOf(7.0)), 0.0)
        assertEquals(10.0, EvalReport.p90((1..11).map { it.toDouble() }.reversed()), 0.0)
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| ステップ数の中央値 | 7 |" in md && "| ステップ数 P90 | 10 |" in md && "| 所要時間の中央値（秒） | 25 |" in md)
    }

    @Test fun totalWallClockShownWhenGiven() {
        val s = Scoring.score(
            EvalTask(id = "t", goal = "g", apps = emptyList(), category = EvalCategory.Read, expect = Expect.Succeeded, checks = emptyList()),
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 60_000, 0, 0, 0, null),
        )
        val o = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 60_000, 0, 0, 0, null)
        assertFalse("合計経過時間" in EvalReport.markdown("r", "m", listOf(s to o), emptyList(), null))
        val md = EvalReport.markdown("r", "m", listOf(s to o), emptyList(), null, totalWallMs = 90_500, parallel = 2)
        assertTrue(md, "| 合計経過時間（秒） | 90.5 |" in md)
        assertTrue(md, "同時実行 2" in md)
    }
}
