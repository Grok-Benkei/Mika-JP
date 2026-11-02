package com.wangzan101.mika.core.cap.sandbox

/** モデルに与えられたコマンド出力: 開始 + 終了。省略された単語の数が中央に示されます。 */
object OutputClip {
    fun clip(s: String, head: Int = 4000, tail: Int = 12000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        // はエージェント ペアを半分に分割しません
        val h = if (head > 0 && s[head - 1].isHighSurrogate()) head - 1 else head
        var start = s.length - tail
        if (start < s.length && s[start].isLowSurrogate()) start++
        return "${s.substring(0, h)}\n…(${start - h} characters omitted)…\n${s.substring(start)}" to true
    }
}
