package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * アプリによって描画されたシミュレートされたスクロール ホイールを使用して、[WheelDrags] を実行します。ドラッグすることのみ可能です。読み戻されるのは、選択した項目の上下の [radius] 項目です。選択した項目の名前にはステータスワードが含まれる場合があります。
 * は実機の時計を再現しており、時分歯車は24時間周期、選択項目は「Selected, 21」、上下は20と22となります。
 */
class WheelDragsTest {
    /**
     * [size] 値 (0 から開始)、[value] が選択されます。指を 1 つのグリッド上にドラッグします。 [rowPx] ピクセルは 1 つのグリッドを回転します (数字は大きくなり、[upIncreases] が false の場合は小さくなります)。
     * 最初の [deadDrags] 回ドラッグした後、応答がありません。 [label] は各項目の名前です (選択された項目の 2 番目のパラメーターは true です)。
     */
    private class Wheel(
        var value: Int,
        val size: Int,
        val wrap: Boolean,
        val radius: Int = 1,
        val rowPx: Int = 80,
        val upIncreases: Boolean = true,
        var deadDrags: Int = 0,
        val label: (Int, Boolean) -> String = { v, sel -> if (sel) "已选中，$v" else "$v" },
    ) {
        var drags = 0
        private fun visibleValues(): List<Pair<Int, Boolean>> = (-radius..radius).mapNotNull { o ->
            val v = value + o
            val shown = if (wrap) Math.floorMod(v, size) else v.takeIf { it in 0 until size }
            shown?.let { it to (o == 0) }
        }
        fun items() = visibleValues().map { (v, sel) -> label(v, sel) }
        fun selected() = visibleValues().indexOfFirst { it.second }
        /** によって読み取られた中心は、80 ピクセルの推定グリッド (実際のグリッドは [rowPx]) に従って配置されており、調整が簡単です。 */
        fun centers() = visibleValues().indices.map { 300 + it * 80 }
        fun drag(upPx: Int) {
            drags++
            if (deadDrags > 0) { deadDrags--; return }
            val n = (upPx.toDouble() / rowPx).roundToInt() * if (upIncreases) 1 else -1
            value = if (wrap) Math.floorMod(value + n, size) else (value + n).coerceIn(0, size - 1)
        }
    }

    /** は停止するまで実行され、最後のステップに戻り、初めていくつかのスペースをドラッグします。 */
    private fun run(w: Wheel, target: String, rowHeight: Int = 80): Pair<WheelDrags.Step, Int?> {
        val plan = WheelDrags(target, rowHeight)
        var dragged = 0
        var first: Int? = null
        repeat(60) {
            when (val s = plan.next(w.items(), w.selected(), w.centers(), dragged)) {
                is WheelDrags.Step.Drag -> {
                    if (first == null) first = s.steps
                    dragged = (s.steps * plan.itemHeight * s.boost).roundToInt()
                    w.drag(dragged)
                }
                else -> return s to first
            }
        }
        error("did not stop")
    }

    @Test fun hoursFrom21To7WithStatePrefixedLabel() {
        val w = Wheel(21, 24, wrap = true)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    @Test fun minutesFrom21To25() {
        val w = Wheel(21, 60, wrap = true)
        val (step, first) = run(w, "25")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(25, w.value)
        assertEquals(4, first)
    }

    @Test fun englishStatePrefixAndUnitSuffix() {
        val en = Wheel(21, 24, wrap = true, label = { v, sel -> if (sel) "Selected, $v" else "$v" })
        assertEquals(WheelDrags.Step.Done, run(en, "7").first)
        assertEquals(7, en.value)
        val unit = Wheel(21, 24, wrap = true, label = { v, _ -> "${v}点" })
        assertEquals(WheelDrags.Step.Done, run(unit, "7").first)
        assertEquals(7, unit.value)
    }

    /** では、23 → 0:21 → 7 が 14 ブロック後ではなく 10 ブロック前にあるときに、サイドが近づいてくるのがわかります。 */
    @Test fun wrapSeenTakesTheShortWayRound() {
        val w = Wheel(21, 24, wrap = true, radius = 3)
        val (step, first) = run(w, "7")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(10, first)
        assertEquals(7, w.value)
    }

    /** 初めてドラッグした後、応答がありません (ドラッグが短すぎてクリックとみなされます): これで終了とみなされず、さらにドラッグして再試行してください。 */
    @Test fun aDragWithNoEffectIsRetriedNotReportedAsTheEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 1)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    /** はどれだけドラッグしても移動できません。not_moving が報告され (モデルは自動的に描画されます)、at_end は報告されません。 */
    @Test fun wheelThatNeverMovesIsNotMovingNotAtEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 100)
        assertEquals(WheelDrags.Step.Stop("not_moving"), run(w, "7").first)
        assertEquals(3, w.drags)
        assertEquals(21, w.value)
    }

    /** 非循環スクロール ホイールが最後までドラッグされましたが、その方向には項目がありません。これで終了です。 */
    @Test fun nonWrappingWheelStopsAtItsEnd() {
        val w = Wheel(8, 11, wrap = false)
        assertEquals(WheelDrags.Step.Stop("at_end"), run(w, "15").first)
        assertEquals(10, w.value)
    }

    /** ホイールを上にドラッグして数値を減らします。最初の回転後に方向を修正します。 */
    @Test fun wheelWhoseUpDragDecreasesIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, upIncreases = false)
        assertEquals(WheelDrags.Step.Done, run(w, "25").first)
        assertEquals(25, w.value)
    }

    /** 1 つのグリッドが推定よりも高くなっています。回転した実際のグリッドの数に応じて調整し、所定の位置にドラッグします。 */
    @Test fun rowHeightIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, rowPx = 120)
        val (step, _) = run(w, "40", rowHeight = 80)
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(40, w.value)
        assertTrue(w.drags <= 4)
    }

    @Test fun valueNotOnTheWheelIsNotForced() {
        val w = Wheel(0, 60, wrap = true, radius = 2)
        val (step, _) = run(w, "75")
        assertTrue("$step", step is WheelDrags.Step.Stop)
    }
}
