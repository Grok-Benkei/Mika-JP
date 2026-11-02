package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** は、サービス プロバイダーのテキストツーテキスト インターフェイスにアップロードできるように、16 ビット モノラル PCM を標準 WAV (44 バイト ヘッダー + リトル エンディアン データ) にパッケージ化します。 */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // fmt ブロック長
        buf.putShort(1)             // PCM
        buf.putShort(1)             // モノラル
        buf.putInt(rate)
        buf.putInt(rate * 2)        // 1 秒あたりのバイト数 = サンプル レート × チャネル × 2 バイト
        buf.putShort(2)             // ブロックの位置合わせ
        buf.putShort(16)            // ビット深度
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /** すべてゼロのサイレント WAV。特定のモデルがテキストを変換できるかどうかをテストするために使用されます (実際の録音は必要ありません)。 */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
