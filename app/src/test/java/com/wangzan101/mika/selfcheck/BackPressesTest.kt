package com.wangzan101.mika.selfcheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** セルフテストのプレスが返されました: 再生中にインプット メソッドを押した回数は、インプット メソッドを閉じるためのものであり、回数には含まれません。合計回数には上限があります。 */
class BackPressesTest {
    /** 1 つの画面: 入力方法では、[imePresses] を押して閉じ (最初に画面上の単語を消去してから閉じます)、次に [pageLeavesAfter] を押してページを終了する必要があります。 */
    private class Screen(var imePresses: Int, val pageLeavesAfter: Int = 1) {
        var presses = 0
        private var pageBacks = 0
        var gone = false
        fun press() {
            presses++
            if (imePresses > 0) imePresses-- else if (++pageBacks >= pageLeavesAfter) gone = true
        }
    }

    private fun run(screen: Screen, tries: Int = 2, max: Int = 5) = runBlocking {
        BackPresses.until(tries, max, { screen.imePresses > 0 }, { screen.press() }, { screen.gone })
    }

    @Test fun keyboardThatTakesTwoPressesNoLongerFailsTheCheck() {
        // インプットメソッドが2回食べると失敗とみなされることが分かりました。
        val screen = Screen(imePresses = 2)
        assertTrue(run(screen))
        assertEquals(3, screen.presses)
    }

    @Test fun withoutKeyboardOnlyTheTriesCount() {
        val screen = Screen(imePresses = 0, pageLeavesAfter = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(2, screen.presses)
    }

    @Test fun keyboardThatNeverHidesStopsAtTheCap() {
        val screen = Screen(imePresses = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(5, screen.presses)
    }

    @Test fun firstPressLeavingIsEnough() {
        val screen = Screen(imePresses = 0)
        assertTrue(run(screen))
        assertEquals(1, screen.presses)
    }
}
