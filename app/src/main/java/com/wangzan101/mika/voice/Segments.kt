package com.wangzan101.mika.voice

/**
 * 長押しすると、サービスが一定期間終了し、その後再開されてセグメントが結合されたことが認識されます。
 * 中国語と日本語の段落は直接接続されており、他のテキスト（英語など）の段落の間にはスペースが追加されます。純粋なロジックであり、システムには一切触れません。
 */
class Segments {
    private var done = ""
    private var pending = ""

    /** 現在の段落の中間結果。現時点では全文に戻ります。 */
    fun partial(text: String): String {
        pending = text.trim()
        return full()
    }

    /** 現在の段落が認識されており、[text] がこの段落の最後のテキストです。 */
    fun segmentDone(text: String) {
        done = join(done, text.trim())
        pending = ""
    }

    /** 完了した各セグメントと未完了のセグメントの中間結果。最終結果を待つことができない場合は、リリースしてから使用してください。 */
    fun full(): String = join(done, pending).trim()

    companion object {
        /** の末尾には (コード ポイントによる) 非常に多くの文字があり、認識サービスによって変更される可能性があり、インターフェイスは明るく表示されます。 */
        private const val TAIL = 4

        fun split(full: String): SpeechEvent.Partial {
            val n = full.codePointCount(0, full.length)
            if (n <= TAIL) return SpeechEvent.Partial("", full)
            val cut = full.offsetByCodePoints(0, n - TAIL)
            return SpeechEvent.Partial(full.substring(0, cut), full.substring(cut))
        }

        private fun join(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            noSpace(a.codePointBefore(a.length)) || noSpace(b.codePointAt(0)) || b.codePointAt(0).toChar() in ATTACHED -> a + b
            else -> "$a $b"
        }

        /** 句読点は前のテキストの直後にあり、前にスペースはありません。 */
        private const val ATTACHED = ",.!?;:)"

        /** 自体が空白であるか、単語 (漢字、かな、全角句読点) を区切るのにスペースを使用しないテキストに属します。 */
        private fun noSpace(cp: Int): Boolean {
            if (Character.isWhitespace(cp)) return true
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                else -> {}
            }
            val block = Character.UnicodeBlock.of(cp)
            return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
    }
}
