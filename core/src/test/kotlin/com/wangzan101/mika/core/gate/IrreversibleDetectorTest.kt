package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun el(
    ref: String, name: String, bounds: List<Int>, res: String? = null, clickable: Boolean = true,
    editable: Boolean = false, focused: Boolean = false, scrollable: Boolean = false, hint: Boolean = false,
) = UiElement(ref, if (editable) "edit" else "button", name, 0, 0, bounds, res, clickable, editable, scrollable, hint = hint, focused = focused)

private fun snap(vararg e: UiElement, id: String = "s1") = ScreenSnapshot(id, "com.chat", e.toList(), 1080, 2400)
private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class IrreversibleDetectorTest {
    private val d = IrreversibleDetector

    @Test fun japaneseCancellationAndDataDeletionLabels() {
        for ((label, action) in listOf(
            "取り消す" to IrrevAction.delete, "予約をキャンセル" to IrrevAction.order,
            "キャンセルする" to IrrevAction.delete, "解約" to IrrevAction.delete,
            "消去" to IrrevAction.delete, "すべてクリア" to IrrevAction.delete,
            "初期化" to IrrevAction.delete, "リセット" to IrrevAction.delete,
            "予約を取り消す" to IrrevAction.order, "データを消去する" to IrrevAction.delete,
            "設定をリセット" to IrrevAction.delete, "送信を取り消す" to IrrevAction.delete,
        )) assertEquals(label, action, d.matchName(label))
    }

    @Test fun japaneseConfirmationQuestionsHaveTheirConcreteAction() {
        for ((question, action) in listOf(
            "この予約を取り消しますか？" to IrrevAction.order,
            "この注文をキャンセルしますか？" to IrrevAction.order,
            "データを消去しますか？" to IrrevAction.delete,
            "この設定をリセットしてもよろしいですか？" to IrrevAction.delete,
        )) {
            val dialog = snap(
                el("@e1", question, listOf(0, 300, 999, 400), clickable = false),
                el("@e2", "はい", listOf(500, 500, 999, 600)),
                el("@e3", "キャンセル", listOf(0, 500, 450, 600)),
            )
            assertEquals(question, action, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog)?.action)
            assertNull(question, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), dialog))
        }
    }

    @Test fun cancellationNegationsHistoryAndSettingsRemainHarmless() {
        for (label in listOf("キャンセル", "取り消さない", "キャンセルしない", "予約をキャンセルしない", "解約しない", "消去しません",
            "初期化しない", "リセットしない", "送信履歴", "削除履歴", "解約履歴", "解約方法", "初期化設定", "リセットの説明", "予約キャンセル履歴")) {
            assertNull(label, d.matchName(label))
            val dialog = snap(el("@e1", label, listOf(0, 300, 999, 400), clickable = false), el("@e2", "はい", listOf(500, 500, 999, 600)))
            assertNull(label, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog))
        }
    }

    @Test fun clearRecordAndListLabelsRetainChineseCompatibility() {
        for (label in listOf("清空记录", "清空列表", "清空記錄")) assertEquals(label, IrrevAction.delete, d.matchName(label))
        for (label in listOf("已清空记录", "清空记录说明", "清空列表入口", "送信履歴", "送信一覧")) assertNull(label, d.matchName(label))
    }

    @Test fun yenAndCurrencyCodesOnConfirmationScreensAreAmounts() {
        for (amount in listOf("980円", "９８０円", "JPY 980", "980 JPY", "5千円", "1万2千円", "1億2000円", "1亿2000元", "1兆2000 JPY", "1京2000 CNY", "$1百", "2十300元", "一万2000円", "十万円", "百円")) {
            val dialog = snap(el("@e1", amount, listOf(0, 300, 999, 400), clickable = false), el("@e2", "はい", listOf(500, 500, 999, 600)))
            assertEquals(amount, IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog)?.action)
        }
    }

    @Test fun chineseSendButton() {
        val s = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), el("@e2", "发送(1)", listOf(0, 0, 100, 100)), el("@e3", "确认支付", listOf(0, 200, 100, 300)))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        assertNull(d.matchName("发送给朋友的那张照片在哪里"))
        for ((w, a) in listOf("删除" to IrrevAction.delete, "退出群聊" to IrrevAction.delete, "提交订单" to IrrevAction.order,
            "立即购买" to IrrevAction.order, "转账" to IrrevAction.pay, "发 布" to IrrevAction.send, "报名" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
    }

    @Test fun englishAndCoordinateTaps() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "Place order", listOf(100, 800, 900, 900)),
            el("@e3", "Buy Now", listOf(100, 600, 900, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":500,"y":850}"""), s)?.action)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":[400,600],"y":650}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":300}"""), s))
        for ((w, a) in listOf("Send" to IrrevAction.send, "post" to IrrevAction.send, "Pay now" to IrrevAction.pay,
            "Delete" to IrrevAction.delete, "Clear all" to IrrevAction.delete, "Checkout" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
        val r = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.chat:id/btnSend"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
    }

    @Test fun iconOnlyButtonFallsBackToTargetLabel() {
        val icon = snap(el("@e1", "", listOf(800, 900, 990, 990), res = "com.chat:id/iv_icon"))
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), icon))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"x":900,"y":950,"target":"发送"}"""), icon))
        val box = snap(el("@e1", "", listOf(800, 900, 990, 990)), el("@e2", "删除", listOf(820, 920, 980, 980), clickable = false))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), box)?.action)
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":1,"y":1,"target":"删除"}"""), null)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e9","snapshotId":"old","target":"Send"}"""), icon)?.action)
        assertTrue(d.unresolved("phone_tap", args("""{"ref":"@e1","snapshotId":"old"}"""), icon))
        assertTrue(d.unresolved("phone_tap", args("""{"x":1,"y":1}"""), null))
        assertFalse(d.unresolved("phone_tap", args("""{"x":900,"y":950}"""), icon))
    }

    // M6: 絵点の位置に名前付き要素(自作インターフェース、名前のないアイコン)がない場合は、同一行のテキストで判定します。
    @Test fun unnamedSpotJudgedByRowText() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "删除", listOf(800, 400, 990, 460), clickable = false),
            el("@e3", "", listOf(50, 400, 150, 460)),
            el("@e4", "张三", listOf(0, 600, 999, 660), clickable = false),
            el("@e5", "删除记录说明", listOf(0, 0, 999, 300), clickable = false),
        )
        assertEquals(Irreversible(IrrevAction.delete, "删除"), d.classify("phone_tap", args("""{"x":100,"y":430}"""), s))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":400,"y":430}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":630}"""), s))
        // 高すぎる要素 (コンテナー、テキストの大きなブロック) は「同じ行上」とは見なされません
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":200}"""), s))
        // 名前付きの要素をクリックするときは、その要素をクリックするだけです
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":640}"""), s))
    }

    // ユーザーの他の人に連絡し、送信ボタンを押してアプリケーションを送信するだけです。既存の会話、レコード、カスタマー サービス ページ、メッセージを開くだけのスペースはカウントされません。
    @Test fun contactAndApplyButtonsAreSubmissions() {
        for (w in listOf("立即沟通", "沟通", "打招呼", "立即打招呼", "一键投递", "投递简历", "投简历", "立即申请", "申请职位", "应聘",
            "联系TA", "发起聊天", "立即沟通 >", "Say hi", "say  hello!", "Easy Apply", "Apply now", "Contact recruiter")) {
            assertEquals(w, IrrevAction.submit, d.matchName(w))
        }
        for (w in listOf("继续沟通", "已沟通", "沟通记录", "已投递", "投递记录", "联系客服", "联系我们", "消息", "私信", "申请", "申请权限",
            "Apply", "Contact", "Contact us", "Message", "Messages", "沟通技巧有哪些")) {
            assertNull(w, d.matchName(w))
        }
        // ボタンはジョブの詳細 (リストの右側) にあります。番号を押してクリックして確認します。
        val s = snap(el("@e1", "AI Agent 工程师", listOf(300, 100, 999, 160), clickable = false), el("@e2", "立即沟通", listOf(800, 180, 990, 230)),
            el("@e3", "感兴趣", listOf(600, 180, 780, 230)))
        assertEquals(Irreversible(IrrevAction.submit, "立即沟通"), d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s))
    }

    @Test fun submitOnlyExactMatch() {
        assertEquals(IrrevAction.submit, d.matchName("提交"))
        assertEquals(IrrevAction.submit, d.matchName("确认提交"))
        assertEquals(IrrevAction.submit, d.matchName(" Submit "))
        assertNull(d.matchName("提交搜索"))
        assertNull(d.matchName("Submitted"))
        val s = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.x:id/search_submit"))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
    }

    @Test fun searchEnterIsNotSend() {
        val s = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), res = "com.shop:id/search_input", editable = true))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true}"""), s))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), s))
        val focusedSearch = snap(el("@e1", "Search", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true), el("@e2", "备注", listOf(0, 200, 999, 300), editable = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), focusedSearch))
    }

    @Test fun chatEnterIsSend() {
        val s = snap(el("@e1", "说点什么...", listOf(0, 900, 800, 999), editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"你好","pressEnter":true}"""), s)?.action)
        assertNull(d.classify("phone_type_text", args("""{"text":"你好"}"""), s))
        val r = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.chat:id/chat_input", editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), r)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"hi","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_DEL"}"""), r))
    }

    @Test fun readOnlyToolsNeverMatch() {
        val s = snap(el("@e1", "发送", listOf(0, 0, 999, 999)))
        for (t in listOf("phone_observe", "phone_list_elements", "phone_screenshot", "phone_list_apps", "phone_diagnose", "wait", "knowledge_search", "knowledge_read", "skill_search", "skill_read",
            "phone_back", "phone_clear_text", "phone_launch_app", "ask_user", "request_confirmation", "finish", "end_not_done")) {
            assertNull(t, d.classify(t, args("""{"ref":"@e1","snapshotId":"s1","x":500,"y":500,"target":"发送"}"""), s))
        }
    }

    // C1: 座標をクリックすると、モデルが最後に表示されたときではなく、現時点で再読み取られる画面によって判断されます。
    @Test fun coordinateTapUsesFreshRead() {
        val old = snap(el("@e1", "+", listOf(800, 900, 990, 990)))
        val now = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), id = "fresh")
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), old))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"x":900,"y":950}"""), old, now)?.action)
        // ref: 元の読み取り画面、または現時点で同じ位置にある要素、いずれかがカウントされます。
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), old, now)?.action)
    }

    // C2: キーコード判定 (数値書き込み、スペース)、入力ボックスはデフォルトで送信され、検索ボックスのみが欠落します。フォーカスボタンで単語リストを押す
    @Test fun activatingKeysAndFocus() {
        val plain = snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true, focused = true))
        for (k in listOf("066", "KEYCODE_NUMPAD_ENTER", "23")) assertEquals(k, IrrevAction.send, d.classify("phone_press_key", args("""{"key":"$k"}"""), plain)?.action)
        assertNull("入力欄でのスペースキーは文字入力として扱います", d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), plain))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), plain)?.action)
        val button = snap(el("@e1", "发送", listOf(0, 0, 999, 100), focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), button)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_DPAD_CENTER"}"""), button)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "返回", listOf(0, 0, 999, 100), focused = true))))
        // フォーカスが見えない、または画面に入力ボックスがありません: 占い
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "标题", listOf(0, 0, 999, 100), clickable = false))))
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), null))
        assertNotNull(d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true))))
    }

    // I2/M1: 強い単語、一般的な確認、英語フレーズ、除外単語、エントリのカウント
    @Test fun wordMatchingExamples() {
        val hits = mapOf(
            "删除聊天记录" to IrrevAction.delete, "撤回" to IrrevAction.delete, "转发给朋友" to IrrevAction.send,
            "发红包" to IrrevAction.pay, "申请退款" to IrrevAction.order, "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order,
            "去结算(3)" to IrrevAction.order, "使用支付宝支付" to IrrevAction.pay, "Send message" to IrrevAction.send,
            "Post comment" to IrrevAction.send, "Delete conversation" to IrrevAction.delete, "DELETE" to IrrevAction.delete,
            "评论" to IrrevAction.send, "回复:" to IrrevAction.send, "立即预约" to IrrevAction.order, "确认报名" to IrrevAction.order,
            "张三 删除" to IrrevAction.delete,
        )
        for ((w, a) in hits) assertEquals(w, a, d.matchName(w))
        for (w in listOf("评论(23)", "回复 3", "支付宝", "支付方式", "支付设置", "预约挂号指南", "Postpone", "Sender", "Payment history",
            "提交搜索", "确定", "OK", "这是一段很长的聊天消息里面提到了删除两个字")) {
            assertNull(w, d.matchName(w))
        }
        // 一般的な確認: 同じ画面上の強い単語または金額のみがカウントされます
        val dialog = snap(el("@e1", "确定删除这条消息?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确定", listOf(500, 500, 999, 600)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog)?.action)
        val sheet = snap(el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确认", listOf(500, 500, 999, 600)))
        // 金額のみ、支払い文字列なし: 注文を押します (同意する場合、金額は「実際の支払い」と一致する必要があります)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plain = snap(el("@e1", "已保存", listOf(0, 300, 999, 400), clickable = false), el("@e2", "完成", listOf(500, 500, 999, 600)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plain))
        // リソース ID の投稿はカウントされません
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), snap(el("@e1", "", listOf(0, 0, 99, 99), res = "com.x:id/post_list"))))
    }

    // 繁体字中国語インターフェイスと金額: 同じ語彙セットに従って判断されます
    @Test fun traditionalChineseAndDollarAmounts() {
        for ((w, a) in listOf("刪除" to IrrevAction.delete, "確認付款" to IrrevAction.pay, "結帳" to IrrevAction.order, "轉帳" to IrrevAction.pay,
            "立即購買" to IrrevAction.order, "傳送" to IrrevAction.send, "送出" to IrrevAction.submit, "儲值" to IrrevAction.pay)) {
            assertEquals(w, a, d.matchName(w))
        }
        for ((w, s) in listOf("註銷帳户" to "注销账户", "回覆" to "回复", "發佈" to "发布", "聯繫對方" to "联系对方")) {
            assertNotNull(s, d.matchName(s))
            assertEquals(w, d.matchName(s), d.matchName(w))
        }
        assertNull(d.matchName("已付款"))
        assertNull(d.matchName("購買記錄"))
        for (amount in listOf("合計 NT$ 390", "總計 $12.99", "US$12.99")) {
            val sheet = snap(el("@e1", amount, listOf(0, 300, 999, 400), clickable = false), el("@e2", "確定", listOf(500, 500, 999, 600)))
            assertEquals(amount, IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        }
        val pay = snap(el("@e1", "付款方式", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "NT$390", listOf(0, 400, 999, 460), clickable = false), el("@e3", "確認", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), pay)?.action)
        assertNotNull(d.paymentPage(pay))
    }

    // I3: リスト行から開始する十分な長さの水平スワイプ = 削除するスライド
    @Test fun longHorizontalSwipeOnRowIsDelete() {
        val s = snap(el("@e1", "张三 昨天", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true))
        assertEquals(Irreversible(IrrevAction.delete, "スワイプ: 「张三 昨天」"),
            d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
        assertNull("スワイプが短すぎます", d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":600,"endY":350}"""), null, s))
        assertNull("縦方向のスワイプです", d.classify("phone_swipe", args("""{"startX":500,"startY":800,"endX":500,"endY":200}"""), null, s))
        assertNull("対象の行の上ではありません", d.classify("phone_swipe", args("""{"startX":900,"startY":700,"endX":300,"endY":700}"""), null, s))
        assertEquals(IrrevAction.delete, d.classify("phone_scroll", args("""{"direction":"left","amount":5,"x":500,"y":350}"""), null, s)?.action)
        assertNull("既定の移動量では長さが足りません", d.classify("phone_scroll", args("""{"direction":"left","x":500,"y":350}"""), null, s))
        assertNull(d.classify("phone_scroll", args("""{"direction":"down","amount":10}"""), null, s))
    }

    // ラウンド 3: 商品ページで金額ポップアップ ウィンドウが押されます。支払いに基づいて計算され、「注文する」だけでは解除できません。
    @Test fun amountOverOrderPageIsOrderPayWordIsPay() {
        // 製品ページで仕様を選択してください: 今すぐ購入 + 価格 + 確認、支払い言葉なし → 注文する (金額に同意し拘束することができます)、支払いはありません
        val s = snap(
            el("@e1", "立即购买", listOf(0, 900, 999, 999)),
            el("@e2", "¥25.00", listOf(0, 500, 999, 560), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        // 支払いパネル:¥25.00 + 確認 + 支払い → 支払い
        val sheet = snap(
            el("@e1", "支付", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥25.00", listOf(0, 400, 999, 460), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet)?.action)
        // 同じ画面に支払いの文字があります: 支払い優先
        val both = snap(el("@e1", "删除", listOf(0, 0, 99, 99)), el("@e2", "付款", listOf(0, 200, 99, 299)), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), both)?.action)
        // 削除確認ボックスに価格が表示されていますが、削除されたままです。
        val del = snap(el("@e1", "确定删除这件商品?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "¥25.00", listOf(0, 0, 99, 99), clickable = false), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), del)?.action)
    }

    // ラウンド 4: 注文ステータスやレコードなどのラベルはアクションではありません
    @Test fun statusLabelsAreNotActions() {
        for (w in listOf("待付款", "待支付", "付款成功", "已支付", "退款/售后", "购买记录", "微信红包", "支付明细", "删除记录列表")) {
            assertNull(w, d.matchName(w))
        }
        for ((w, a) in listOf("发红包" to IrrevAction.pay, "塞钱进红包" to IrrevAction.pay, "红包" to IrrevAction.pay,
            "申请退款" to IrrevAction.order, "确认付款" to IrrevAction.pay)) {
            assertEquals(w, a, d.matchName(w))
        }
        // ステータス ラベルも、コンテキストとして一般的な「確認」を示しません
        val tabs = snap(el("@e1", "待付款", listOf(0, 0, 99, 99)), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), tabs))
    }

    // ラウンド 5: 検索ボックスに入力しても送信とみなされません
    @Test fun searchFieldsRecognized() {
        // リソース ID で識別できる推奨ワードの検索ボックスのみを表示します。
        val hinted = snap(UiElement("@e1", "edit", "iPhone 16 Pro", 0, 0, listOf(0, 50, 800, 120), "search_edit_frame", true, true, false, hint = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
        // 最後の検索語が結果ページに残り、同じ行に「検索」ボタンがあります。
        val results = snap(el("@e1", "耳机", listOf(0, 50, 800, 120), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertNull(d.classify("phone_type_text", args("""{"text":"蓝牙耳机","pressEnter":true}"""), results))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), results))
        // 「検索」ボタンは別の行にあります: それでも送信を押してください
        val chat = snap(el("@e1", "耳机", listOf(0, 900, 800, 999), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"66"}"""), chat)?.action)
    }

    // オプション: 短いスクロール可能領域 (カルーセル) の開始点は、スライド削除としてカウントされません。
    @Test fun swipeInsideCarouselIsNotDelete() {
        val s = snap(el("@e1", "推荐 新品", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 250, 999, 450), clickable = false, scrollable = true))
        assertNull(d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
    }

    // 0b-1: 入力ボックスに入力された単語はプロンプトではなく、その中の「検索」と「検索」は検索ボックスとみなされません。
    @Test fun searchWordsInTypedTextAreNotSearch() {
        val chat = snap(el("@e1", "帮我搜索一下", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), chat)?.action)
        val en = snap(el("@e1", "research this", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":" please","pressEnter":true}"""), en)?.action)
        // プロンプト内の「検索」は引き続きカウントされます
        val hinted = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
    }

    // 0b-3: スクロール可能領域の価格はコンテキストに含まれません。支払い言葉の確認がない場合は、注文を押すと金額のみが計算されます。
    @Test fun amountsInsideScrollablesAreNotContext() {
        // フィルター パネル: スクロール可能な価格リスト + 下部の「OK」
        val filter = snap(
            el("@e1", "", listOf(0, 200, 999, 800), clickable = false, scrollable = true),
            el("@e2", "¥0-50", listOf(0, 250, 999, 300)),
            el("@e3", "¥50-100", listOf(0, 320, 999, 370)),
            el("@e4", "确定", listOf(500, 850, 999, 950)),
        )
        assertNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), filter))
        // リスト内の価格はスクロール領域内にあり、スクロール領域外の金額が考慮されます
        val sizes = snap(
            el("@e1", "", listOf(0, 0, 999, 700), clickable = false, scrollable = true),
            el("@e2", "¥99.00", listOf(0, 100, 999, 160), clickable = false),
            el("@e3", "立即购买", listOf(0, 900, 999, 999)),
            el("@e4", "确定", listOf(500, 750, 999, 850)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action)
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action != IrrevAction.pay)
    }

    // Repair 1 C1: 支払いパネル全体がスクロール可能な領域にあり、クリック確認もその中にあり、金額は依然として状況に応じて計算されます。合計は 1 つのカテゴリで計算されます
    @Test fun sheetInsideScrollableStillHasContext() {
        val sheet = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plainPrice = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plainPrice))
        // 合計はスクロール領域内にあり、ボタンは外側にあります。合計はまだ計算されています。
        val total = snap(
            el("@e0", "", listOf(0, 0, 999, 800), clickable = false, scrollable = true),
            el("@e1", "合计 ¥88", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), total)?.action)
    }

    // Repair 1 I2: 金額がある場合、支払い方法、English Pay、および任意の長さのセグメントの支払い/支払いがすべて押されます。新しい支払いに関する強い言葉
    @Test fun paySheetWordingWithAmountIsPay() {
        // 下部の支払いパネル: 金額、支払い方法、確認ボタンはすべて画面の下半分にあります
        fun sheet(word: String) = snap(
            el("@e1", "¥25.00", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", word, listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确认", listOf(500, 850, 999, 950)),
        )
        for (w in listOf("支付方式", "付款方式", "支付宝", "零钱", "账户余额", "招商银行卡(1234)", "收银台", "免密支付已开启", "使用指纹", "请输入支付密码",
            "Pay with card", "Payment method", "Checkout total", "本次将从你的账户中支付这笔款项给商家完成订单")) {
            assertEquals(w, IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet(w))?.action)
        }
        for (w in listOf("收银台", "买单", "充值", "提现", "转出", "打赏", "缴费")) assertEquals(w, IrrevAction.pay, d.matchName(w))
        for (w in listOf("待付款", "充值记录", "提现明细")) assertNull(w, d.matchName(w))
        // 「お支払い方法」という文字は、金額がない場合はカウントされません。
        val noAmount = snap(el("@e1", "零钱", listOf(0, 400, 999, 460), clickable = false), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), noAmount))
    }

    // 修復 1 M9: リソース ID での検索が単語で認識される
    @Test fun searchResourceIdOnWordBoundary() {
        val research = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.x:id/research_input", editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), research)?.action)
        val camel = snap(el("@e1", "", listOf(0, 0, 800, 99), res = "com.x:id/etSearch", editable = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), camel))
    }

    // 修理 2-1: 複数のセグメントに分割された金額 (実際の支払い | ¥ | 39.90) もコンテキストとして考慮されます。数字のない「合計」という単語もカウントされます
    @Test fun splitAmountRowIsContext() {
        fun row(scrolled: Boolean) = snap(
            *listOfNotNull(
                if (scrolled) el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true) else null,
                el("@e1", "实付", listOf(0, 700, 200, 760), clickable = false),
                el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
                el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
                el("@e4", "确认", listOf(500, 850, 999, 950)),
            ).toTypedArray(),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(false)))
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(true)))
        val noLabel = snap(
            el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
            el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
            el("@e4", "确认", listOf(500, 850, 999, 950)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), noLabel))
    }

    // 修正 2-2: 支払い方法を紹介する単語が支払いパネルに含まれていません。新しい支払い単語はボタン自体のみを認識します
    @Test fun paymentOptionAdsAreNotPaySheets() {
        val picker = snap(
            el("@e1", "支持花呗付款", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", "¥99.00", listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), picker)?.action)
        val cod = snap(
            el("@e1", "货到付款", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), cod)?.action != IrrevAction.pay)
        // ページ上部の「残高」は支払いパネルではありません。
        val page = snap(
            el("@e1", "账户余额", listOf(0, 100, 999, 160), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), page)?.action != IrrevAction.pay)
        for (w in listOf("充值中心", "话费充值", "生活缴费", "优惠买单", "收款码", "提现入口")) assertNull(w, d.matchName(w))
        for (w in listOf("立即充值", "确认缴费", "去买单", "充值")) assertEquals(w, IrrevAction.pay, d.matchName(w))
    }

    // 入力ボックス内の単語の変更は、元に戻せない操作ではありません。検索ボックスのクリア、単語を含む入力ボックスのクリック、およびテキスト ツールのクリアはブロックされません。それ以降の送信は通常どおり処理されます。
    @Test fun editingTextInAFieldIsNeverIrreversible() {
        val field = el("@e1", "删除聊天记录怎么恢复", listOf(100, 100, 800, 160), editable = true)
        val s = snap(
            field,
            el("@e2", "清空", listOf(810, 100, 880, 160)),
            el("@e3", "", listOf(740, 110, 790, 150), res = "com.app:id/iv_clear"),
            el("@e4", "搜索", listOf(890, 100, 990, 160)),
            el("@e5", "清空", listOf(100, 900, 300, 960)),
            el("@e6", "", listOf(700, 110, 735, 150)),
        )
        for (ref in listOf("@e1", "@e2", "@e3", "@e6")) {
            assertNull(ref, d.classify("phone_tap", args("""{"ref":"$ref","snapshotId":"s1","target":"清空搜索框"}"""), s))
        }
        // 座標点はクリア キー上にあります
        assertNull(d.classify("phone_tap", args("""{"x":845,"y":130}"""), s))
        // クリアテキスト、入力せずに入力するだけ: 判定なし
        assertFalse(d.needsCheck("phone_clear_text", args("""{"ref":"@e1","snapshotId":"s1"}""")))
        assertNull(d.classify("phone_type_text", args("""{"ref":"@e1","snapshotId":"s1","text":"删除"}"""), s))
        // 入力ボックスから遠い「クリア」（ショッピングカートのクリア、履歴のクリア）は削除されたままです
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e5","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun namedSendInsideAnEditorStillCounts() {
        // Web ページ編集領域全体が編集可能で、その中に送信キーが描画されます。名前が付いているものは通常どおりに判断されます。
        val s = snap(el("@e1", "", listOf(0, 800, 999, 990), editable = true), el("@e2", "发送", listOf(850, 900, 990, 980)),
            el("@e3", "", listOf(700, 900, 840, 980), res = "com.app:id/btn_send"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun browserInteractKeysAndDrags() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        for (k in listOf("Enter", "Control+Enter", "Shift+Return")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.send, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)?.action)
        }
        // スペースはボタンをクリックするのと同じです。入力ボックスに入力するだけです
        assertTrue(d.needsCheck(tool, args("""{"action":"key","key":"Space"}""")))
        assertNull(d.classify(tool, args("""{"action":"key","key":"Space"}"""), s))
        val onButton = snap(el("@e2", "删除", listOf(800, 900, 990, 990), focused = true))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"key","key":"Space"}"""), onButton)?.action)
        // アクティベーション キー (キャリッジ リターン、スペース、修飾キーの形式)、クリック フォーカス判定: フォーカスが「削除」にあるとき、ブロック、入力ボックス内のスペースは入力用です
        for (k in listOf("Shift+Space", "Control+Space", "Control+Enter")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertTrue(k, d.needsCheck(tool, a))
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, onButton)?.action)
        }
        assertNull(d.classify(tool, args("""{"action":"key","key":"Shift+Space"}"""), s))
        // フォーカスを移動し、判断せずに閉じます。フォーカスが「削除」にある場合も同様です。矢印キーは選択コントロールでのみ機能します
        for (k in listOf("Tab", "Shift+Tab", "Escape", "ArrowDown", "End")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), onButton))
        }
        // 入力ボックスのキーはすべて編集されています
        for (k in listOf("Tab", "Escape", "Control+A", "ArrowDown", "d", "Backspace", "Delete", "Control+D")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s))
        }
        assertFalse(d.needsCheck(tool, args("""{"action":"key","key":"bogus"}""")))
    }

    @Test fun browserInteractSelectionKeysJudgedByTheControl() {
        val tool = "web_interact"
        fun ui(ref: String, role: String, name: String, b: List<Int>, focused: Boolean = false) =
            UiElement(ref, role, name, 0, 0, b, null, true, false, false, focused = focused)
        val list = snap(
            ui("@e1", "listbox", "操作", listOf(0, 100, 999, 400), focused = true),
            ui("@e2", "option", "归档", listOf(0, 100, 999, 200)),
            ui("@e3", "option", "删除", listOf(0, 200, 999, 300)),
        )
        val radios = snap(
            ui("@e1", "radio", "普通配送", listOf(0, 100, 500, 200), focused = true),
            ui("@e2", "radio", "立即支付", listOf(500, 100, 999, 200)),
        )
        val city = snap(ui("@e1", "combobox", "城市", listOf(0, 100, 999, 200), focused = true), ui("@e2", "button", "删除", listOf(0, 500, 999, 600)))
        val button = snap(ui("@e1", "button", "删除", listOf(0, 100, 999, 200), focused = true))
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown", "d", "Shift+D")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, list)?.action)
            assertEquals(k, IrrevAction.pay, d.classify(tool, a, radios)?.action)
            // コントロールとそれが選択できる項目は無害です
            assertNull(k, d.classify(tool, a, city))
        }
        // 選択コントロールではないボタンは、矢印キーではアクティブになりません (ボタン上の文字はコマンド キーです。browserInteractCommandKeys を参照)
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), button))
        // 選択コントロール上: フォーカスの移動と閉じるは判定されず、Ctrl を含む文字は最初の文字でジャンプされません。
        for (k in listOf("Tab", "Escape", "Control+D")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), list))
    }

    @Test fun browserInteractCommandKeys() {
        val tool = "web_interact"
        fun ui(ref: String, name: String, b: List<Int>, focused: Boolean = false, keys: List<String> = emptyList()) =
            UiElement(ref, "button", name, 0, 0, b, null, true, false, false, focused = focused, keyShortcuts = keys)
        fun hit(k: String, s: ScreenSnapshot) = d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)
        // フォーカスは要素上にあります。要素上で、削除、バックスペース、単一の英数字、Ctrl/Alt/Meta+文字がすべてクリックされます。
        val onTrash = snap(ui("@e1", "删除", listOf(0, 0, 999, 100), focused = true))
        val onCard = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true))
        for (k in listOf("Delete", "Backspace", "d", "7", "Shift+D", "Control+D", "Alt+1", "Meta+Shift+K")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.delete, hit(k, onTrash)?.action)
            assertNull(k, hit(k, onCard))
        }
        // フォーカス要素 (ページ自体) がありません。削除とバックスペースはページ上で選択された項目に適用され、ブロックされます。
        val page = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100)))
        assertEquals(Irreversible(IrrevAction.delete, "Delete:焦点不明"), hit("Delete", page))
        assertEquals(IrrevAction.delete, hit("Backspace", page)?.action)
        // その他のコマンドキー: 宣言されていない場合、ページは解放されます
        for (k in listOf("d", "Control+K", "Alt+s")) assertNull(k, hit(k, page))
        // ページでは、ショートカット キーを宣言しています。どのコントロールを押すか (aria-keyshortcuts、accesskey は Alt+ キーに置き換えられます)
        val declared = snap(
            ui("@e1", "删除邮件", listOf(0, 0, 999, 100), keys = listOf("Control+Shift+K", "#")),
            ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")),
            ui("@e3", "刷新", listOf(0, 400, 999, 500), keys = listOf("r")),
        )
        assertEquals(IrrevAction.delete, hit("Shift+Control+K", declared)?.action)
        assertNull(hit("Control+K", declared))
        assertEquals(IrrevAction.send, hit("Alt+S", declared)?.action)
        assertNull(hit("r", declared))
        // フォーカスは無害な要素にありますが、ページによって押されたキーは「送信」として宣言されています
        val focusedElsewhere = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true), ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")))
        assertEquals(IrrevAction.send, hit("Alt+s", focusedElsewhere)?.action)
    }

    @Test fun browserInteractDragsAndSelects() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        // 削除領域にドラッグし、「Slide to Pay」をドラッグします。カードを空白スペースにドラッグしてもカウントされません
        assertEquals(Irreversible(IrrevAction.delete, "drag:删除"),
            d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toRef":"@e2"}"""), s))
        assertEquals(IrrevAction.pay, d.classify(tool, args("""{"action":"drag","ref":"@e3","snapshotId":"s1","toX":990,"toY":550}"""), s)?.action)
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"drag","x":500,"y":350,"toX":900,"toY":950}"""), s)?.action)
        assertNull(d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toX":500,"toY":700}"""), s))
        // ホバリング時の判定なし
        assertFalse(d.needsCheck(tool, args("""{"action":"hover","ref":"@e2","snapshotId":"s1"}""")))
        // ドロップダウン選択 ドロップダウン ボックス自体と選択した項目を押します
        val form = snap(el("@e1", "城市", listOf(0, 100, 999, 200)), el("@e2", "提交方式", listOf(0, 300, 999, 400)), el("@e3", "删除", listOf(0, 500, 999, 600)))
        assertTrue(d.needsCheck(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}""")))
        assertNull(d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}"""), form))
        assertEquals(Irreversible(IrrevAction.delete, "option:删除账户"),
            d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"删除账户"}"""), form))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"select","ref":"@e3","snapshotId":"s1","option":"x"}"""), form)?.action)
    }
}
