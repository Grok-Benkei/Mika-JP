package com.wangzan101.mika.core.tool

/** モデルへ返すサンドボックス・rootのコマンド出力。先頭と末尾を残し、省略した文字数を示します。 */
object OutputClip {
    fun clip(s: String, head: Int = 4000, tail: Int = 12000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        // サロゲートペアの途中では切らない。
        val h = if (head > 0 && s[head - 1].isHighSurrogate()) head - 1 else head
        var start = s.length - tail
        if (start < s.length && s[start].isLowSurrogate()) start++
        return "${s.substring(0, h)}\n…（${start - h}文字を省略）…\n${s.substring(start)}" to true
    }
}
