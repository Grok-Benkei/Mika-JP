package com.wangzan101.mika.core.memory

import java.text.Normalizer

/**
 * このタスクでユーザー自身が発言した内容: 冒頭、間投詞、およびその後の回答におけるユーザー自身の言葉 (画面、ブラウザ ページのページ説明、ミカの回答、引用された添付ファイルの行はカウントされません)
 * 氏自身の言葉を引用）、引継ぎ・引き継ぎ時の注意点。
 * 記憶される `quote` は、いずれかの文の部分文字列である必要があります (すべての半角を統一し、先頭と末尾の空白を削除して比較します)。画面、Web ページ、およびツールの結果上の単語はカウントされません。
 */
class UserUtterances {
    private val texts = mutableListOf<String>()

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    val all: List<String> @Synchronized get() = texts.toList()

    /** タスクが開始されるたびにクリアされます。前のタスクで発言した単語は今回はカウントされません。 */
    @Synchronized fun clear() = texts.clear()

    /**
     * [content] の特定の値は、[quote] または今回ユーザーが言ったこと、つまりモデルが中継されたときに画面からコピーされた値では見つかりません。
     * 特定の値 = 引用符内の内容、数字(漢数字、区切られた数字は一度アラビア数字の列に統一)、英語/3文字以上の数字、
     * 住所フラグメント (地名記号に最初の 2 文字を加えたもの (「張江路」や「宜品園」など)。2 文字のみを使用します。モデルは誤って拒否されないように追加の地区名を追加します)。
     * 最初の 2 つの用語と比較して、三人称ナレーション自体によって引き起こされる誤った拒否を避けるために、「ユーザー (その)」と「私 (その)」を削除します。
     *
     * 制限事項: 引用符のない中国語の名前とロゴ文字のない地名は認識できません (「王暁明に送信」)。このタイプは、プロンプトの単語と結果カードに対するユーザーのレビューによって異なります。
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> {
        val c = comparable(content)
        val said = (texts + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /**
     * 画面本文はモデルへ出さない。ユーザー自身の発話に含まれる断片だけを、画面上にあったものとして返す。
     * モデルやページ由来の文字列は受け取らない。長い発話でも最大 [MAX_SEEN] 件で、画面の周辺語は含めない。
     */
    @Synchronized fun seenOnScreen(screenText: String): List<String> {
        val hay = norm(screenText)
        if (hay.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (said in texts) {
            val piece = norm(said)
            if (piece.length < MIN_QUOTE) continue
            var i = 0
            while (i < piece.length && found.size < MAX_SEEN) {
                var best: String? = null
                val max = minOf(MAX_PHRASE, piece.length - i)
                for (len in max downTo MIN_QUOTE) {
                    val sub = piece.substring(i, i + len)
                    if (sub in hay && sub.any { it.isLetterOrDigit() || it.code > 127 }) {
                        best = sub
                        break
                    }
                }
                if (best == null) i++ else {
                    found += best
                    i += best.length
                }
            }
            if (found.size >= MAX_SEEN) break
        }
        return found.toList()
    }

    /** 2 単語未満の引用はカウントされません。どの文にも 1 つまたは 2 つの単語が含まれます。 */
    @Synchronized fun contains(quote: String): Boolean {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return false
        return texts.any { norm(it).contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        const val MAX_PHRASE = 8
        const val MAX_SEEN = 8
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
    }
}
