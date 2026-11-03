package com.wangzan101.mika.core.agent

import java.lang.Character.UnicodeScript

/**
 * 最終回答が日本語かどうかを判定する:文字の書記体系(Unicode script)を中心に判定し、短い中国語の文章は補助の文型でも見分ける。モデル呼び出しは行わない純粋関数。
 * 目標言語は常時日本語であり、ユーザーの入力言語(英語・中国語など)には従わない。
 *
 * 仮名を含む漢字の文は日本語とする。漢字だけの名前・住所・ラベルは長さだけでは中国語と決めず、明確な中国語の文型がある場合に判定する。ハングルは韓国語。
 * ラテン、キリル、アラビアなどはそれぞれ別の語族とする。数字、句読点、空白、Markdown記号は数えない。
 *
 * 書記体系間の公平性:中国語・日本語は空白で区切らないため1文字を1単位とし、それ以外は単語単位で数える
 * (同じ書記体系の文字の連続を1単語とする)。文字数で数えると英語1単語が漢字数文字分になってしまうため。
 * 小文字を含まないラテン／キリルの単語(GB、OS、型番などの略語・単位)は数えない。
 *
 * 回答で数えない部分:各種引用符内(「」『』“”"" 《》)、括弧内、バッククォート内のコード、URL、山括弧タグ。
 * これらは原文の引用であり保持すべきものとする。また、漢字の固有名詞・住所や識別子だけのセルは数えない。
 * 外国語の名前だけを列挙した回答を言語不一致と判定しないためである。
 *
 * 不一致と判定する条件:引用などを除いたいずれかの行・セルで、最頻語族が [MISMATCH_SHARE] 以上を占め、
 * かつその語族が日本語でないこと。日本語の長い導入で外国語の別段落を隠せないようにする。
 * 短い外国語の文章も対象とし、ユーザーの文の長さは条件にしない。
 */
internal object ReplyLanguage {
    /** 互換性のために残す(目標は常時日本語のためユーザーの長さは判定条件にしない)。 */
    const val MIN_USER_UNITS = 3
    const val MIN_ANSWER_UNITS = 1
    const val MISMATCH_SHARE = 0.6
    const val NAME_UNITS = 6

    /** 語族。[display] はモデルへの通知文に差し込む日本語名。 */
    data class Family(val display: String)

    val CHINESE = Family("中国語")
    val JAPANESE = Family("日本語")
    val KOREAN = Family("韓国語")

    /** 目標言語は常時日本語。 */
    val TARGET: Family = JAPANESE

    /** 回答 [answer] の最頻語族と目標語族 [target](常時日本語)。 */
    data class Mismatch(val answer: Family, val target: Family) {
        /** 互換用:旧コードの `user` 参照は目標語族を返す。 */
        val user: Family get() = target
    }

    /**
     * 回答本文が日本語でない場合に不一致を返す。判定できなければ(短い固有名詞のみ、引用・コードのみ等) null。
     * [user] は互換性のためだけに受け取り、目標判定には使わない。
     */
    @Suppress("UNUSED_PARAMETER")
    fun mismatch(user: String, answer: String): Mismatch? = mismatch(answer)

    /** 回答本文が日本語でない場合に不一致を返す。 */
    fun mismatch(answer: String): Mismatch? {
        for (cell in stripQuoted(answer).split(CELL)) {
            val a = units(cell, skipNames = true)
            val aTotal = a.values.sum()
            if (aTotal < MIN_ANSWER_UNITS) continue
            val (answerFamily, count) = a.maxBy { it.value }
            if (count >= aTotal * MISMATCH_SHARE && answerFamily != TARGET) return Mismatch(answerFamily, TARGET)
        }
        return null
    }

    /** 回答本文が日本語と判定できるか(不一致でないか)。短い固有名詞・識別子のみなど、言語を判定できない場合も true。 */
    fun isJapanese(answer: String): Boolean = mismatch(answer) == null

    /** テキスト [text] の語族別単位数(クラス説明参照)。[skipNames] のとき短い名前だけの行・セルは数えない。 */
    fun units(text: String, skipNames: Boolean): Map<Family, Int> {
        val out = LinkedHashMap<Family, Int>()
        for (cell in text.split(CELL)) {
            val runs = runsOf(cell).filter { it.units > 0 }
            if (skipNames && isName(cell, runs)) continue
            val kana = runs.filter { it.key == KANA }.sumOf { it.units }
            // 住所などは仮名が少なくても日本語。別の文・段落の中国語はセルを分けて判定する。
            val cjk = if (kana > 0) JAPANESE else CHINESE
            for (r in runs) {
                val family = when (r.key) {
                    HAN, KANA -> cjk
                    UnicodeScript.HANGUL.name -> KOREAN
                    else -> Family(scriptDisplay(r.key))
                }
                out[family] = (out[family] ?: 0) + r.units
            }
        }
        return out
    }

    /** 漢字だけの名称・住所・ラベルは長さや句読点で排除しない。明確な中国語の文型や外国語の文は判定対象に残す。 */
    private fun isName(cell: String, runs: List<Run>): Boolean {
        if (runs.isEmpty()) return true
        val plain = cell.trim().trimStart('-', '*', '#', ' ', '\t').trim()
        if (IDENTIFIER.matches(plain)) return true
        if (runs.any { it.key == HAN } && runs.all { it.key == HAN || it.key == UnicodeScript.LATIN.name } &&
            !CHINESE_PROSE.containsMatchIn(plain) &&
            Regex("[A-Za-z]+").findAll(plain).all { word -> word.value.any(Char::isUpperCase) }) return true
        if (runs.sumOf { it.units } > NAME_UNITS) return false
        if (runs.size != 1) {
            return runs.all { it.key == HAN || it.key == UnicodeScript.LATIN.name } &&
                !CHINESE_PROSE.containsMatchIn(plain) && !SENTENCE_PUNCTUATION.containsMatchIn(plain) &&
                Regex("[A-Za-z]+").findAll(plain).all { word -> word.value.any(Char::isUpperCase) }
        }
        return when (runs.single().key) {
            HAN -> !CHINESE_PROSE.containsMatchIn(plain)
            KANA -> true
            UnicodeScript.LATIN.name, UnicodeScript.CYRILLIC.name -> {
                // 単一の識別子・プロトコル値、製品名、地名などは翻訳しない。
                if (IDENTIFIER.matches(plain)) return true
                if (SENTENCE_PUNCTUATION.containsMatchIn(plain)) return false
                val words = plain.split(Regex("\\s+"))
                words.all { word -> word.firstOrNull()?.isUpperCase() == true || word.any(Char::isUpperCase) }
            }
            else -> !SENTENCE_PUNCTUATION.containsMatchIn(plain) && runs.single().units <= 2
        }
    }

    /** 書記体系名の日本語表示。未知の体系は体系名をそのまま使う(固有名として扱う)。 */
    private fun scriptDisplay(scriptName: String): String = when (scriptName) {
        "LATIN" -> "ラテン文字"
        "CYRILLIC" -> "キリル文字"
        "ARABIC" -> "アラビア文字"
        "GREEK" -> "ギリシャ文字"
        "HEBREW" -> "ヘブライ文字"
        "DEVANAGARI" -> "デーヴァナーガリー文字"
        "THAI" -> "タイ文字"
        else -> "${scriptName}文字"
    }

    /** 回答から引用符・括弧・バッククォート内、URL、タグを除く(括弧は入れ子でも内外とも除く)。 */
    fun stripQuoted(text: String): String {
        var s = FENCE.replace(text, " ")
        for (r in listOf(INLINE_CODE, URL, TAG, QUOTES, BLOCKQUOTE)) s = r.replace(s, " ")
        while (true) {
            val next = PARENS.replace(s, " ")
            if (next == s) return s
            s = next
        }
    }

    /** 同一書記体系の連続:[key] は書記体系(漢字と仮名は統合前に分けて記録)、[units] は単位数。 */
    private data class Run(val key: String, var units: Int)

    /**
     * セルを連続に切る:同じ書記体系の文字同士が空白、ハイフン、アポストロフィ、結合記号
     * (またはどの書記体系にも属さない文字)だけで隔てられている場合は同じ連続とする。
     * 漢字・仮名は1文字1単位、それ以外は1単語1単位とし、小文字を含まないラテン／キリル単語は数えない。
     */
    private fun runsOf(cell: String): List<Run> {
        val out = mutableListOf<Run>()
        var current: Run? = null
        val word = StringBuilder()
        fun endWord() {
            val r = current
            if (word.isNotEmpty() && r != null && countsAsWord(word)) r.units++
            word.clear()
        }
        var i = 0
        while (i < cell.length) {
            val cp = cell.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            // どの書記体系にも属さない文字(長音符「ー」など)は前後に従い、単独では数えない
            if (Character.isLetter(cp) && UnicodeScript.of(cp).let { it == UnicodeScript.COMMON || it == UnicodeScript.INHERITED }) continue
            if (Character.isLetter(cp)) {
                val key = keyOf(cp)
                if (current?.key != key) { endWord(); current = Run(key, 0).also { out += it } }
                if (key == HAN || key == KANA) current!!.units++ else word.appendCodePoint(cp)
            } else if (cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == '’'.code) {
                endWord()
            } else {
                endWord()
                current = null
            }
        }
        endWord()
        return out
    }

    private fun countsAsWord(word: CharSequence): Boolean {
        val script = UnicodeScript.of(Character.codePointAt(word, 0))
        if (script != UnicodeScript.LATIN && script != UnicodeScript.CYRILLIC) return true
        return word.codePoints().anyMatch(Character::isLowerCase)
    }

    private fun keyOf(cp: Int): String = when (val s = UnicodeScript.of(cp)) {
        UnicodeScript.HAN -> HAN
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> KANA
        else -> s.name
    }

    private const val HAN = "HAN"
    private const val KANA = "KANA"

    /** 行、表のセル、文、読点で区切った並列項目をそれぞれ1セルとする。名前やバージョン内の半角ピリオドは分けない。 */
    private val CELL = Regex("[\\n|、]|(?<=[。！？])")
    private val IDENTIFIER = Regex("[A-Za-z_](?:[A-Za-z0-9_.:/@+\\-]*[A-Za-z0-9_])?")
    private val SENTENCE_PUNCTUATION = Regex("[.!?。！？:：;；]")
    private val CHINESE_PROSE = Regex(
        "已经|已經|已[完成停打开关開關付支发發写寫读讀查]|正在|无法|没有|沒有|你好|抱歉|这是|這是|我[已会會能要想先来來]|我打开|帮[我你]|打开了|找到了|找不到|看不到|等完了|完成了|成功了|失败了|请|請(?:你|您|用|先|把|給|幫|勿|在|去|問|看|回答|打開)|选哪个|選哪個|哪个|哪個|选一个|選一個|怎么|怎麼|什么|什麼|有没有|有沒有|是否|卡住|[吗嗎呢吧][.!?。！？\\s]*$" +
            "|已(?:提交|更新|设置|設置|送达|送達|发货|發貨|到账|到賬|取消)" +
            "|该[^\\n。！？]{1,40}(?:的[^\\n。！？]{1,40})?(?:为|是)" +
            "|(?:价格|库存)(?:为|是|有|充足|不足)|(?:预计|預計)(?:今天|明天|后天|後天)[^\\n。！？]{0,12}(?:送达|送達|到达|到達)" +
            "|(?:不能|可以|不要|不用|需要|無法)(?:再|先|把|给|給|帮|幫|您|你|我|登录|登入|登錄|打开|打開|付款|支付|继续|繼續|等待|选择|選擇|点击|點擊|执行|執行)" +
            "|(?:我|你|您)(?:不能|可以|需要|不[会會能想要]|没|沒)" +
            "|^(?:好[的了]|收到|知道了|明白了|谢谢|謝謝|是的)(?=[，,。.!！?？\\s]|$)" +
            "|好了" +
            "|(?:看|做|读|讀|等|写|寫|处理|處理|办|辦|查)完了" +
            "|(?:没|沒)(?:发|發|付|写|寫|做|找到|看见|看見|成功|完成|问题|問題)",
    )
    private val FENCE = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~")
    private val INLINE_CODE = Regex("`[^`\\n]*`")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val TAG = Regex("<[^<>\\n]*>")
    private val QUOTES = Regex("「[^」]*」|『[^』]*』|“[^”]*”|\"[^\"]*\"|《[^》]*》")
    private val BLOCKQUOTE = Regex("(?m)^\\s*>[^\\n]*(?:\\n|$)")
    /** 最も内側の括弧の組だけに一致させ、なくなるまで繰り返し除く。半角・全角の混在も可。 */
    private val PARENS = Regex("[(（][^()（）\\n]*[)）]")
}
