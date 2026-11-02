package com.wangzan101.mika.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.wangzan101.mika.core.vision.ImageOps
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** はシステム ビットマップを使用して画像処理を実装します。変更された画像は (詳細を失うことなく) PNG として出力されますが、[toJpeg] のみが JPEG を出力します。 */
object BitmapImageOps : ImageOps {
    /** よりも低い明るさのピクセルは黒とみなされます。 */
    private const val BLACK_LUMA = 16
    private const val BLACK_SHARE = 0.98
    /** は真っ黒と判定された場合、最大でも数点しか描画できません。画面全体を 1 点ずつ表示するには遅すぎます。 */
    private const val SAMPLE_SIDE = 64

    private fun decode(img: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IllegalArgumentException("not a decodable image")

    private inline fun <T> use(img: ByteArray, block: (Bitmap) -> T): T {
        val bmp = decode(img)
        try { return block(bmp) } finally { bmp.recycle() }
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    /** はエンコード後にリサイクルされます。結果は元の画像になる可能性があり (サイズが変更されていない場合、システムは直接元の画像に戻ります)、リサイクルを繰り返しても無害です。 */
    private fun Bitmap.png(): ByteArray = try { encode(Bitmap.CompressFormat.PNG, 100) } finally { recycle() }

    private fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt().coerceIn(0, size - 1)

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    override fun size(img: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img, 0, img.size, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "not a decodable image" }
        return o.outWidth to o.outHeight
    }

    /** は各ブロックの真の平均を取得します。1 ステップで 8×8 に縮小するのは単なるサンプリング ポイントであり、同じピクチャを 2 回行った場合の結果は異なる場合があります。画面全体のピクセルを一度に読み取るのではなく、行ごとに読み取ります。 */
    override fun aHash(img: ByteArray): Long = use(img) { bmp ->
        val w = bmp.width; val h = bmp.height
        val sum = LongArray(64); val count = IntArray(64)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val by = y * 8 / h * 8
            for (x in 0 until w) {
                val i = by + x * 8 / w
                sum[i] += luma(row[x]); count[i]++
            }
        }
        val gray = DoubleArray(64) { if (count[it] == 0) 0.0 else sum[it].toDouble() / count[it] }
        val mean = gray.sum() / 64.0
        gray.foldIndexed(0L) { i, acc, g -> if (g >= mean) acc or (1L shl i) else acc }
    }

    override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray = use(img) { bmp ->
        val f = maxSide.toDouble() / max(bmp.width, bmp.height)
        val out = if (f < 1.0) {
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true)
        } else bmp
        try { out.encode(Bitmap.CompressFormat.JPEG, quality) } finally { if (out !== bmp) out.recycle() }
    }

    override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val half = strokePx / 2f
        // ストロークは線の中心に配置されます。右側と下のピクセル グリッドは [r, r+1) であるため、ラインは r+1 に位置し、4 つの辺が同じ幅を持ち、すべてフレーム内に収まるようにする必要があります。
        val rect = RectF(
            toPx(box[0], out.width) - expandPx + half, toPx(box[1], out.height) - expandPx + half,
            toPx(box[2], out.width) + 1 + expandPx - half, toPx(box[3], out.height) + 1 + expandPx - half,
        )
        val paint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = strokePx.toFloat() }
        Canvas(out).drawRect(rect, paint)
        out.png()
    }

    override fun crop(img: ByteArray, box: IntArray): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val l = toPx(box[0], bmp.width); val t = toPx(box[1], bmp.height)
        val r = toPx(box[2], bmp.width); val b = toPx(box[3], bmp.height)
        require(r >= l && b >= t) { "empty box" }
        Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1).png()
    }

    override fun scale(img: ByteArray, factor: Double): ByteArray = use(img) { bmp ->
        require(factor > 0) { "factor must be positive" }
        Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * factor).roundToInt()), max(1, (bmp.height * factor).roundToInt()), true)
            .png()
    }

    override fun pixels(img: ByteArray): IntArray = use(img) { bmp ->
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    override fun isMostlyBlack(img: ByteArray): Boolean = use(img) { bmp ->
        val sx = max(1, bmp.width / SAMPLE_SIDE); val sy = max(1, bmp.height / SAMPLE_SIDE)
        var total = 0; var black = 0
        for (y in 0 until bmp.height step sy) for (x in 0 until bmp.width step sx) {
            total++
            if (luma(bmp.getPixel(x, y)) < BLACK_LUMA) black++
        }
        black >= total * BLACK_SHARE
    }
}
