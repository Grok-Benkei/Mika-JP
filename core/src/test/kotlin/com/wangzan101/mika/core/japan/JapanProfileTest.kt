package com.wangzan101.mika.core.japan

import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.DeclareResult
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.Permit
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.*
import org.junit.Test

class JapanProfileTest {
    @Test fun japaneseNamesResolveToTheRightInstalledApp() {
        val store = KnowledgeStore(KnowledgeStore.loadBundled())
        mapOf("ライン" to "jp.naver.line.android", "楽天市場" to "jp.co.rakuten.android",
            "メルカリ" to "com.kouzoh.mercari", "Googleマップ" to "com.google.android.apps.maps",
            "設定アプリ" to "com.android.settings").forEach { (name, pkg) -> assertEquals(name, pkg, store.packageFor(name)) }
        assertTrue(store.matchTask("明日のアラームを設定して").none { it.name == "android-settings" })
        assertTrue(store.matchTask("設定を開いてAndroidのバージョンを確認して").any { it.name == "android-settings" })
        assertEquals("amazon.co.jp", store.byDomain("www.amazon.co.jp")?.open)
    }

    @Test fun japaneseActionsAreClassifiedAndHistoryIsNotAnAction() {
        mapOf("送信" to IrrevAction.send, "メッセージを送信" to IrrevAction.send,
            "ポストする" to IrrevAction.send, "購入する" to IrrevAction.order,
            "注文を確定する" to IrrevAction.order, "予約する" to IrrevAction.order,
            "支払う" to IrrevAction.pay, "支払いを確定" to IrrevAction.pay,
            "削除する" to IrrevAction.delete, "送信取消" to IrrevAction.delete).forEach { (name, action) ->
            assertEquals(name, action, IrreversibleDetector.matchName(name))
        }
        listOf("購入履歴", "支払い方法", "送信済み", "送信先", "削除しない", "購入しない", "検索", "キャンセル").forEach {
            assertNull(it, IrreversibleDetector.matchName(it))
        }
    }

    @Test fun japaneseYenPaymentConfirmationHasContext() {
        val screen = ScreenSnapshot("jp-payment", "jp.ne.paypay.android.app", listOf(
            UiElement("@total", "text", "お支払い金額 １，２８０円", 0, 0, listOf(20, 600, 800, 670), null, false, false, false),
            UiElement("@method", "text", "お支払い方法", 0, 0, listOf(20, 680, 800, 740), null, false, false, false),
            UiElement("@ok", "button", "確定", 0, 0, listOf(20, 850, 900, 940), null, true, false, false),
        ), 1080, 2400)
        assertEquals(IrrevAction.pay, IrreversibleDetector.matchName("確定", screen, screen.elements.last()))
    }

    @Test fun japaneseAuthenticationAndPaymentRequireTheUser() {
        mapOf("認証コードを入力してください" to "login", "私はロボットではありません" to "captcha",
            "支払いを確定" to "payment").forEach { (text, reason) ->
            val result = ScreenDiagnosis.classify(ScreenFacts("jp.example", true, 3, false, emptyList(), listOf(text), false))
            assertEquals(text, ScreenState.NEEDS_HUMAN, result.state)
            assertEquals(text, reason, result.takeoverReason)
        }
        assertEquals(ScreenState.NORMAL, ScreenDiagnosis.classify(
            ScreenFacts("jp.example", true, 3, false, emptyList(), listOf("お待ちください"), false)).state)
    }

    @Test fun yenTotalsSupportFullWidthDigitsAndRejectADifferentCurrency() {
        fun permit(displayed: String, declared: String): Permit {
            val pkg = "jp.example.shop"
            val request = ConfirmationRequest(IrrevAction.order, "商品", "商品を購入", declared, pkg)
            val gate = ConfirmationGate()
            assertEquals(DeclareResult.Pending, gate.declare(request, pkg))
            gate.decide(true, 1)
            val screen = ScreenSnapshot("total", pkg, listOf(
                UiElement("@product", "text", "商品", 0, 0, listOf(10, 10, 900, 100), null, false, false, false),
                UiElement("@amount", "text", "合計 $displayed", 0, 0, listOf(10, 700, 900, 780), null, false, false, false),
                UiElement("@buy", "button", "注文を確定する", 0, 0, listOf(10, 850, 900, 940), null, true, false, false),
            ), 1080, 2400)
            return gate.permits(Irreversible(IrrevAction.order, "注文を確定する"), screen, 2)
        }
        assertEquals(Permit.Allowed, permit("１，２８０円", "1,280円"))
        assertEquals(Permit.Allowed, permit("JPY 1,280", "1280円"))
        assertTrue(permit("1,280元", "1280円") is Permit.Mismatch)
        assertTrue(permit("1,280円", "1280 USD") is Permit.Mismatch)
        assertTrue(permit("1,280円", "1200円") is Permit.Mismatch)
    }

    @Test fun learnedJapaneseSkillsUseAllRequiredSections() {
        val document = "---\nname: japanese-task\ndisplay: 日本語の手順\ndescription: 日本語の依頼に使う\n---\n" +
            listOf("開始条件", "手順", "注意点", "完了の確認", "ユーザーに引き継ぐ条件").joinToString("\n") { "## $it\n確認した内容を記録する。" }
        assertNull(SkillDrafter.validate(document, emptySet(), null))
        assertEquals("missing_section", SkillDrafter.validate(document.replace("## 手順", "## その他"), emptySet(), null))
    }
}
