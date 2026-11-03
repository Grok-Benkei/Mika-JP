package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.verify.Criteria
import com.wangzan101.mika.core.verify.Evidence
import com.wangzan101.mika.core.verify.Verdict
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 各モデルによって返される JSON の外観は異なります。許容できる読み取り値ですが、読み取れない場合は、再度質問して、JSON を要求してください。 */
class ModelJsonTest {
    private val verdict = """{"checks": [{"criterion": "电量", "status": "met", "quote": "86%"}], "verdict": "pass", "reason": "屏上有"}"""

    @Test fun readsObjectsInTheShapesModelsReplyIn() {
        val shapes = listOf(
            verdict,
            "```json\n$verdict\n```",
            "好的,核对结果如下:\n$verdict\n以上。",
            // オブジェクトの後に中括弧で囲まれた説明が続きます。最初の { から最後の } までは取得できません。
            "$verdict\n注:引用格式为 {原文}。",
            // の前には、JSON ではない中括弧の段落があります。
            "按 {checks, verdict} 的格式回答:$verdict",
            // 文字列内の括弧はバランスを崩さない
            """{"checks": [{"criterion": "标题含 } 和 {", "status": "met", "quote": "a}b{"}], "verdict": "pass", "reason": "x"}""",
        )
        for (s in shapes) assertTrue(s, Verifier.parse(s)?.verdict == Verdict.Pass)
        assertNull(ModelJson.firstObject("没有 JSON"))
        assertNull(ModelJson.firstObject("{坏的"))
    }

    @Test fun keyVariantsAreAccepted() {
        val o = ModelJson.firstObject("""{"Final_Verdict": "x", "VERDICT": "pass"}""")!!
        assertEquals("pass", o.field("verdict")!!.jsonPrimitive.content)
        val v = Verifier.parse("""{"Checks": [{"Criterion": "电量", "Status": "MET", "Quote": "86%"}], "Verdict": "Pass", "Reason": "ok"}""")!!
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals("met", v.checks.single().status)
    }

    @Test fun verifierRetriesOnceAskingForJsonOnly() = runTest {
        val criteria = Criteria(listOf("电量"), "read", fallback = false)
        val ev = Evidence(com.wangzan101.mika.core.verify.freshOf("当前 App:设置\n电量 86%"), null, com.wangzan101.mika.core.verify.EvidenceLedger(), emptyList(), protected = false)
        fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
        val m = FakeModel(reply("我认为通过了。"), reply(verdict))
        val v = Verifier(m, null).verify("看电量", criteria, "电量 86%\n依据: 「电量 86%」", ev)
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(2, m.requests.size)
        assertEquals(ModelJson.JSON_ONLY, (m.requests[1].last().parts.single() as Part.Text).text)
        // を 2 回読み取ることはできません: 検証に失敗しました、本当のことを言います
        val bad = Verifier(FakeModel(reply("通过"), reply("还是通过")), null).verify("看电量", criteria, "电量 86%\n依据: 「电量 86%」", ev)
        assertEquals(Verdict.CannotVerify, bad.verdict)
        assertEquals("検証モデルの応答を解析できません", bad.reason)
    }
}
