package com.wangzan101.mika.cap.phone.display

import kotlin.math.abs

/**
 * の大まかな画面判定は、ピクセル読み取り機能のみを使用し、Android クラスに依存しません (JVM 上でテストできます)。
 * [pixel] (x, y) の ARGB カラー値を返します。
 */
object FrameMath {
    const val GRID_W = 12
    const val GRID_H = 24
    private const val CELL_SAMPLES = 4
    private const val CELL_DELTA = 16
    private const val MIN_CELLS = 3
    private const val PAGE_SHARE = 0.25
    private const val BLACK_SAMPLES = 24
    private const val BLACK_LEVEL = 8

    /** 画面を [GRID_W]×[GRID_H] のグリッドに切り、各グリッドの 4×4 ポイントの平均輝度を取得します。 */
    fun lumaGrid(width: Int, height: Int, pixel: (Int, Int) -> Int): IntArray {
        val out = IntArray(GRID_W * GRID_H)
        val cw = width / GRID_W
        val ch = height / GRID_H
        if (cw == 0 || ch == 0) return out
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            var sum = 0L
            for (sy in 0 until CELL_SAMPLES) for (sx in 0 until CELL_SAMPLES) {
                sum += luma(pixel(gx * cw + sx * cw / CELL_SAMPLES, gy * ch + sy * ch / CELL_SAMPLES))
            }
            out[gy * GRID_W + gx] = (sum / (CELL_SAMPLES * CELL_SAMPLES)).toInt()
        }
        return out
    }

    /** 十分なグリッドが明らかに明るくなったり暗くなったりした場合にのみ、画像が変化します。カーソルの点滅などの小さな変化はカウントされません。 */
    fun differs(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        return a.indices.count { abs(a[it] - b[it]) > CELL_DELTA } >= MIN_CELLS
    }

    /** 少なくとも [PAGE_SHARE] のグリッドが変更されました。アバター アニメーション、カーソル、プログレス サークルなどの局所的な変更ではなく、ページ全体が変更されました (または画面全体が移動します)。 */
    fun pageChanged(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        return a.indices.count { abs(a[it] - b[it]) > CELL_DELTA } >= a.size * PAGE_SHARE
    }

    /** 完全に黒とみなされるには、均一にサンプリングされたポイントの各チャネルの少なくとも 99.5% が [BLACK_LEVEL] 以下です。 仮想画面は、 */
    fun isBlack(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width <= 1 || height <= 1) return false
        var dark = 0
        val total = BLACK_SAMPLES * BLACK_SAMPLES
        for (gy in 0 until BLACK_SAMPLES) for (gx in 0 until BLACK_SAMPLES) {
            val p = pixel(gx * (width - 1) / (BLACK_SAMPLES - 1), gy * (height - 1) / (BLACK_SAMPLES - 1))
            if ((p shr 16 and 0xff) <= BLACK_LEVEL && (p shr 8 and 0xff) <= BLACK_LEVEL && (p and 0xff) <= BLACK_LEVEL) dark++
        }
        return dark * 1000 >= total * 995
    }

    private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
}
