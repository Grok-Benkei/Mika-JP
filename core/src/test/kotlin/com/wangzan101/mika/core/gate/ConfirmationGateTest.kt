package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.verb

/** ご請求の際は、下記のページに記載されているお支払い金額25円を申告時にご持参いただく必要があり、お支払いのステップに同意していただくだけです。 */
class ConfirmationGateTest {
    private fun req(action: IrrevAction, app: String, target: String = "张三", content: String = "明天见") =
        ConfirmationRequest(action, target, content, "¥25", app)
    private fun hit(action: IrrevAction) = Irreversible(action, "x")
    private fun el(name: String, editable: Boolean = false, top: Int = 0) =
        UiElement("@e1", "v", name, 0, 0, listOf(0, top, 999, top + 60), null, true, editable, false)
    /** と [peer] のチャット ページ: タイトルが上部にあり、入力ボックスは [typed] です。 */
    private fun chat(app: String = "com.a", typed: String = "明天见", peer: String = "张三", vararg more: UiElement) =
        ScreenSnapshot("s", app, listOf(el(peer, top = 30), el("¥25", top = 800), el(typed, editable = true, top = 900), el("发送", top = 900)) + more, 1080, 2400)

    private fun approved(r: ConfirmationRequest, step: Int = 1) = ConfirmationGate().apply {
        assertEquals(DeclareResult.Pending, declare(r, r.app)); decide(true, step)
    }

    @Test fun approvalIsSingleUseAndScoped() {
        val g = approved(req(IrrevAction.send, "com.a"))
        assertEquals(Permit.Allowed, g.permits(hit(IrrevAction.send), chat(), 2))
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
        // その他のアクション、その他のアプリ: 正しくありません。それに応じて契約は無効になります。
        val kind = approved(req(IrrevAction.send, "com.a"))
        assertTrue(kind.permits(hit(IrrevAction.delete), chat(), 2) is Permit.Mismatch)
        assertEquals(Permit.NoGrant, kind.permits(hit(IrrevAction.send), chat(), 2))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(app = "com.b"), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), null, 2) is Permit.Mismatch)
    }

    // 検証のために送信する連絡先またはアプリケーションのボタンを押します。オブジェクト (役職、会社) は画面上のどこにでもあり、検証する入力ボックスのテキストはありません。
    @Test fun contactButtonApprovalChecksTargetOnScreen() {
        val page = ScreenSnapshot("s", "www.jobs.example", listOf(el("产品经理 · 某公司", top = 400), el("¥25", top = 430), el("立即沟通", top = 460)), 1080, 2400)
        val r = req(IrrevAction.submit, "www.jobs.example", target = "产品经理", content = "点立即沟通,发出预设的招呼语")
        assertEquals(Permit.Allowed, approved(r).permits(hit(IrrevAction.submit), page, 2))
        assertTrue(approved(r.copy(target = "运营专员")).permits(hit(IrrevAction.submit), page, 2) is Permit.Mismatch)
    }

    @Test fun declineGrantsNothing() {
        val g = ConfirmationGate()
        g.declare(req(IrrevAction.send, "com.a"), "com.a")
        g.decide(false)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
        g.decide(true)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
    }

    @Test fun mismatchWhenForegroundDiffers() {
        val g = ConfirmationGate()
        assertEquals(DeclareResult.Mismatch, g.declare(req(IrrevAction.send, "com.a"), "com.b"))
        assertEquals(DeclareResult.Mismatch, g.declare(req(IrrevAction.send, "com.a"), null))
        g.decide(true)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
    }

    @Test fun resetDropsUnusedApprovals() {
        val g = approved(req(IrrevAction.send, "com.a"))
        g.reset()
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
    }

    // C3: 宣言内容をバインドすることに同意します
    @Test fun approvalMustMatchTargetAndContent() {
        assertTrue(approved(req(IrrevAction.send, "com.a", target = "李四")).permits(hit(IrrevAction.send), chat(), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = "后天见"), 2) is Permit.Mismatch)
        // シナリオ B: チャットに「また明日」というメッセージがありますが、入力ボックスには他の単語があります。
        val bubble = chat(typed = "后天见", more = arrayOf(el("明天见", top = 500)))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), bubble, 2) is Permit.Mismatch)
        // シナリオ A: 「また明日」に同意し、「また明日 + リンク」と入力します。
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = "明天见 http://x.example"), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = ""), 2, typing = "明天见 http://x.example") is Permit.Mismatch)
        // 宣言内容がプレフィックスよりも切り捨てられる（...で終わる）場合
        val long = "很长的一段话".repeat(40)
        val capped = long.take(199) + "…"
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a", content = capped)).permits(hit(IrrevAction.send), chat(typed = long), 2))
        // Telephone_type_text Enter: 比較は、このステップで入力する単語です。
        val empty = chat(typed = "")
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), empty, 2, typing = "明天见"))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), empty, 2, typing = "别的话") is Permit.Mismatch)
        // はコンテンツを削除します。オブジェクトは編集できない画面上のどこにでも配置できます
        val del = ScreenSnapshot("s", "com.a", listOf(el("张三", top = 500), el("¥25", top = 550), el("删除", top = 600)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(req(IrrevAction.delete, "com.a", content = "整个会话")).permits(hit(IrrevAction.delete), del, 2))
    }

    // ラウンド 2: オブジェクトは入力ボックスだけでなく、タイトル バー (送信) に存在する必要があり、少なくとも 2 つの単語である必要があります。
    @Test fun targetBindingIsStrict() {
        // は Li Si のチャットに「Zhang San はまた明日会いましょうと言いました」と入力しました。
        val other = chat(peer = "李四", typed = "张三说明天见")
        assertTrue(approved(req(IrrevAction.send, "com.a", content = "张三说明天见")).permits(hit(IrrevAction.send), other, 2) is Permit.Mismatch)
        // Zhang San はチャット テキストにのみ表示され (先頭には表示されません)、カウントされません。
        val body = chat(peer = "李四", more = arrayOf(el("张三", top = 500)))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), body, 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a", target = "张")).permits(hit(IrrevAction.send), chat(peer = "张"), 2) is Permit.Mismatch)
    }

    // ラウンド 3: 注文時に金額を持参する必要があり、その金額が画面に表示されている必要があります
    @Test fun orderAmountMustBeOnScreen() {
        fun order(amount: String?) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(el("耳机", top = 300), el("¥25.00", top = 400), el("立即购买", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("25元")).permits(hit(IrrevAction.order), page, 2))
        assertTrue(approved(order("¥39")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertTrue(approved(order(null)).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
    }

    @Test fun dollarAndTraditionalTotalsBindTheAmount() {
        fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳機", "買一副耳機", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(el("耳機", top = 300), el("運費 NT$60", top = 350), el("總計 NT$1,290", top = 400), el("結帳", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("NT$1290")).permits(hit(IrrevAction.order), page, 2))
        assertTrue(approved(order("NT$60")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
    }

    // C3: 次のステップのみ
    @Test fun approvalExpiresOnOtherWritesAndAfterThreeSteps() {
        for (t in listOf("phone_tap", "phone_swipe", "phone_scroll", "phone_back", "phone_launch_app", "phone_press_key")) {
            val g = approved(req(IrrevAction.send, "com.a"))
            g.noteWrite(verb(t))
            assertEquals(t, Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
        }
        val typing = approved(req(IrrevAction.send, "com.a"))
        typing.noteWrite(verb("phone_clear_text")); typing.noteWrite(verb("phone_type_text"))
        assertEquals(Permit.Allowed, typing.permits(hit(IrrevAction.send), chat(), 2))
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a"), step = 1).permits(hit(IrrevAction.send), chat(), 4))
        assertTrue(approved(req(IrrevAction.send, "com.a"), step = 1).permits(hit(IrrevAction.send), chat(), 5) is Permit.Mismatch)
    }

    // I1 / M2
    @Test fun paymentNeverApprovedAndDeclinedKindNotAskedAgain() {
        val g = ConfirmationGate()
        assertEquals(DeclareResult.Unsupported, g.declare(req(IrrevAction.pay, "com.a"), "com.a"))
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.send, "com.a"), "com.a"))
        g.decide(false)
        assertEquals(DeclareResult.Declined, g.declare(req(IrrevAction.send, "com.a", target = "李四"), "com.a"))
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.delete, "com.a"), "com.a"))
        g.reset()
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.send, "com.a"), "com.a"))
        // 別途同意があっても支払いは解除されません。
        g.decide(true)
        assertTrue(g.permits(hit(IrrevAction.pay), chat(), 1) is Permit.Mismatch)
    }

    // 0b-2: 金額は通貨を含む数値とのみ比較されます。合計がある場合はその合計が使用され、合計がない場合はローリング エリア外の最大のものが使用されます。
    @Test fun orderAmountComparedWithCurrencyAmountsAndTotal() {
        fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(
            el("耳机", top = 100), el("已售25件", top = 200), el("满25减3", top = 300), el("¥2,599", top = 400), el("提交订单", top = 900),
        ), 1080, 2400)
        assertTrue(approved(order("25")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertTrue(approved(order("3元")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("2599元")).permits(hit(IrrevAction.order), page, 2))
        // 合計がある場合のみ合計を認識します(ラベルと金額が同じ要素、またはラベルの直後の要素)
        val total = ScreenSnapshot("s", "com.a", listOf(
            el("耳机", top = 100), el("¥2599", top = 200), el("运费 ¥10", top = 300), el("合计:", top = 900), el("¥2609", top = 900), el("提交订单", top = 900),
        ), 1080, 2400)
        assertTrue(approved(order("2599")).permits(hit(IrrevAction.order), total, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("¥2609.00")).permits(hit(IrrevAction.order), total, 2))
        val inline = ScreenSnapshot("s", "com.a", listOf(el("耳机", top = 100), el("实付 ¥88", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("88元")).permits(hit(IrrevAction.order), inline, 2))
        // スクロールエリアの価格はカウントされません
        val scrolled = ScreenSnapshot("s", "com.a", listOf(
            UiElement("@e9", "v", "", 0, 0, listOf(0, 0, 999, 800), null, false, false, true),
            el("耳机", top = 100), el("¥2599", top = 200), el("¥19", top = 850),
        ), 1080, 2400)
        assertTrue(approved(order("2599")).permits(hit(IrrevAction.order), scrolled, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("19")).permits(hit(IrrevAction.order), scrolled, 2))
    }

    private fun at(name: String, l: Int, t: Int, r: Int, b: Int, editable: Boolean = false, scrollable: Boolean = false, hint: Boolean = false) =
        UiElement("@e", "v", name, 0, 0, listOf(l, t, r, b), null, true, editable, scrollable, hint = hint)
    private fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
    private fun page(vararg e: UiElement) = ScreenSnapshot("s", "com.a", listOf(at("耳机", 0, 100, 999, 160)) + e, 1080, 2400)

    // 修理 1 I1(a): 合計をいくつかのセグメントに分割します
    @Test fun splitTotalIsJoined() {
        val p = page(at("合计:", 0, 900, 150, 960), at("¥", 160, 900, 190, 960), at("1299", 200, 900, 300, 960), at(".00", 300, 900, 350, 960),
            at("提交订单", 700, 900, 999, 960))
        assertEquals(Permit.Allowed, approved(order("1299元")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        assertTrue(approved(order("1299.5")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
        // 合計ワードはありますが、金額が読み取れません: リリースされていません
        val unreadable = page(at("合计:", 0, 900, 150, 960), at("¥2599", 0, 300, 999, 360), at("提交订单", 700, 900, 999, 960))
        assertTrue(approved(order("2599")).permits(Irreversible(IrrevAction.order, "提交订单"), unreadable, 2) is Permit.Mismatch)
    }

    // 修理1 I1(b)：備考欄の「合計￥1」とリスト内の各店舗の合計はカウントされません。割引額はカウントされません
    @Test fun typedAndPerShopTotalsIgnored() {
        val p = page(
            at("", 0, 0, 999, 800, scrollable = true),
            at("店铺小计 合计 ¥30", 0, 200, 999, 260),
            at("合计¥1", 0, 400, 999, 460, editable = true),
            at("已优惠 ¥5", 0, 850, 400, 900),
            at("合计 ¥95", 0, 900, 600, 960), at("提交订单", 700, 900, 999, 960),
        )
        assertEquals(Permit.Allowed, approved(order("95")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        for (a in listOf("1", "30", "5")) assertTrue(a, approved(order(a)).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
        // 合計がない場合：上限金額に割引、送料は含まれません
        val noTotal = page(at("¥99", 0, 300, 999, 360), at("运费 ¥120", 0, 400, 999, 460), at("满200减150", 0, 500, 999, 560), at("立即购买", 0, 900, 999, 960))
        assertEquals(Permit.Allowed, approved(order("99")).permits(Irreversible(IrrevAction.order, "立即购买"), noTotal, 2))
    }

    // 修理 1 I1(c): 合計はスクロール領域内にのみあり、外側にクーポンがあります: ボタンに最も近い合計を取得します
    @Test fun totalInsideScrollWithCouponOutside() {
        val p = page(
            at("", 0, 0, 999, 800, scrollable = true),
            at("合计 ¥10", 0, 100, 999, 160),
            at("合计 ¥88", 0, 700, 999, 760),
            at("优惠券 -¥10", 0, 820, 999, 880),
            at("提交订单", 700, 900, 999, 960),
        )
        assertEquals(Permit.Allowed, approved(order("88")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        assertTrue(approved(order("10")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
    }
}

