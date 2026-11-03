package com.wangzan101.mika.capsule

import com.wangzan101.mika.ui.appString
import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleContentTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun show(view: CapsuleView.Shown) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(view, onTap = { clicks += "open" }, onStop = { clicks += "stop" }, onHandBack = { clicks += "back" })
            }
        }
    }

    @Test fun runningIsABallWithRingThatSaysWhatItDoesAndOpensOnTap() {
        show(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏", count = 1, runKey = "a", conversationId = "c"))
        compose.onNodeWithContentDescription(appString(R.string.capsule_desc_running, "在搜牙膏")).assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // その文章は書かないでください
        compose.onNodeWithText("在搜牙膏").assertDoesNotExist()
        compose.onNodeWithTag("capsule-ball").performClick()
        assertEquals(listOf("open"), clicks)
    }

    @Test fun eachBallStateShowsItsMarks() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 3))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        // 数個：角に個数を記入
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        // およびその他の条件: 灰色の円、個数なし
        view = CapsuleView.Shown(CapsuleKind.Held, appString(R.string.capsule_hold_why, "排队中"), count = 1)
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertDoesNotExist()
        // 私が見たものに対処してほしい: オレンジ色の丸がまだ 1 つあります
        view = CapsuleView.Shown(CapsuleKind.NeedConfirm, appString(R.string.capsule_needs_ok, "发送"), count = 1, needCount = 1)
        compose.onNodeWithContentDescription(appString(R.string.capsule_desc_needs, appString(R.string.capsule_needs_ok, "发送"))).assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // 完了、未完了、停止：コーナーマーク、丸なし
        for (kind in listOf(CapsuleKind.Done, CapsuleKind.Failed, CapsuleKind.Cancelled)) {
            view = CapsuleView.Shown(kind, "结果")
            compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        // アイドル: アイコンのみ
        view = CapsuleView.Shown(CapsuleKind.Idle, "")
        compose.onNodeWithContentDescription(CapsuleCopy.IDLE).assertIsDisplayed()
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun pillShowsItsLineAndOpensOnTap() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedTakeover, appString(R.string.capsule_needs_you, "付款"), pill = true))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, { clicks += "open" }, {}, {}) }
        }
        for (v in listOf(
            CapsuleView.Shown(CapsuleKind.NeedTakeover, appString(R.string.capsule_needs_you, "付款"), pill = true),
            CapsuleView.Shown(CapsuleKind.NeedConfirm, appString(R.string.capsule_needs_ok, "发送"), pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, appString(R.string.capsule_asks, "要哪个？"), pill = true),
            CapsuleView.Shown(CapsuleKind.Done, appString(R.string.capsule_done_tap), pill = true),
            CapsuleView.Shown(CapsuleKind.Failed, appString(R.string.capsule_failed_tap), pill = true),
            CapsuleView.Shown(CapsuleKind.Cancelled, appString(R.string.capsule_stopped), pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, appString(R.string.capsule_need_of_total, 1, 2), count = 2, needCount = 1, pill = true),
        )) {
            view = v
            compose.onNodeWithText(v.text).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("capsule").performClick()
        assertEquals(listOf("open"), clicks)
    }

    /** 伸縮：同じベースの幅を変更すると、収納時は星の幅になります。 */
    @Test fun pillShrinksBackToBall() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Done, appString(R.string.capsule_done_tap), pill = true))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}, side = DockSide.Left) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(pill = false)
        compose.waitForIdle()
        val ball = compose.onNodeWithTag("capsule-ball").getBoundsInRoot()
        assertTrue("ball should be narrower: ${ball.width} vs $wide", ball.width < wide)
        assertEquals(ball.height.value, ball.width.value, 1f)
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun handedOverBarHasStopAndHandBack() {
        show(CapsuleView.Shown(CapsuleKind.HandedOver, CapsuleModel.HANDED_OVER, detail = "付款 · " + appString(R.string.capsule_then_tap_right), runKey = "a"))
        compose.onNodeWithTag("capsule-bar").assertIsDisplayed()
        compose.onNodeWithText("付款 · " + appString(R.string.capsule_then_tap_right)).assertIsDisplayed()
        compose.onNodeWithText(CapsuleCopy.STOP).performClick()
        compose.onNodeWithText(appString(R.string.action_hand_back)).performClick()
        assertEquals(listOf("stop", "back"), clicks)
    }

    /** 同じ段落 ([CapsuleView.Shown.widthEpoch] は変更されません) が短い文に変更されますが、カプセルは狭くなりません。段落を変更すると幅が再設定されます。 */
    @Test fun widthOnlyGrowsWithinEpoch() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedAnswer, appString(R.string.capsule_asks, "要三家店里哪一家的"), pill = true, widthEpoch = 1))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(text = "短")
        compose.onNodeWithTag("capsule").assertWidthIsAtLeast(wide)
        view = view.copy(widthEpoch = 2)
        compose.waitForIdle()
        val narrow = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        assertTrue("new epoch should re-fit: $narrow vs $wide", narrow < wide)
    }

    /** がフェードアウトしても、外側のリングとコーナー マークはまだ残っています。画面を読み取っているときに「画面の反対側に移動」することができます。 */
    @Test fun fadedBallKeepsMarksAndOffersSwapSide() {
        var swapped = 0
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 2), {}, {}, {},
                    alpha = CapsuleFade.BALL, onSwapSide = { swapped++ },
                )
            }
        }
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == CapsuleCopy.SWAP_SIDE }.action() }
        assertEquals(1, swapped)
    }

    /** には、画面を読むときに長押しする代わりに「トーク」が追加されています。長押しして話すと、カプセルが青い枠で点灯します。 */
    @Test fun talkActionAndHoldingRing() {
        var talked = 0
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Idle, ""), {}, {}, {},
                    holding = true, onTalk = { talked++ }, talkLabel = "说话",
                )
            }
        }
        compose.onNodeWithTag("capsule-holding", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "说话" }.action() }
        assertEquals(1, talked)
    }
}
