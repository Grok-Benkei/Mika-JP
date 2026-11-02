package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入方法: ボタンを押して、システムが誰に投票するかを決定するまで待ちます。投票で除外されなかった場合は、失敗が報告されます (呼び出し元は戻ります)。タッチはまだ待ちません。
 * プレス/リフトのペア: プレスが成功しない場合は戻り、プレスが成功した場合のみリフトします。
 */
class InjectModeTest {
    // InputManager.INJECT_INPUT_EVENT_MODE_*
    private val async = 0
    private val waitForResult = 1
    private val waitForFinish = 2

    @Test fun keysWaitForTheDispatchResult() {
        assertEquals(waitForResult, ShellService.injectMode(key = true, waitForFinish = false))
    }

    @Test fun touchesStayAsync() {
        assertEquals(async, ShellService.injectMode(key = false, waitForFinish = false))
    }

    @Test fun explicitWaitForFinishWins() {
        assertEquals(waitForFinish, ShellService.injectMode(key = true, waitForFinish = true))
        assertEquals(waitForFinish, ShellService.injectMode(key = false, waitForFinish = true))
    }

    @Test fun undeliveredDownFallsBack() {
        var ups = 0
        var fallbacks = 0
        assertTrue(ShellService.sendPair({ false }, { ups++; true }, { fallbacks++; true }))
        assertEquals(1, fallbacks)
        assertEquals(0, ups)
        assertFalse(ShellService.sendPair({ false }, { true }, { false }))
    }

    @Test fun deliveredDownNeverFallsBackAndRetriesUpOnce() {
        var ups = 0
        var fallbacks = 0
        assertFalse(ShellService.sendPair({ true }, { ups++; false }, { fallbacks++; true }))
        assertEquals(2, ups)
        assertEquals(0, fallbacks)
    }
}
