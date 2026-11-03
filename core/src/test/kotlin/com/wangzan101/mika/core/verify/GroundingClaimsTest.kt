package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答内の特定の事実のみをチェックします。回答自体の説明 (カウント、ランキング、ステップ数) および参照番号はチェック対象の値としてカウントされません。捏造された値は引き続きブロックされます。 */
class GroundingClaimsTest {
    @Test fun japaneseAndLegacyEvidenceHeadingsKeepMetadataOutOfBodyClaims() {
        for (heading in listOf("根拠", "出典", "依据")) {
            val answer = "結果は「確認済み」です。\n**$heading：** 参照日時 2026年11月3日 14:05、参照番号 999。"
            val result = Grounding.check("結果を教えて", answer, listOf("確認済み"))
            assertEquals(heading, true, result.found)
            assertEquals(listOf(Grounding.Claim(Grounding.Kind.Quote, "確認済み")), result.claims)
            assertTrue(result.unmatched.isEmpty())

            val wrongBody = Grounding.check("結果を教えて", answer.replace("結果は", "値は 777、結果は"), listOf("確認済み"))
            assertEquals(heading, listOf("777"), wrongBody.unmatched.map { it.text })
        }
    }

    @Test fun quotedEvidenceStillNeedsSupportWithJapaneseHeadings() {
        for (heading in listOf("根拠", "出典")) {
            val result = Grounding.check("結果を教えて", "結果です。\n$heading:「未表示の語句」", listOf("確認済み"))
            assertEquals(false, result.found)
            assertEquals(listOf(Grounding.Claim(Grounding.Kind.Quote, "未表示の語句")), result.unmatched)
        }
    }

    @Test fun japaneseEvidenceWordsInsideQuotedLabelsRemainBodyClaims() {
        for (heading in listOf("根拠", "出典")) {
            val label = "$heading: 未確認777"
            val answer = "ラベルは「$label」です。"
            val result = Grounding.check("ラベルを教えて", answer, listOf("確認済み"))
            assertEquals(listOf(label), result.claims.filter { it.kind == Grounding.Kind.Quote }.map { it.text })
            assertEquals(false, result.found)
            assertEquals(true, Grounding.check("ラベルを教えて", answer, listOf(label)).found)
        }
    }

    private fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val criteria = Criteria(listOf("回答给出热榜第一的问题和热度"), "read", fallback = false)
    private val pass = """{"checks": [{"criterion": "回答给出热榜第一的问题和热度", "status": "met", "quote": "1561 万热度"}], "verdict": "pass", "reason": "屏上有"}"""
    private val hot = "当前 App:com.example.qa\n热榜\n为什么秋天的天空特别蓝\n1561 万热度\n城市里还能看到银河吗\n982 万热度\n" +
        "长期熬夜有哪些代价\n655 万热度\n猫为什么喜欢纸箱\n431 万热度\n第一份工作该怎么选\n307 万热度"
    private fun screen(fresh: String = hot, shot: ByteArray? = null) =
        Evidence(freshOf(fresh), shot, EvidenceLedger(), listOf("第1步 phone_launch_app(com.example.qa) 生效"), protected = false)

    // 実測値：基準内の「目に見える1～5のランキング」の1と5を画面上の値とみなします。画面上には「1561万熱」としか表示されておらず、正解は検証不能と判定される。
    @Test fun narrativeCountsAndRanksInBasisAreNotRequired() = runTest {
        val answer = "热榜前五:\n1. 为什么秋天的天空特别蓝(1561万热度)\n2. 城市里还能看到银河吗\n3. 长期熬夜有哪些代价\n4. 猫为什么喜欢纸箱\n5. 第一份工作该怎么选\n" +
            "依据: 热榜页可见排名从1到5的问题列表,共5条,第1条热度1561万"
        assertEquals(true, Grounding.found("热榜前五是什么", answer, listOf(hot)))
        assertEquals(Verdict.Pass, Verifier(FakeModel(reply(pass)), null).verify("热榜前五是什么", criteria, answer, screen()).verdict)
        // 結論の小さなカウントやステップ数も数値ではありません。
        assertEquals(true, Grounding.found("热榜第一是什么", "第3步看到热榜,共 5 条,第一是「为什么秋天的天空特别蓝」\n依据: 热榜页", listOf(hot)))
    }

    // 実測値：\bは「@e46表示」で漢字境界を認識せず、46をチェック対象の数値とみなす
    @Test fun citationRefsGluedToChineseAreStripped() {
        val styles = listOf(
            "热榜第一是「为什么秋天的天空特别蓝」,1561万热度\n依据: @e46显示「为什么秋天的天空特别蓝」,@e47显示1561 万热度",
            "热榜第一是「为什么秋天的天空特别蓝」@e46,热度1561万@e47。\n依据: 热榜页(@e46)显示该标题",
            "热榜第一是「为什么秋天的天空特别蓝」[@e46、@e47]\n依据:@v2里写着「1561 万热度」",
        )
        for (a in styles) assertEquals(a, true, Grounding.found("热榜第一是什么", a, listOf(hot)))
        assertEquals(" 显示", Grounding.stripRefs("@e46显示"))
    }

    @Test fun madeUpNumberWithUnitIsCaught() = runTest {
        val answer = "热榜第一是「为什么秋天的天空特别蓝」,热度 1800 万\n依据: 热榜页第1条"
        val g = Grounding.check("热榜第一是什么", answer, listOf(hot))
        assertEquals(false, g.found)
        assertEquals(listOf("1800万"), g.unmatched.map { it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify("热榜第一是什么", criteria, answer, screen())
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.NOT_IN_READS, v.reason)
        assertEquals(listOf("1800万"), v.notFound)
        // スクリーンショットを見て、私はそれを見逃しませんでした。数字は言葉で書くことができますが、言葉で書かれていない場合、数字はそこにありません。
        val seen = Verifier(FakeModel(reply(pass)), FakeModel(reply(pass))).verify("热榜第一是什么", criteria, answer, screen(shot = byteArrayOf(1)))
        assertEquals(Verdict.CannotVerify, seen.verdict)
        // の価格とパーセンテージは同じです。
        assertEquals(false, Grounding.found("多少钱", "现价 ¥39\n依据: 商品页", listOf("商品页\n现价 ¥49")))
        assertEquals(false, Grounding.found("好评率", "好评率 97%\n依据: 商品页", listOf("商品页\n好评率 96%")))
    }

    @Test fun madeUpQuotedTitleIsCaught() = runTest {
        val answer = "热榜第一是「秋天为什么容易犯困」\n依据: 热榜页第1条「秋天为什么容易犯困」"
        assertEquals(false, Grounding.found("热榜第一是什么", answer, listOf(hot)))
        val v = Verifier(FakeModel(reply(pass)), null).verify("热榜第一是什么", criteria, answer, screen())
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(listOf("秋天为什么容易犯困"), v.notFound)
        // 引用符のない「tag:value」がリテラルの単語ではないという事実をチェックする必要があります (値のテキストが正しいかどうかは検証モデルによって決定され、プロンプト単語には名前とタイトルが元のテキストである必要があります)。引用として書かれているかどうかがチェックされます
        val detail = listOf("标题:为什么秋天的天空特别蓝\n作者 小王")
        assertEquals(false, Grounding.found("文章标题", "标题:「秋天为什么容易犯困」\n依据: 详情页", detail))
        assertEquals(true, Grounding.found("文章标题", "标题:「为什么秋天的天空特别蓝」\n依据: 详情页", detail))
        assertTrue(Verifier.PROMPT, "名前、タイトル" in Verifier.PROMPT)
        // は、構造 (「結論:」) は画面上のフィールドではないと回答しました
        assertEquals(true, Grounding.found("热榜第一", "结论:第一条最热,是「为什么秋天的天空特别蓝」\n依据: 热榜页", listOf(hot)))
    }

    @Test fun datesAndTimesAreClaims() {
        val s = listOf("订单详情\n下单时间 2026-10-23 14:05\n已发货")
        assertEquals(true, Grounding.found("什么时候下的单", "10月23日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月22日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月23日 15:05 下的单\n依据: 订单详情「已发货」", s))
    }

    // ラジオボタンの選択やスイッチのオン状態は本文には記載されません。スクリーンショットを見た検証者が合否判定にカウントされ、画像を見て検証として記録されます。
    @Test fun visualStateIsGroundedByImageOnlyWhenAnImageWasSent() = runTest {
        val shown = "配送方式\n标准配送\n加急配送\n¥12"
        val answer = "现在选的是「标准配送」「已选中」,运费 ¥12\n依据: 配送方式一栏"
        val crit = Criteria(listOf("回答给出当前选中的配送方式"), "read", fallback = false)
        val ok = """{"checks": [{"criterion": "回答给出当前选中的配送方式", "status": "met", "quote": "标准配送"}], "verdict": "pass", "reason": "截图里标准配送是选中的"}"""
        val v = Verifier(FakeModel(reply(ok)), FakeModel(reply(ok))).verify("现在选的是哪种配送", crit, answer, screen(shown, shot = byteArrayOf(1)))
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(Verifier.IMAGE, v.groundedBy)
        assertEquals(listOf("已选中"), v.notFound)
        // スクリーンショットなし: 同じ回答は検証できません
        val noImage = Verifier(FakeModel(reply(ok)), null).verify("现在选的是哪种配送", crit, answer, screen(shown))
        assertEquals(Verdict.CannotVerify, noImage.verdict)
        // すべての文字が揃ったら、いつものようにテキスト認証です。
        val plain = Verifier(FakeModel(reply(ok)), FakeModel(reply(ok))).verify("现在选的是哪种配送", crit, "选的是「标准配送」\n依据: 配送方式一栏", screen(shown, byteArrayOf(1)))
        assertEquals(null, plain.groundedBy)
    }

    @Test fun timeoutGrowsWithEvidenceAndIsCapped() {
        assertEquals(Verifier.TIMEOUT_MS, Verifier.timeoutMs(500, 0))
        assertTrue(Verifier.timeoutMs(40 * 1024, 1) > Verifier.timeoutMs(500, 1))
        assertTrue(Verifier.timeoutMs(500, 1) > Verifier.timeoutMs(500, 0))
        assertEquals(Verifier.MAX_TIMEOUT_MS, Verifier.timeoutMs(10_000_000, 3))
    }

    @Test fun timeoutRetriesOnceWithTrimmedEvidence() = runTest {
        val bodies = mutableListOf<String>()
        val slowOnce = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                bodies += (messages.single().parts.first() as Part.Text).text
                if (bodies.size == 1) delay(Verifier.MAX_TIMEOUT_MS + 1_000)
                return reply(pass)
            }
        }
        // 各エントリには回答の値が含まれています。再質問すると、エントリは失われず、抜粋は短くなり、最新のアクションのみが保持されます。
        val old = (1..5).map { "旧读屏$it\n" + "字".repeat(1_500) + "\n" + "字".repeat(1_500) + "\n1561 万热度\n" + "字".repeat(1_500) }
        val e = Evidence(freshOf(hot), null, ledgerOf(old), (1..30).map { "第${it}步 tap 生效" }, protected = false)
        val v = Verifier(slowOnce, null).verify("热榜第一是什么", criteria, "热榜第一是「为什么秋天的天空特别蓝」,1561万热度\n依据: 热榜页", e)
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(2, bodies.size)
        assertTrue("第1步" in bodies[0] && bodies[0].length < 12_000)
        assertFalse(bodies[1], "第1步 tap" in bodies[1])
        assertTrue("第30步" in bodies[1] && bodies[1].length < bodies[0].length)
        assertEquals(6, Regex("1561 万热度").findAll(bodies[1]).count())
    }
}
