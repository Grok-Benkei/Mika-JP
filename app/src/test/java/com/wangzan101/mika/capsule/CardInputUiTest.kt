package com.wangzan101.mika.capsule

import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** カプセルカード入力ボックス: 折りたたんだときに送信されなかった単語を保持し、その後の単語をフォロ​​ーします。 */
class CardInputUiTest {
    private val screen = PendingScreen(CapturedScreen(null, UserScreen("", 0L, "com.android.settings", "设置")), null)

    @Test fun collapsingKeepsTheDraftButClosesTheFieldAndDropsTheScreen() {
        val ui = CardInputUi(open = true, draft = "帮我把这段", screen = screen).collapsed()
        assertEquals("帮我把这段", ui.draft)
        assertFalse(ui.open)
        assertNull(ui.screen)
    }

    @Test fun spokenTextFollowsTheDraft() {
        assertEquals("帮我把这段话发给小王", CardInputUi(draft = "帮我把这段话").withSpoken("发给小王").draft)
        assertEquals("send it to Tom", CardInputUi(draft = "send it").withSpoken("to Tom").draft)
        assertEquals("发给小王", CardInputUi().withSpoken("发给小王").draft)
    }

    @Test fun spokenTextKeepsTheAttachedScreen() {
        assertSame(screen, CardInputUi(draft = "", screen = screen).withSpoken("这个怎么回").screen)
    }
}
