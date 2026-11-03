package com.wangzan101.mika.selfcheck

import java.util.Locale

/**
 * セルフテスト ページ上のテキストを認識するためにセルフテストで使用される比較方法。スクリーン リーダーによって提供されるテキストは、ページに書かれているものとまったく同じではない場合があります。デフォルトでは、ボタンはシステム テーマによってすべて大文字で表示されます。
 * 画面の読み取りでは大文字が読み取られます。スペースが 1 つ増えたり、1 つ減ったり、または連続したスペースが存在する場合があります。したがって、まず両側をまっすぐにしてから比較してください。
 * 大文字と小文字に関係なく、先頭と末尾の空白が削除され、中央の空白が通常のスペースに結合されます。
 */
internal object SelfCheckText {
    private val SPACES = Regex("[\\s\\p{Z}]+")

    fun normalize(s: String): String = s.replace(SPACES, " ").trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    fun contains(text: String, part: String): Boolean = normalize(text).contains(normalize(part))

    /**
     * 数字を含むテキスト (「クリック %d 回」、「時間 %d」、「行 %d」)。 [template] は、数字を [sentinel] に置き換えた後の文全体です。
     * を識別する場合、上記の比較方法に従って番号の前後 2 つのセクションが比較されます。真ん中には数字しか入れられません。
     */
    class Numbered(template: String, sentinel: String) {
        private val parts = normalize(template).split(normalize(sentinel))

        fun matches(s: String): Boolean {
            if (parts.size != 2) return false
            val (head, tail) = parts
            val n = normalize(s)
            if (n.length <= head.length + tail.length || !n.startsWith(head) || !n.endsWith(tail)) return false
            return n.substring(head.length, n.length - tail.length).all(Char::isDigit)
        }
    }
}
