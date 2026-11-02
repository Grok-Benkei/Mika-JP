package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedVerificationsTest {
    private var t = 0L
    private fun reply(step: Int, tool: String?, answer: String = "a$step") =
        RunEvent.ModelReply(++t, step, "", tool, if (tool == "finish") """{"answer":"$answer"}""" else "{}", null, null, null)
    private fun result(step: Int, tool: String) = RunEvent.ToolResult(++t, step, tool, """{"ok":true}""", null, null)
    private fun verifier(step: Int? = null) = RunEvent.Verifier(++t, "fail", "{}", step)

    // 通常のステップ: 検証は終了応答の前に記録されます。最終回：以降に収録されます。 1 つのレコード内に 2 つの注文が混在している場合でも、それぞれに独自の場所があります。
    @Test fun legacyRecordsPairEitherOrder() {
        val ev = listOf(
            reply(1, "phone_observe"), result(1, "phone_observe"),
            verifier(), reply(2, "finish"), result(2, "finish"),
            reply(3, "phone_observe"), result(3, "phone_observe"),
            reply(4, "finish"), result(4, "finish"), verifier(),
        )
        assertEquals(listOf(2 to 3, 9 to 7), RecordedVerifications.pairs(ev))
    }

    // 2 つのフィニッシュが隣り合っています。最初にチェックしてから返信を書き留めたフィニッシュは、前のフィニッシュには割り当てられません。
    @Test fun backToBackFinishesDoNotSwap() {
        val ev = listOf(verifier(), reply(1, "finish"), result(1, "finish"), verifier(), reply(2, "finish"), result(2, "finish"))
        assertEquals(listOf(0 to 1, 3 to 4), RecordedVerifications.pairs(ev))
    }

    @Test fun stepPairsRegardlessOfPosition() {
        // ステップあり: 最終ラウンドの検証 (返信後) に続いて他の終了が行われ、ペアリングが正しくなります。
        val ev = listOf(reply(1, "finish"), result(1, "finish"), verifier(1), reply(2, "finish"), verifier(2))
        assertEquals(listOf(2 to 0, 4 to 3), RecordedVerifications.pairs(ev))
    }

    @Test fun evidenceExcludesTheCheckedFinishItself() {
        val ev = listOf(reply(1, "phone_observe"), result(1, "phone_observe"), reply(2, "finish"), result(2, "finish"), verifier(2))
        val (v, f) = RecordedVerifications.pairs(ev).single()
        assertEquals(listOf(ev[0], ev[1]), RecordedVerifications.before(ev, v, f))
    }
}
