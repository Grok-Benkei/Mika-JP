package com.wangzan101.mika.cap.phone.display

/** ROTATION_0/90/180/270 はそれぞれ 0/1/2/3 です。物理バッファーは変更されず、論理画面は 4 分の 1 回転で幅と高さが入れ替わります。 */
internal data class DisplayFrameGeometry(val bufferWidth: Int, val bufferHeight: Int, val rotation: Int) {
    private val sideways get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    /** 短辺に従ってプレビューを制限します: 水平画面に 1 つの低解像度画像だけが残らないように、水平画面と垂直画面で同じ量のピクセルを維持します。 */
    fun previewWidth(shortSide: Int): Int =
        if (width > height) maxOf(1, (width.toLong() * shortSide / height).toInt()) else shortSide

    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width)
        return w to maxOf(1, (height.toLong() * w / width).toInt())
    }
}
