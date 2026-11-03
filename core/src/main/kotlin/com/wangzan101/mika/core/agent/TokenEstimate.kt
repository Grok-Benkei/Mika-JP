package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import kotlin.math.ceil

/**
 * 未調整の単語セグメンターの概算値: 非中国語文字 / 4 + 中国語文字 × 0.75;画像のサイズがわかっている場合は、幅×高さ/[PIXELS_PER_TOKEN]を押します。そうでない場合は、1000として計算します。
 * は、圧縮するかどうかを決定し、コンテキスト バジェット内のトークンによって切り詰めるためにのみ使用されます。
 * 中国語、日本語、韓国語の句読点、全角文字、かな、ことわざ、漢字は0.75として計算されます。
 */
object TokenEstimate {
    private const val IMAGE_TOKENS = 1000
    /** 一般的なサービス プロバイダーの口径 (トークンあたり約 28×28 ピクセル、各社若干の違いがあります)。 */
    const val PIXELS_PER_TOKEN = 750.0

    /** [width]×[height] の写真にはおよそトークンがいくつありますか?サイズがわからない場合は、1000を押してください。 */
    fun image(width: Int, height: Int): Int = if (width > 0 && height > 0) ceil(width.toLong() * height / PIXELS_PER_TOKEN).toInt() else IMAGE_TOKENS

    fun of(messages: List<Message>): Int {
        var images = 0
        var text = 0.0
        for (m in messages) for (p in m.parts) when (p) {
            is Part.Image -> images += image(p.width, p.height)
            is Part.Text -> text += chars(p.text)
            is Part.Thinking -> text += chars(p.text)
            is Part.ToolCall -> text += chars(p.name) + chars(p.argsJson)
            is Part.ToolResult -> text += chars(p.content)
        }
        return images + ceil(text).toInt()
    }

    /**
     * 実際の使用量で大まかな推定値を調整する: 新しい「真/大まかな推定値」の比率を返します。 [previous] が null の場合、今度は比率を直接取得します。 それ以外の場合、
     * は前のスムージングとマージされます。結果は [MIN_SCALE]..[MAX_SCALE] に限定されます。異常な使用法によって推定が大きく偏ることはありません。
     */
    fun calibrated(previous: Double?, estimated: Int, actual: Int): Double {
        if (estimated <= 0 || actual <= 0) return previous ?: 1.0
        val ratio = actual.toDouble() / estimated
        val next = if (previous == null) ratio else previous * (1 - SMOOTHING) + ratio * SMOOTHING
        return next.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 3.0
    private const val SMOOTHING = 0.3

    /** は、メッセージに含まれない固定オーバーヘッド (基本的なプロンプト ワード、ツール定義) に使用される同じ口径のテキストを推定します。 */
    fun of(text: String): Int = ceil(chars(text)).toInt()

    /** は [maxTokens] トークン以下の最長のプレフィックスで、最後に [hint] が続きます。コンテキストバジェットでのみ使用されます。 */
    fun clip(text: String, maxTokens: Int, hint: String = "…"): String {
        if (maxTokens <= 0 || text.isEmpty()) return ""
        if (of(text) <= maxTokens) return text
        val room = maxTokens - of(hint)
        if (room <= 0) return hint
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (of(text.substring(0, mid)) <= room) lo = mid else hi = mid - 1
        }
        // はエージェント ペアを半分に分割しません
        if (lo in 1 until text.length && text[lo - 1].isHighSurrogate() && text[lo].isLowSurrogate()) lo--
        return text.substring(0, lo) + hint
    }

    private val WIDE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )
    private val WIDE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    private fun wide(cp: Int) = Character.UnicodeScript.of(cp) in WIDE_SCRIPTS || Character.UnicodeBlock.of(cp) in WIDE_BLOCKS

    private fun chars(s: String): Double {
        var han = 0
        var other = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (wide(cp)) han++ else other++
            i += Character.charCount(cp)
        }
        return han * 0.75 + other / 4.0
    }
}
