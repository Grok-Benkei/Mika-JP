package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** 「バックステージ画面」ページの並べ替えとテキスト (インターフェイスや実際のタスクには影響しません)。 */
class ScreensCopyTest {
    @get:Rule val texts = ChineseTexts()

    private fun row(displayId: Int, key: String = "k$displayId", foreground: String? = null, web: Boolean = false) =
        ScreenRow(key, "c$displayId", "做点什么", displayId, TaskState.Running(0, "打开设置"), foreground, web)

    /** 画面が構築されているものが前にあります（画面作成の順序に従って）。画面がまだ構築されていないもの (-1) は、相互の元の順序を維持して後ろにリストされます。 */
    @Test fun screensAreOrderedWithUnbuiltOnesLast() {
        val rows = listOf(row(-1, "a"), row(15), row(-1, "b"), row(12))
        assertEquals(listOf("k12", "k15", "a", "b"), orderedScreens(rows).map { it.key })
        assertEquals(emptyList<ScreenRow>(), orderedScreens(emptyList()))
    }

    /** の 2 行目は、使用しているアプリを示します。開発に使用された画面番号、ピクセル、密度などは含まれません。 */
    @Test fun placeSaysWhichAppNotDisplayInternals() {
        assertEquals("微信 を使用中", ScreensCopy.place(row(12, foreground = "微信")))
        assertEquals("ブラウザを使用中", ScreensCopy.place(row(-1, web = true)))
        assertEquals("まだアプリを開いていません", ScreensCopy.place(row(-1)))
        assertEquals("今開いているアプリを確認できません", ScreensCopy.place(row(12)))
        listOf(row(12, foreground = "微信"), row(12), row(-1)).map(ScreensCopy::place).forEach {
            assertFalse(it, it.contains("12") || it.contains("dpi") || it.contains("×"))
        }
    }

    /** どの州にも言い伝えがあります。待っている人、何かをしてほしい人、そして実際に働いている人を分けることができます。 */
    @Test fun everyStateHasALabel() {
        assertEquals("実行中", ScreensCopy.state(TaskState.Running(1, "点了一下")))
        assertEquals("タスクが完了しました", ScreensCopy.state(TaskState.Succeeded("好了")))
        assertEquals("タスクが完了しませんでした", ScreensCopy.state(TaskState.Failed("出错了", FailKind.Model)))
        assertEquals("タスクを停止しました", ScreensCopy.state(TaskState.Cancelled))
        val needsYou = listOf(
            TaskState.WaitingInput("密码是什么"),
            TaskState.WaitingTakeover("payment", "要你付款"),
            TaskState.Held(GateReason("waiting_user", "在系统的选择框里选一个", needsYou = true)),
        )
        assertEquals(listOf("あなたの対応が必要", "あなたの対応が必要", "あなたの対応が必要"), needsYou.map(ScreensCopy::state))
        // 状況（ロック画面、アシスタントが接続されていない）を待っているだけでは、「処理を依頼した」とはみなされません。
        assertEquals("待機中", ScreensCopy.state(TaskState.Held(GateReason("locked", "锁屏了"))))
        assertEquals("待機中", ScreensCopy.state(TaskState.Idle))
    }
}
