package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** サイドバーの長押しメニューの配置: 下部に配置できる場合は下部に配置します。置けない場合は上に置きます。配置できない場合は、その行を上に移動します。使用可能な範囲は 100 ～ 1000、行の高さは 50、メニューは 150、間隔は 8。 */
class ConversationMenuPlacementTest {
    private fun place(rowTop: Float) = placeMenu(rowTop, rowHeight = 50f, menuHeight = 150f, gap = 8f, top = 100f, bottom = 1000f)

    @Test fun roomBelowPutsTheMenuBelow() {
        assertEquals(MenuPlacement(rowTop = 300f, menuTop = 358f, above = false), place(300f))
        // に適合: 792 + 50 + 8 + 150 = 1000
        assertEquals(MenuPlacement(792f, 850f, above = false), place(792f))
    }

    @Test fun nearTheBottomPutsTheMenuAbove() {
        assertEquals(MenuPlacement(rowTop = 800f, menuTop = 642f, above = true), place(800f))
        assertEquals(MenuPlacement(940f, 782f, above = true), place(940f))
    }

    @Test fun aRowPartlyOffTheUsableAreaIsMovedIn() {
        // ステータス バーの一番下の行までスクロールし、使用可能な範囲の一番上に移動します。メニューは下に接続されています。
        assertEquals(MenuPlacement(100f, 158f, above = false), place(60f))
        // キーボードによってブロックされた行が使用可能な範囲の下端の上に移動され、メニューが上に配置されます
        assertEquals(MenuPlacement(950f, 792f, above = true), place(990f))
    }

    @Test fun noRoomEitherSideMovesTheRowUp() {
        // には 100..400 しか残っていません (キーボードが画面の半分を占めています): 上下が収まらないため、行を 400-150-8-50 = 192 に移動します。メニューは以下になります。
        assertEquals(MenuPlacement(192f, 250f, above = false), placeMenu(200f, 50f, 150f, 8f, top = 100f, bottom = 400f))
        // どんなに小さくても上端を超えません
        assertEquals(MenuPlacement(100f, 158f, above = false), placeMenu(150f, 50f, 150f, 8f, top = 100f, bottom = 250f))
    }
}
