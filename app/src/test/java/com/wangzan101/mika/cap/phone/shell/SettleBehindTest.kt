package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.SettleBehind.Plan
import com.wangzan101.mika.cap.phone.shell.SettleBehind.Seen
import org.junit.Assert.assertEquals
import org.junit.Test

class SettleBehindTest {
    private fun behind(id: Int) = Seen(id, onMain = true, visible = false)
    private fun front(id: Int) = Seen(id, onMain = true, visible = true)

    @Test fun nothingMovedIsDoneAtOnce() {
        assertEquals(Plan.Done(emptySet(), emptySet(), emptySet()), SettleBehind.plan(emptySet(), emptyList(), 0, null))
    }

    @Test fun allBehindIsKept() {
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 0, null))
    }

    @Test fun visibleDuringTheTransitionWaitsInsteadOfFailing() {
        // 初めて見たのでまたランク付けしてみました。
        assertEquals(Plan.Wait(setOf(1), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 0, null))
        // はキューに入れられたばかりです: 継続的にキューに入れられるわけではなく、ただ待つだけです
        assertEquals(Plan.Wait(emptySet(), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 300, 300))
        // 十分に分離してから配置してください
        assertEquals(Plan.Wait(setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), 900, SettleBehind.REORDER_EVERY_MS))
        // 決済済み: 滞在
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 1_200, 300))
    }

    @Test fun stillVisibleAtTheDeadlineTakesTheFallback() {
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), SettleBehind.TIMEOUT_MS, 100))
        assertEquals(Plan.Done(emptySet(), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), SettleBehind.TIMEOUT_MS + 500, null))
    }

    @Test fun closeOutRechecksUnderTheLock() {
        // タイムアウト後も表示されているものは、次のとおりです。もう一方は残ります。
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(front(1), behind(2))))
        // タイムアウトが解決されました: そのまま
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(behind(1), behind(2))))
        // は解決されており、現時点で表示されているのはユーザーがクリックしたものです: そのままにしておいてください、持ち去らないでください
        assertEquals(Plan.Done(setOf(2), emptySet(), emptySet()), SettleBehind.closeOut(setOf(2), emptySet(), listOf(front(2))))
        // 待機中に、他のタスクが画面に表示されるかオフになります。これは、滞在または撤退としてカウントされません。
        assertEquals(Plan.Done(emptySet(), emptySet(), setOf(1, 2)),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(Seen(1, onMain = false, visible = true))))
    }

    @Test fun tasksNoLongerOnMainAreDroppedNotKept() {
        // は他のタスクによって別の画面に移動されたか、オフになっています (リストされていません)
        val elsewhere = Seen(3, onMain = false, visible = true)
        assertEquals(Plan.Wait(setOf(1), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(front(1), elsewhere), 0, null))
        assertEquals(Plan.Done(setOf(1), emptySet(), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(behind(1), elsewhere), 0, null))
        // タイムアウトが発生したときにホーム画面にいない場合は後退できません
        assertEquals(Plan.Done(emptySet(), setOf(1), setOf(3)),
            SettleBehind.plan(setOf(1, 3), listOf(front(1), elsewhere), SettleBehind.TIMEOUT_MS, null))
    }
}
