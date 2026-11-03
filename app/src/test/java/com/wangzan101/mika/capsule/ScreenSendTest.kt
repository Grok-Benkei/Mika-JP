package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.ScreenCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ダブルクリックまたはシングルクリック、スクリーンショットをどの会話に送信するか、画面がある場合はリスト内でどのように呼び出すか。 */
class ScreenSendTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun secondTapWithinTimeoutIsDouble() {
        val t = DoubleTap(300)
        assertEquals(DoubleTap.Tap.First, t.tap(1_000))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_250))
        // ダブルクリックはシングルクリックとしてカウントされなくなりました。
        assertFalse(t.settle(1_400))
    }

    @Test fun singleTapSettlesAfterTimeout() {
        val t = DoubleTap(300)
        assertEquals(DoubleTap.Tap.First, t.tap(1_000))
        assertFalse(t.settle(1_200))
        assertTrue(t.settle(1_300))
        assertFalse("1 回だけ数える", t.settle(1_400))
    }

    @Test fun lateSecondTapStartsOver() {
        val t = DoubleTap(300)
        t.tap(1_000)
        assertEquals(DoubleTap.Tap.First, t.tap(1_400))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_600))
    }

    @Test fun resetForgetsTheFirstTap() {
        val t = DoubleTap(300)
        t.tap(1_000)
        t.reset()
        assertFalse(t.settle(2_000))
        assertEquals(DoubleTap.Tap.First, t.tap(2_100))
    }

    private fun run(key: String, conversation: String, state: TaskState, kind: CapsuleKind, onScreen: Boolean = false) =
        CardRun(CapsuleRun(key, conversation, state, goal = "g", onScreen = onScreen), kind, "")

    @Test fun screenGoesToTheConversationTheCardShows() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        val done = run("b", "c2", TaskState.Succeeded("好"), CapsuleKind.Done)
        assertEquals("c1", CapsuleCards.conversationOf(CapsuleCards.of(listOf(running), null)!!))
        assertEquals("c2", CapsuleCards.conversationOf(CapsuleCards.of(listOf(done), null)!!))
        // アイドル カード: 最新の会話を継続します。新しい会話が選択されると、発信者によって会話が開かれます
        assertEquals("c9", CapsuleCards.conversationOf(CardView.Chat("c9", title = "t")))
        assertNull(CapsuleCards.conversationOf(CardView.Chat("c9", fresh = true)))
        assertNull(CapsuleCards.conversationOf(CardView.Chat(null)))
        // 複数の内容がある場合、カードには一番上のものだけが表示され、画面はそれが参加している会話に送信します。
        val several = CapsuleCards.of(listOf(running, done))!!
        assertEquals(CardInputKind.Steer, CapsuleCards.input(several)?.kind)
        assertEquals("c1", CapsuleCards.conversationOf(several))
    }

    @Test fun inputKindDecidesHowTheScreenIsSent() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(running), null)!!)!!.kind)
        val asking = run("b", "c2", TaskState.WaitingInput("记一笔吗?", listOf("记一笔", "不用")), CapsuleKind.NeedAnswer)
        assertEquals(CardInputKind.Answer, CapsuleCards.input(CapsuleCards.of(listOf(asking), null)!!)!!.kind)
        assertEquals(CardInputKind.Start, CapsuleCards.input(CardView.Chat(null))!!.kind)
    }

    @Test fun plainTitles() {
        assertEquals("第三条有没有坑?", ScreenCopy.plain("[[screen:s1]]\n第三条有没有坑?"))
        assertEquals(ScreenCopy.TAG, ScreenCopy.plain("[[screen:s1]]"))
        assertEquals("普通的一句话", ScreenCopy.plain("普通的一句话"))
    }
}
