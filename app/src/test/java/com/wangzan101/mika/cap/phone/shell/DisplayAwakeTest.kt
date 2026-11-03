package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * の自己完結型の仮想画面グループは決してスリープしません。画面がオンのときは、ユーザーの操作を時間どおりに報告し、画面が解放されると停止します。報告できない場合は、再試行しません。
 * は、画面を押して報告されたインターフェイスのみを認識し、デフォルト グループ (ユーザー ホーム画面) に報告された古いインターフェイスは認識しません。
 */
class DisplayAwakeTest {
    private val ownGroup = 1 shl 11

    /** 登録した定期タスクを書き留め、手動でテストして「ポイントに到達」します。 */
    private class Timer {
        val tasks = mutableListOf<Runnable>()
        val cancelled = mutableSetOf<Runnable>()
        fun repeat(r: Runnable): () -> Unit { tasks += r; return { cancelled += r } }
        fun tick() = tasks.toList().forEach { if (it !in cancelled) it.run() }
        val live get() = tasks.count { it !in cancelled }
    }

    @Test fun pokesEachDisplayUntilItIsReleased() {
        val timer = Timer()
        val poked = mutableListOf<Int>()
        val awake = DisplayAwake({ poked += it; true }, timer::repeat)
        awake.start(5)
        awake.start(7)
        timer.tick()
        timer.tick()
        assertEquals(listOf(5, 7, 5, 7), poked)
        awake.stop(5)
        poked.clear()
        timer.tick()
        assertEquals(listOf(7), poked)
        awake.stopAll()
        assertEquals(0, timer.live)
    }

    @Test fun startingTheSameDisplayAgainKeepsOnlyOneTimer() {
        val timer = Timer()
        val awake = DisplayAwake({ true }, timer::repeat)
        awake.start(5)
        awake.start(5)
        assertEquals(1, timer.live)
        awake.stop(5)
        assertEquals(0, timer.live)
    }

    @Test fun givesUpEverywhereOnceThePowerCallIsUnavailable() {
        val timer = Timer()
        var calls = 0
        val awake = DisplayAwake({ calls++; false }, timer::repeat)
        awake.start(5)
        awake.start(7)
        timer.tick()
        assertEquals(0, timer.live)
        // 以降に構築された画面は登録されなくなります。
        awake.start(9)
        assertEquals(0, timer.live)
        assertEquals(1, calls)
    }

    @Test fun onlyDisplaysInTheirOwnGroupAreKeptAwake() {
        assertTrue(DisplayAwake.needed(ownGroup or 1, ownGroup, groupId = 3))
        // 所属するグループを読み取れません: 画面設定時にマークに従ってカウントしてください
        assertTrue(DisplayAwake.needed(ownGroup, ownGroup, groupId = null))
        // には独自のグループがないか (信頼できない画面に戻る)、または実際にはデフォルト グループに属します。これを報告すると、ユーザーのホーム画面が永久的になります。
        assertFalse(DisplayAwake.needed(1, ownGroup, groupId = null))
        assertFalse(DisplayAwake.needed(ownGroup, ownGroup, groupId = 0))
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class OldPower { fun userActivity(time: Long, event: Int, flags: Int) {} }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class NewPower {
        fun userActivity(time: Long, event: Int, flags: Int) {}
        fun userActivity(displayId: Int, time: Long, event: Int, flags: Int) {}
    }

    @Test fun picksOnlyThePerDisplayUserActivity() {
        assertNull(DisplayAwake.userActivityMethod(OldPower::class.java))
        val m = DisplayAwake.userActivityMethod(NewPower::class.java)
        assertNotNull(m)
        m!!
        assertEquals(4, m.parameterCount)
        assertEquals(Int::class.javaPrimitiveType, m.parameterTypes[0])
    }
}
