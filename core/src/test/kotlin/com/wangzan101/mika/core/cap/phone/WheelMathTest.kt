package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelMathTest {
    @Test fun numbersOnWheelItems() {
        assertEquals(9, WheelMath.number("09"))
        assertEquals(9, WheelMath.number("9时"))
        assertEquals(9, WheelMath.number(" 9 点 "))
        assertEquals(2026, WheelMath.number("2026年"))
        assertEquals(40, WheelMath.number("４０"))
        assertEquals(3, WheelMath.number("第3周"))
        assertNull(WheelMath.number("上午"))
        assertNull(WheelMath.number(""))
        assertNull(WheelMath.number(null))
    }

    @Test fun matchingNumbersIgnoresPaddingAndUnits() {
        assertTrue(WheelMath.matches("09", "9"))
        assertTrue(WheelMath.matches("9", "09"))
        assertTrue(WheelMath.matches("40分", "40"))
        assertTrue(WheelMath.matches("2026年", "2026"))
        assertFalse(WheelMath.matches("19", "9"))
        assertFalse(WheelMath.matches("4", "40"))
        assertFalse(WheelMath.matches(null, "9"))
    }

    /** 選択された項目のアクセシビリティ対応の名前には、多くの場合、ステータスの単語または単位が含まれます: 「選択、21」、「選択、21」、「21 (選択)」、「ブラックジャック」。 */
    @Test fun valueIsFoundInsideLabelsWithStateWords() {
        assertEquals(21, WheelMath.number("已选中，21"))
        assertEquals(21, WheelMath.number("Selected, 21"))
        assertEquals(21, WheelMath.number("21 (selected)"))
        assertEquals(21, WheelMath.number("21点"))
        assertEquals(9, WheelMath.number("已选中，09"))
        assertTrue(WheelMath.matches("已选中，21", "21"))
        assertTrue(WheelMath.matches("Selected, 21", "21"))
        assertTrue(WheelMath.matches("已选中，07", "7"))
        assertFalse(WheelMath.matches("已选中，21", "1"))
        assertTrue(WheelMath.matches("已选中，下午", "下午"))
        assertTrue(WheelMath.matches("下午 (selected)", "下午"))
        assertFalse(WheelMath.matches("已选中，上午", "下午"))
        // モデルに報告される値は値セクションのみを残します
        assertEquals("21", WheelMath.clean("已选中，21"))
        assertEquals("21", WheelMath.clean("Selected, 21"))
        assertEquals("21", WheelMath.clean("21 (selected)"))
        assertEquals("21点", WheelMath.clean("21点"))
        assertEquals("已选中，下午", WheelMath.clean("已选中，下午"))
        assertEquals(null, WheelMath.clean(null))
    }

    /** 実機の時計: 24 時間ホイールの時と分の両方が循環します。選択されている項目を「選択、21」といい、上下が20、22となります。 */
    @Test fun stepsFromASelectedLabelWithStatePrefix() {
        val near = listOf("20", "已选中，21", "22")
        // ループ時に数値の違いが見えません (14 スペース戻ると、ループのスクロール ホイールに到達できます)
        assertEquals(-14, WheelMath.stepsTo(near, 1, "7"))
        // 以前に 0 ～ 23 のサイクルを見たことがある、または 23 → 0 が見えます: 私が近づいている側、10 ブロック前方
        assertEquals(10, WheelMath.stepsTo(near, 1, "7", knownModulus = 24))
        assertEquals(10, WheelMath.stepsTo(listOf("18", "19", "20", "已选中，21", "22", "23", "0"), 3, "7"))
        // 分: 21 → 25 4 スペース前
        assertEquals(4, WheelMath.stepsTo(near, 1, "25"))
        assertEquals(1, WheelMath.stepsTo(listOf("Selected, 21", "22"), 0, "22"))
        assertEquals(-1, WheelMath.stepsTo(listOf("20点", "21点", "22点"), 1, "20"))
    }

    @Test fun selectedItemPrefersTheSelectedFlag() {
        val items = listOf(WheelMath.Item("20", 100, 100), WheelMath.Item("已选中，21", 100, 180, selected = true), WheelMath.Item("22", 100, 260))
        assertEquals(1, WheelMath.selectedIndex(items, 260, 80))
        // は選択済みとしてマークされていません: 中心線上
        assertEquals(2, WheelMath.selectedIndex(items.map { it.copy(selected = false) }, 260, 80))
    }

    /**
     * 元のデジタル ホイールは、上部と下部のグリッド (39、41) のみをレポートする場合があり、中央のグリッドはレポートされません。中心線に最も近い隣接グリッド (39) は選択できません。
     * は 40 で停止し、39 を読み取ります。各アイテムに高さがある場合、中心線をカバーするアイテムのみが認識されます。そうでない場合は、上下の項目を押して中央の線を押し出します。
     */
    @Test fun missingCenterItemIsNotReplacedByANeighbour() {
        val pair = listOf(WheelMath.Item("39", 100, 100, height = 120), WheelMath.Item("41", 100, 340, height = 120))
        assertEquals(-1, WheelMath.selectedIndex(pair, 220, 240))
        val three = listOf(pair[0], WheelMath.Item("40", 100, 220, height = 80), pair[1])
        assertEquals(1, WheelMath.selectedIndex(three, 220, 120))
        assertEquals("40", WheelMath.between("39", "41"))
        assertEquals("00", WheelMath.between("59", "01"))
        assertEquals("23", WheelMath.between("22", "0"))
        assertEquals("0", WheelMath.between("23", "1"))
        assertEquals("1", WheelMath.between("12", "2"))
        assertEquals("12", WheelMath.between("11", "1"))
        assertEquals("12", WheelMath.between("1", "11"))
        assertEquals("12点", WheelMath.between("11点", "1点"))
        assertEquals("0", WheelMath.between("1", "59"))
        assertEquals("09点", WheelMath.between("08点", "10点"))
        assertEquals(null, WheelMath.between("39", "40"))
        assertEquals(null, WheelMath.between("5", "30"))
        assertEquals(null, WheelMath.between("上午", "下午"))
    }

    @Test fun columnDropsTitlesAndButtonsAroundTheWheel() {
        val items = listOf(
            WheelMath.Item("小时", 100, 20),
            WheelMath.Item("20", 100, 300), WheelMath.Item("21", 100, 380), WheelMath.Item("22", 100, 460),
            WheelMath.Item("确定", 100, 900),
        )
        assertEquals(listOf("20", "21", "22"), WheelMath.column(items, 2).map { it.text })
        assertEquals(items.take(2), WheelMath.column(items.take(2), 1))
    }

    @Test fun matchingTextIsWholeOrAffix() {
        assertTrue(WheelMath.matches("北京市", "北京"))
        assertTrue(WheelMath.matches("PM", "pm"))
        assertTrue(WheelMath.matches("下午", "下午"))
        assertFalse(WheelMath.matches("上午", "下午"))
        assertFalse(WheelMath.matches("南京", "北京市"))
    }

    @Test fun wrapLengthFromVisibleItems() {
        assertEquals(60, WheelMath.modulus(listOf("58", "59", "00", "01", "02")))
        assertEquals(24, WheelMath.modulus(listOf("22", "23", "0", "1")))
        assertEquals(12, WheelMath.modulus(listOf("11", "12", "1", "2")))
        assertNull(WheelMath.modulus(listOf("07", "08", "09")))
        assertNull(WheelMath.modulus(listOf("上午", "下午")))
        assertNull(WheelMath.modulus(listOf("5")))
    }

    @Test fun distanceTakesTheShortWayRoundWhenItWraps() {
        assertEquals(40, WheelMath.distance(0, 40, null))
        assertEquals(-20, WheelMath.distance(0, 40, 60))
        assertEquals(2, WheelMath.distance(59, 1, 60))
        assertEquals(-3, WheelMath.distance(2, 23, 24))
        assertEquals(10, WheelMath.distance(2026, 2036, null))
        assertEquals(0, WheelMath.distance(7, 7, 60))
    }

    @Test fun movedAcrossTheWrapIsOneStep() {
        assertEquals(1, WheelMath.moved("59", "00", 60))
        assertEquals(-59, WheelMath.moved("59", "00", null))
        assertNull(WheelMath.moved("上午", "下午", null))
    }

    @Test fun itemHeightIsTheMedianGap() {
        assertEquals(100, WheelMath.itemHeight(listOf(300, 100, 200, 400)))
        assertEquals(100, WheelMath.itemHeight(listOf(100, 200, 300, 600)))
        assertNull(WheelMath.itemHeight(listOf(100)))
        assertNull(WheelMath.itemHeight(emptyList()))
    }

    @Test fun selectedIsTheItemOnTheCenterLine() {
        assertEquals(2, WheelMath.selected(listOf(100, 200, 300, 400, 500), 310, 100))
        // は中心線と一致していません (ローラー全体が他の場所にあります)
        assertEquals(-1, WheelMath.selected(listOf(100, 200), 900, 100))
        assertEquals(-1, WheelMath.selected(emptyList(), 0, null))
    }

    @Test fun stepsToVisibleTargetFollowsPosition() {
        val visible = listOf("上午", "下午")
        assertEquals(1, WheelMath.stepsTo(visible, 0, "下午"))
        assertEquals(-1, WheelMath.stepsTo(visible, 1, "上午"))
        // 番号: 番号の差ではなく、ターゲットは目の前の位置にあります (見える側にループします)
        assertEquals(2, WheelMath.stepsTo(listOf("07", "08", "09", "10", "11"), 2, "11"))
        assertEquals(31, WheelMath.stepsTo(listOf("07", "08", "09", "10", "11"), 2, "40"))
        assertEquals(-19, WheelMath.stepsTo(listOf("58", "59", "00", "01", "02"), 2, "41"))
        assertEquals(-31, WheelMath.stepsTo(listOf("07", "08", "09", "10", "11"), 2, "40", increasingForward = false))
        // テキストリスト、ターゲットは目の前にありません: わかりません
        assertNull(WheelMath.stepsTo(listOf("北京", "天津", "上海"), 1, "广州"))
        assertNull(WheelMath.stepsTo(listOf("07"), 5, "9"))
    }

    @Test fun dragsSplitIntoWheelSizedPieces() {
        assertEquals(listOf(300), WheelMath.drags(3, 100, 400))
        assertEquals(listOf(400, 400, 200), WheelMath.drags(10, 100, 450))
        assertEquals(listOf(-200), WheelMath.drags(-2, 100, 400))
        assertEquals(emptyList<Int>(), WheelMath.drags(0, 100, 400))
        // 一度に少なくとも 1 つのフレームをドラッグします
        assertEquals(listOf(100, 100), WheelMath.drags(2, 100, 50))
    }

    @Test fun calibrationFromWhatADragActuallyMoved() {
        assertEquals(120, WheelMath.calibrated(600, 5))
        assertEquals(120, WheelMath.calibrated(-600, -5))
        assertNull(WheelMath.calibrated(600, 0))
    }

    @Test fun wheelShapeIsShortCenteredEvenlySpacedValues() {
        fun items(vararg t: String, x: Int = 500, gap: Int = 100) = t.mapIndexed { i, s -> WheelMath.Item(s, x, 200 + i * gap) }
        assertTrue(WheelMath.looksLikeWheel(items("08", "09", "10", "11", "12"), 400, 600))
        assertTrue(WheelMath.looksLikeWheel(items("上午", "下午", "晚上"), 400, 600))
        // 左列の設定項目、テキストが長すぎる、繰り返している、少なすぎる、間隔がランダムであるなどはカウントされません。
        assertFalse(WheelMath.looksLikeWheel(items("Wi-Fi", "蓝牙", "显示", x = 120), 0, 1000))
        assertFalse(WheelMath.looksLikeWheel(items("今天下午三点开会别忘了", "好的", "收到"), 400, 600))
        assertFalse(WheelMath.looksLikeWheel(items("1", "1", "1"), 400, 600))
        assertFalse(WheelMath.looksLikeWheel(items("1", "2"), 400, 600))
        assertFalse(WheelMath.looksLikeWheel(listOf(WheelMath.Item("1", 500, 100), WheelMath.Item("2", 500, 200), WheelMath.Item("3", 500, 500)), 400, 600))
    }
}
