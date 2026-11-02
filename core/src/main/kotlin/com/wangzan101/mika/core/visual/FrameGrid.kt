package com.wangzan101.mika.core.visual

import kotlin.math.abs

/**
 * は、1 フレーム減少した輝度グリッド ([cols] × [rows]、行優先): 影響を判断するための状況証拠として、アクションの前後で 1 部コピーを取得します (主にインターフェイスのステータスに基づいて)。
 * はサンプリングにピクセル読み取り関数のみを使用し、Android クラスに依存しません (JVM でテストできます)。ポートは既製のビットマップをサンプリングし、画像をエンコードしません。
 */
class FrameGrid(val cols: Int, val rows: Int, val luma: IntArray) {
    init { require(luma.size == cols * rows) }

    companion object {
        private const val SAMPLES = 2

        /** は、[width]×[height] 画面をグリッドに分割し、各グリッドの 2×2 ポイントの平均輝度を取得します。 [pixel] は (x, y) の ARGB を返します。小さすぎる画面はヌルです。 */
        fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameGrid? {
            val cols = FrameTuning.GRID_COLS; val rows = FrameTuning.GRID_ROWS
            val cw = width / cols
            val ch = height / rows
            if (cw == 0 || ch == 0) return null
            val out = IntArray(cols * rows)
            for (gy in 0 until rows) for (gx in 0 until cols) {
                var sum = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    sum += luma(pixel(gx * cw + (2 * sx + 1) * cw / (2 * SAMPLES), gy * ch + (2 * sy + 1) * ch / (2 * SAMPLES)))
                }
                out[gy * cols + gx] = sum / (SAMPLES * SAMPLES)
            }
            return FrameGrid(cols, rows, out)
        }

        private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
    }
}

/** 2 フレーム間: 画面全体の変更後のグリッド比率は [whole]、対象領域の比率は [region] (どの領域も null) となります。 */
data class FrameChange(val whole: Double, val region: Double?) {
    /** いくつかのグリッドが明らかに変更されました (カーソルの点滅と 1 点のリップルはカウントされません)。 */
    val visible: Boolean get() = whole >= FrameTuning.FRAME_VISIBLE || (region ?: 0.0) >= FrameTuning.REGION_VISIBLE
    /** 画面領域全体が変更されました。 */
    val large: Boolean get() = whole >= FrameTuning.FRAME_LARGE
}

/** アクションの前後の 2 つのフレームの大まかな比較: 各フレームの明るさの差が [FrameTuning.CELL_DELTA] を超えています。これは、このフレームが変化したことを意味します。 */
object FrameDiff {
    /** [roi] はターゲット領域 (0 ～ 999 の正規化された [l,t,r,b]) です。 2 つのフレームのサイズが一致しない場合、または 1 つのフレームが欠落している場合、そのフレームは null になります。 */
    fun compare(before: FrameGrid?, after: FrameGrid?, roi: List<Int>? = null): FrameChange? {
        if (before == null || after == null || before.cols != after.cols || before.rows != after.rows) return null
        val cols = before.cols; val rows = before.rows
        var changed = 0
        var inRegion = 0; var regionChanged = 0
        val box = roi?.takeIf { it.size == 4 }?.let { r ->
            // 小さなコントロールがグリッドの隙間に落ちないように、グリッドの半分を外側に拡張します
            val l = (r[0] * cols / 1000.0 - 0.5).toInt().coerceIn(0, cols - 1)
            val t = (r[1] * rows / 1000.0 - 0.5).toInt().coerceIn(0, rows - 1)
            val rr = (r[2] * cols / 1000.0 + 0.5).toInt().coerceIn(l, cols - 1)
            val b = (r[3] * rows / 1000.0 + 0.5).toInt().coerceIn(t, rows - 1)
            intArrayOf(l, t, rr, b)
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            val d = abs(before.luma[i] - after.luma[i]) > FrameTuning.CELL_DELTA
            if (d) changed++
            if (box != null && x in box[0]..box[2] && y in box[1]..box[3]) {
                inRegion++
                if (d) regionChanged++
            }
        }
        return FrameChange(changed.toDouble() / (cols * rows), box?.let { regionChanged.toDouble() / inRegion.coerceAtLeast(1) })
    }
}
