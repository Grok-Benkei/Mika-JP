package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.CancellationException
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 任意のモデル (画面読み取り、スクリーンショット、レビュー、ビュー モデル、メイン モデルのチェッカー) に送信された画像は、次の方法でエンコードされます。
 * JPEG、長辺が [MAX_SIDE] 以下、品質 [QUALITY]。フル解像度の PNG は数千トークンと MB の費用がかかり、画面の読み取りはコントロール ツリーに依存します。
 * 画面上でレイアウトとテキストがはっきりと見える限り。どの画面を送信できないか (保護されたページ、支払いページ) は呼び出し側によって決定され、ここではコーディングのみが行われます。
 */
object ModelImage {
    const val MAX_SIDE = 1280
    const val QUALITY = 80

    /**
     * は、モデルに送信される画像にエンコードされます。 [ops] が空の場合、またはデコードできない場合は、そのまま送信されます（ファイルヘッダーにより mime を識別します）。サイズが読めるものであればご持参ください。
     * [screenshotId]: 画面が読み取られた時刻。画面イメージでない場合は、null になります。
     */
    fun part(img: ByteArray, ops: ImageOps?, screenshotId: Int? = null): Part.Image {
        val jpeg = ops?.let { o -> attempt { o.toJpeg(img, MAX_SIDE, QUALITY) } }
        val out = jpeg ?: img
        val size = ops?.let { o -> attempt { o.size(out) } } ?: pngSize(out)
        return Part.Image(
            Base64.getEncoder().encodeToString(out), if (jpeg != null) "image/jpeg" else mimeOf(img),
            size?.first ?: 0, size?.second ?: 0, screenshotId,
        )
    }

    /** は、[MAX_SIDE] ([ImageOps.toJpeg] と同じアルゴリズム) に従ってスケーリング後のサイズです。推定に使用されます。 */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val f = MAX_SIDE.toDouble() / max(width, height)
        if (f >= 1.0) return width to height
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun mimeOf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> "image/png"
    }

    /** 幅と高さは PNG の IHDR で直接読み取られます。 PNG でない場合は null です。 */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()) return null
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
