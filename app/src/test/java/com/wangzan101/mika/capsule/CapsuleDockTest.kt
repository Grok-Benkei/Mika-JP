package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleDockTest {
    // 1000×2000 画面、ステータス バー 80、下部ジェスチャ バー 40。可視カプセル距離 30、上下 20、シャドウ エッジ 10
    private val area = DockArea.of(OverlayArea(1000, 2000, insetTop = 80, insetBottom = 40), margin = 30, gap = 20, room = 10)

    @Test fun areaAvoidsBarsAndKeepsMargins() {
        assertEquals(DockArea(1000, left = 20, top = 90, right = 980, bottom = 1950, edgeLeft = -10, edgeRight = 1010), area)
    }

    @Test fun fractionAndYRoundTrip() {
        assertEquals(90, CapsuleDocking.y(0f, area, 100))
        assertEquals(1850, CapsuleDocking.y(1f, area, 100))
        assertEquals(970, CapsuleDocking.y(0.5f, area, 100))
        assertEquals(0.5f, CapsuleDocking.fraction(970, area, 100), 0.001f)
        // クランプ範囲外です
        assertEquals(0f, CapsuleDocking.fraction(-500, area, 100))
        assertEquals(1f, CapsuleDocking.fraction(5000, area, 100))
        assertEquals(90, CapsuleDocking.y(-3f, area, 100))
    }

    @Test fun sameFractionAfterRotation() {
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val f = CapsuleDocking.fraction(CapsuleDocking.y(0.25f, area, 100), area, 100)
        val y = CapsuleDocking.y(f, landscape, 100)
        // 横画面：上50、下990、範囲1/4の840
        assertEquals(50 + 210, y)
        // は左側の前髪: 左側にドッキングするときに邪魔にならないようにします
        assertEquals(100, CapsuleDocking.dockedX(DockSide.Left, landscape, 200))
        assertEquals(1780, CapsuleDocking.dockedX(DockSide.Right, landscape, 200))
    }

    @Test fun tallerWindowMovesUpToFit() {
        // カードは高さ 600 メートルなので、カプセルの高さより下に置くことはできません。
        assertEquals(1950 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), area, 600))
        assertEquals(500, CapsuleDocking.fitY(500, area, 600))
        // 全範囲より高い：トップに近い
        assertEquals(90, CapsuleDocking.fitY(500, area, 5000))
    }

    @Test fun dragStopsAtTheScreenEdge() {
        assertEquals(-10, CapsuleDocking.dragX(-300, area, 200))
        assertEquals(1010 - 200, CapsuleDocking.dragX(5000, area, 200))
        assertEquals(400, CapsuleDocking.dragX(400, area, 200))
    }

    @Test fun releaseSnapsToTheNearestSide() {
        assertEquals(CapsuleDock(DockSide.Left, 0f), CapsuleDocking.release(250, 90, 200, 100, area))
        assertEquals(DockSide.Right, CapsuleDocking.release(450, 500, 200, 100, area).side)
        assertEquals(DockSide.Left, CapsuleDocking.release(380, 500, 200, 100, area).side)
        assertEquals(20, CapsuleDocking.dockedX(DockSide.Left, area, 200))
        assertEquals(780, CapsuleDocking.dockedX(DockSide.Right, area, 200))
        // 前髪左側の場合、左右の幅が異なります：左100、右20
        val lopsided = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(100, CapsuleDocking.edgeOffset(DockSide.Left, lopsided))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Right, lopsided))
    }

    @Test fun keyboardPushesTheRangeUp() {
        // 入力メソッドは 1200 です: 表示部分が 20 を区切ります
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190, up.bottom)
        assertEquals(1190 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), up, 600))
        assertEquals(area, area.above(null, 20, 10))
        // 入力方法がスイング範囲より高い: 上限を超えない
        assertEquals(area.top, area.above(10, 20, 10).bottom)
    }

    @Test fun keyboardDragSavesTheReleaseHeightInTheFullArea() {
        val up = area.above(1200, gap = 20, room = 10)
        val releaseY = 900
        val dock = CapsuleDocking.release(450, releaseY, 200, 100, up, fractionArea = area)

        // 完全な領域に対して保存した比率から、アニメーションの有無にかかわらず離した高さへ戻ることを確認します。
        assertEquals((releaseY - area.top) / (area.bottom - area.top - 100).toFloat(), dock.fraction, 0.0001f)
        assertEquals(releaseY, CapsuleDocking.y(dock.fraction, area, 100))
        assertEquals(releaseY, CapsuleDocking.fitY(CapsuleDocking.y(dock.fraction, area, 100), up, 100))
        // キーボードを閉じた後も同じ高さへ戻ります。
        assertEquals(releaseY, CapsuleDocking.fitY(CapsuleDocking.y(dock.fraction, area, 100), area, 100))

        // 画面回転後も完全な領域の比率を使い、新しい向きのキーボード範囲へ収めます。
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val landscapeUp = landscape.above(600, gap = 20, room = 10)
        val rotatedY = CapsuleDocking.y(dock.fraction, landscape, 100)
        assertEquals(rotatedY, CapsuleDocking.fitY(rotatedY, landscapeUp, 100))
    }

    // ---------- カード ----------

    /** ウィンドウの高さの上下のシャドウ エッジを含む最も高いカード (表示高さ 1160)。 */
    private val full = 1160 + 20

    @Test fun cardCapIsAShareOfTheScreen() {
        // 2000 高画面: 最大 58%、収まります (会話カードはこの高さまで、タスク カードはこの高さまで)
        assertEquals(1160, CardDocking.maxHeight(2000, area, room = 10))
        // 入力方法ポップアップ (上位 1200): 受信後、中央のセクションが短くなります
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 90 - 20, CardDocking.maxHeight(2000, up, room = 10))
        // 横画面：横画面の高さに応じて計算
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(580, CardDocking.maxHeight(1000, landscape, room = 10))
    }

    @Test fun cardTopFollowsItsFraction() {
        // 上部は 90..(1950 - 最も高いカード) の間に比例して配置されます: 最も高いカードは中央、上部、下部にあります
        assertEquals(90 + 340, CardDocking.y(0.5f, area, area, full, full))
        assertEquals(90, CardDocking.y(0f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.y(1f, area, area, full, full))
        // 画面回転後も同じ比率
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(50 + 170, CardDocking.y(0.5f, landscape, landscape, 600, 600))
    }

    @Test fun shorterTaskCardSharesTheTopWithTheChatCard() {
        // タスク カードの高さは内容によれば 500 のみです。上部は同じ比率のダイアログ カードと同じで、下部は短くなります。
        for (f in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(CardDocking.y(f, area, area, full, full), CardDocking.y(f, area, area, full, 500))
        }
        // 従来、固定高カード収録率は、会話カードを元の位置に戻す上記の比率として読み込まれていました。
        assertEquals(CapsuleDocking.y(0.7f, area, full), CardDocking.y(0.7f, area, area, full, full))
    }

    @Test fun switchingCardsKeepsTheTopAndShiftsUpOnlyWhenItOverflows() {
        val top = CardDocking.y(1f, area, area, full, 500)
        // タスク カードはダイアログ カードに置き換えられます。下に置くことができ、上部は動かず、下に向かって成長します。
        assertEquals(top, CardDocking.y(1f, area, area, full, full))
        // 入力方法ポップアップ (上位 1200): 短いカードは移動せずに下に置くことができ、ダイアログ カード (1100 + シャドウ エッジに短縮) は下に置けるまで上に移動されます。
        val up = area.above(1200, gap = 20, room = 10)
        val shortTop = CardDocking.y(0f, area, up, full, 500)
        assertEquals(90, shortTop)
        assertEquals(90 + 204, CardDocking.y(0.3f, area, up, full, 500))
        assertEquals(1190 - 700, CardDocking.y(1f, area, up, full, 700))
        assertEquals(90, CardDocking.y(0.5f, area, up, full, 1100))
    }

    @Test fun cardDragStaysOnScreenAndRemembersTheTopFraction() {
        assertEquals(90, CardDocking.dragY(430, -1000f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, full))
        assertEquals(530, CardDocking.dragY(430, 100f, area, area, full, full))
        // 短いタスク カードを下までドラッグします。ダイアログ カードも配置できる上部で停止します。記録された比率は範囲を超えません。
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, 500))
        assertEquals(1f, CardDocking.release(1950 - full, area, full))
        assertEquals(0.5f, CardDocking.release(430, area, full), 0.001f)
        // 入力メソッドの範囲を押して注意してください。入力メソッドは折りたたまれた後も同じ場所にあり、別の高さに移動しても同じことが当てはまります。
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, full))
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, 500))
        // 入力メソッドがポップアップ表示されたときにドラッグすると、そこからページ全体を削除できません
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 500, CardDocking.dragY(430, 2000f, area, up, full, 500))
    }
}
