package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import com.wangzan101.mika.core.testing.verb

class ClassifiedRiskTest {
    private fun el(ref: String, role: String, name: String, b: List<Int>, editable: Boolean = false, secret: Boolean = false, focused: Boolean = false) =
        UiElement(ref, role, name, 0, 0, b, null, !editable, editable, false, focused = focused, secret = secret)
    private val screen = ScreenSnapshot("s1", "com.any", listOf(
        el("@e1", "Switch", "隐私保护", listOf(0, 0, 999, 100)),
        el("@e2", "TextView", "隐私政策", listOf(0, 100, 999, 200)),
        el("@e3", "Button", "修改密码", listOf(0, 200, 999, 300)),
        el("@e4", "EditText", "", listOf(0, 300, 999, 400), editable = true, secret = true, focused = true),
        el("@e5", "Button", "Uninstall", listOf(0, 400, 999, 500)),
        el("@e6", "Button", "好的", listOf(0, 500, 999, 600)),
    ), 1080, 2400)
    private val shop = screen.copy(elements = screen.elements + el("@e7", "TextView", "合计 ¥25", listOf(0, 600, 999, 700)))

    private fun assess(tool: String, hit: Irreversible? = null, on: ScreenSnapshot? = screen) = ClassifiedRisk.assess(verb(tool), hit) { on }

    @Test fun levelsFollowTheClassifier() {
        assertEquals(Risk(ActionRisk.Read), assess("phone_back"))
        assertEquals(Risk(ActionRisk.Read), assess("phone_scroll"))
        assertEquals(Risk(ActionRisk.Change), assess("phone_tap"))
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.send, "发送")))
        assertEquals(Risk(ActionRisk.External), assess("phone_tap", Irreversible(IrrevAction.submit, "提交")))
        assertEquals(Risk(ActionRisk.Irreversible), assess("phone_tap", Irreversible(IrrevAction.delete, "删除")))
        assertEquals(Risk(ActionRisk.Unknown), assess("phone_tap", Irreversible(IrrevAction.other, "其他")))
    }

    @Test fun onlySpendingMoneyIsPayment() {
        // 支払いと注文はお金を使うことを意味します。
        assertEquals(Risk(ActionRisk.Irreversible, payment = true), assess("phone_tap", Irreversible(IrrevAction.pay, "付款")))
        assertEquals(Risk(ActionRisk.Irreversible, payment = true), assess("phone_tap", Irreversible(IrrevAction.order, "提交订单")))
        // 画面上に金額がある場合、外部および不可逆的なアクションは金額としてカウントされます。
        assertEquals(Risk(ActionRisk.External, payment = true), assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = shop))
        // アカウント番号、パスワード、セキュリティ設定、パスワードボックス、はっきりと見えないものはすべてお金ではありません。
        for (tool in listOf("phone_tap", "phone_type_text")) assertEquals(false, assess(tool).payment)
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.delete, "注销账号")).payment)
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.send, "发送")).payment)
        // 入力ボックス内の金額 (自分で入力した単語) はカウントされません。ミスの不可逆的な判断における一般的な変化は、金額には着目しません。
        val typed = screen.copy(elements = screen.elements + el("@e7", "EditText", "¥25", listOf(0, 600, 999, 700), editable = true))
        assertEquals(false, assess("phone_tap", Irreversible(IrrevAction.submit, "提交"), on = typed).payment)
        assertEquals(Risk(ActionRisk.Change), assess("phone_tap", on = shop))
    }

    @Test fun coordinateTapsUseTheSmallestElementThere() {
        assertEquals("修改密码", ClassifiedRisk.target(verb("phone_tap"), buildJsonObject { put("x", 500); put("y", 250) }, screen)?.name)
    }

    @Test fun japaneseAmountsRequireApprovalForSubmissionsAndDeletion() {
        for (amount in listOf("月額980円", "月額９８０円", "1,980円", "１，９８０円", "9万円", "９万円", "9 万 円", "5千円", "1万2千円", "1万2000円", "1億2000円", "1亿2000元", "1兆2000 JPY", "1京2000 CNY", "USD 1万", "￥2百", "2十300元", "ＵＳＤ１億", "一万2000円", "三千円", "壱万2000元", "十万円", "百円", "￥一万2000", "JPY 980", "980 JPY", "￥９８０", "25元", "$25")) {
            val on = screen.copy(elements = listOf(el("@e7", "TextView", amount, listOf(0, 600, 999, 700))))
            for (action in listOf(IrrevAction.submit, IrrevAction.delete)) {
                val risk = assess("phone_tap", Irreversible(action, "実行"), on)
                assertTrue(amount, risk.payment)
                assertEquals(amount, Approval.Ask, ApprovalPolicy.decide(risk))
            }
            assertEquals(amount, false, assess("phone_tap", Irreversible(IrrevAction.send, "送信"), on).payment)
            val editable = on.copy(elements = on.elements.map { it.copy(editable = true) })
            assertEquals(amount, false, assess("phone_tap", Irreversible(IrrevAction.submit, "提出"), editable).payment)
        }
        for (text in listOf("980", "980件", "通貨: JPY")) {
            val on = screen.copy(elements = listOf(el("@e7", "TextView", text, listOf(0, 600, 999, 700))))
            assertEquals(text, false, assess("phone_tap", Irreversible(IrrevAction.submit, "提出"), on).payment)
        }
    }

    @Test fun defaultDecisionRequiresConfirmationOnYenScreens() = runTest {
        for (amount in listOf("月額980円", "月額９８０円", "JPY 980", "9万円", "5千円", "1万2千円", "1億2000円", "1万2000元", "USD 1万", "百円")) for (button in listOf("提出", "削除")) {
            val on = screen.copy(elements = listOf(
                el("@e1", "Button", button, listOf(0, 800, 999, 900)),
                el("@e2", "TextView", amount, listOf(0, 600, 999, 700)),
            ))
            val port = object : ScreenPort by FakeScreen() {
                override suspend fun snapshot() = on
                override suspend fun peek() = on
            }
            val dispatcher = toolDispatcher(port)
            dispatcher.executeRead(Part.ToolCall("observe", "phone_observe", "{}"))
            val decision = ConfirmDecision(dispatcher, ConfirmationGate()).judge("phone_tap", buildJsonObject {
                put("ref", "@e1"); put("snapshotId", "s1")
            }, Origin.Model(2))
            assertTrue("$amount / $button: $decision", decision is Decision.Required)
        }
    }

    @Test fun splitYenAmountsUseOnlyReadOnlyElementsOnTheSameRow() {
        val number = el("@e1", "TextView", "980", listOf(0, 600, 150, 660))
        val yen = el("@e2", "TextView", "円", listOf(160, 600, 200, 660))
        fun spending(vararg elements: UiElement) = assess("phone_tap", Irreversible(IrrevAction.submit, "提出"), screen.copy(elements = elements.toList())).payment
        assertTrue(spending(number, yen))
        assertEquals(false, spending(number.copy(editable = true), yen))
        assertEquals(false, spending(number, yen.copy(editable = true)))
        assertEquals(false, spending(number, yen.copy(hint = true)))
        assertEquals(false, spending(number, yen.copy(bounds = listOf(160, 800, 200, 860))))
        assertEquals(false, spending(number, yen.copy(bounds = emptyList())))
    }
}
