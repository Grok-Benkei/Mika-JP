package com.wangzan101.mika.core.vision

/**
 * 画像処理インターフェイス: 入力はエンコードされた画像バイト (スクリーンショット PNG など) であり、出力もエンコードされたバイトです。
 * フレーム座標 [box] は [左、上、右、下] で、0 ～ 999 で正規化され、画面読み取り要素の境界と同じ口径を持ちます。
 */
interface ImageOps {
    fun size(img: ByteArray): Pair<Int, Int>

    /** 8×8 グレースケール平均ハッシュ。 2 つのフレーム間の [hamming] 距離が小さいほど、それらは類似しています。 */
    fun aHash(img: ByteArray): Long

    /** はモデルに送信される前に圧縮されます。長辺は [maxSide] を超えません。 */
    fun toJpeg(img: ByteArray, maxSide: Int = 1280, quality: Int = 80): ByteArray

    /** は、[box]から[expandPx]の延長線上に赤枠を描きます。 */
    fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int = 3, strokePx: Int = 4): ByteArray

    fun crop(img: ByteArray, box: IntArray): ByteArray

    fun scale(img: ByteArray, factor: Double): ByteArray

    /** ピクセルごとの ARGB、行の優先順位。 */
    fun pixels(img: ByteArray): IntArray

    /** はほぼ完全に黒です。これは、保護されたページが切り取られたものです。 */
    fun isMostlyBlack(img: ByteArray): Boolean
}

fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
