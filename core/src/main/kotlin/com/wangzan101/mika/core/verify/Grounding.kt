package com.wangzan101.mika.core.verify

/**
 * コードの詳細: 検証者が合格した後、モデルとは関係のないルールを使用してテキスト証拠を再度チェックします。コードは、それがリテラルであるかどうかなど、検証する内容のみを検証します。
 * は、値が回答の内容に属するかどうかを判断しません (これはモデルの検証の問題です。検証者のプロンプト ワードを参照してください)。回答の本文 (「ベース:」の前):
 * - 引用符で囲まれた元のテキスト ("..." "..." "..." "..." '...'、タイトルに既に含まれているものを除く) は、特定のマテリアル内に存在する必要があります。
 *   引用符内の文字を使用して、回答に書いた数字を置き換えます (「今日 +N」は本文中の「今日 +2,271」を指します)。つまり、画面上の単語ではなく、回答の書き方を意味します。
 * - 単位、通貨、パーセント記号または大きさ (百万/十億/千) を含む数値、小数、3 桁を超える整数、文字で挟まれた数値。これらは、単語全体として一部の資料内に存在する必要があります。
 * - 日付と時刻 (2026 年 10 月 23 日、14:05) も見つかります。
 * "Based on:" では引用のみがチェックされます。各引用は資料内のどこかにある必要があります (引用内の... は複数の段落に分割され、各段落は同じ場所にある必要があります)。
 * 根拠内の残りの単語 (ソース、URL、読み取り時間、説明内のカウント) は確認すべき事実ではありません。それらが正しければ、位置決めに役立ちます。正しくない場合でも拒否されません。
 * 単位なしの 2 桁以内の整数はどこにも必要ありません。質問内の数字と原文は既知のものとみなされます。
 * 他の 2 つの情報源も知られており、それぞれがそれが達成されたことを証明するだけです。「明日」と「火曜日」は質問または証拠に書かれており、その日は実行中の時計に従って計算されます。
 * 回答に記載されている計算式（a演算子b=c、a、bは根拠で全て一致）の結果は、約数の四捨五入（近似、≒）であると明記されています。
 * 比較の最初の 2 つの側面は同じ方法で記述されます: 全角と半角 (NFKC)、ゼロ幅文字、参照番号と脚注、1000 分の 1、100 万と 10 億の変換、句読点と空白。両側を線で比較します。
 * 解答行は次の行でスペルアウトされず (行頭のリスト番号は行ごとに削除されます)、資料は文字列全体としてスペルアウトされません。 「現在のアプリ:」という行は、画面上のテキストとしてカウントされません。
 * は、\b を使用せずに、参照番号の境界に明示的な事前アサーションと事後アサーションを使用します。Java の \b は中国語の文字を単語文字として扱い、「@e46 表示」の 46 は答えの数字とみなされます。
 */
internal object Grounding {
    private val QUOTED = Regex("「([^」]*)」|『([^』]*)』|“([^”]*)”|\"([^\"]*)\"|‘([^’]*)’")
    /** 日本語は見出しの位置だけを認識し、引用内のラベルを本文から切り離さない。旧「依据」は従来どおり認識する。 */
    private val EVIDENCE = Regex(
        "依据\\**\\s*[:：]|(?:^|\\n|[。.!！?？]\\s*)[\\s*#>\\-]*(?:根拠|出典)\\**\\s*[:：]",
    )
    /**
     * 数字の後の数億/百万/千は桁違いです (間に区切ることができるのはスペースのみで、アプリでは多くの場合「1,561 万ホット」として配置されます。句読点がある場合はそうではありません)。
     * 「キロメートル」、「キログラム」、「キロワット」の 1,000 は単位の一部であり、桁数ではありません。まず、[ORDINAL] を押すと、行頭と引用頭のシリアル番号 (「5 千と千尋の神隠し」) が削除されます。
     */
    private val NUMBER = Regex("(?<!\\d)(?<!\\d[.,])\\d+(?:[.,]\\d+)*(?!\\d)([ \\t]*(?:[亿万]|千(?![米克瓦卡帕焦])))?")
    private val THOUSANDS = Regex("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")
    private val DIGITS = Regex("\\d+")
    /** 数値の直後に続く単語で、それが数量であることを示します (これらはシリアル番号の後にはありません): 単位、大きさ、数量子。 */
    private const val UNIT_WORDS = "公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|元|块|角|小时|分钟|秒|岁|度|分|人|次|个|条|件|万|亿|千[米克瓦卡帕焦]|[A-Za-z%℃°]"

    /** シリアル番号はチェック対象の番号ではありません: N 番目の項目/ステップ/名前...、最初の N/項目...、上位 N、No.N、#N、行頭のリスト番号 (1. 2, 3))。 「2026 番目」は値ですが、カウントされません。 */
    private val ORDINAL = Regex(
        "第[ \\t]*\\d+[ \\t]*(?=[条个集页项张篇步名位行章节轮次题])|前[ \\t]*\\d+[ \\t]*(?=[个条名位项篇张])|(?i:top)[ \\t]*\\d+|(?i:no)\\.[ \\t]*\\d+|#\\d+" +
            "|(?m)^[ \\t]*\\d+[ \\t]*[.、)）](?!\\d)" +
            // 名前は、行頭または引用符の先頭のシリアル番号の後にスペースで区切られています (「5 千と千尋の神隠し...」): 値ではありません
            "|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]*[.、](?!\\d)|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]+(?=\\p{L})(?!$UNIT_WORDS)",
    )
    /** 日付と時刻。 . で区切ると、1561.5 を日付として認識しないように、年、月、日の 3 つのセグメントのみが認識されます。 数字の後に */
    private val DATE = Regex(
        "(?<!\\d)(?:\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*[日号]?)?|\\d{4}\\.\\d{1,2}\\.\\d{1,2}" +
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}\\s*:\\s*\\d{2}(?:\\s*:\\s*\\d{2})?)(?!\\d)",
    )
    /** が続き、それが大きさの単位であることを示します (カウントに使用される数量詞「バー」と「個」はカウントされません。これらは自分でカウントされます)。 */
    private val UNIT_AFTER = Regex(
        "^\\s*(?:%|‰|元|块|角|美元|欧元|日元|港元|英镑|℃|°|度|公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|小时|分钟|秒|毫秒|岁|英寸|寸|折|星" +
            "|(?:km|cm|mm|kg|mAh|GB|MB|KB|TB|Hz|ms|[kKwWmgGM])(?![A-Za-z]))",
    )
    private val CURRENCY_BEFORE = Regex("[¥$€£]\\s*$")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]")
    /** 回答内の強調とコードマーク (**bold**、__bold__、~~delete~~、`コード`) は単なるレイアウトであり、内容ではありません。単一の * は乗算記号である可能性があり、移動しません。 */
    private val MARKUP = Regex("\\*\\*|__|~~|`")
    private val FRAGMENT_BREAK = Regex("[^\\p{L}]+")
    private val PIECE_BREAK = Regex("[^\\p{L}\\p{N}]+")
    /**
     * さまざまなモデル参照の記述方法: 要素番号 (@e16、@v2、括弧で囲まれる場合があります)、角括弧脚注 ([1]、[^2]、[3])。
     * は答えの一部ではないため、比較前に削除されます。境界は前後に主張されます。境界の後に中国語文字が続く場合も認識される必要があります (@e46 で示されます)。
     */
    private val REF_CITATION = Regex(
        "[(\\[【]\\s*@[ev]\\d+(?:\\s*[,、]\\s*@[ev]\\d+)*\\s*[)\\]】]|(?<![A-Za-z0-9_])@[ev]\\d+(?!\\d)",
    )
    private val FOOTNOTE = Regex("\\[\\^?\\d{1,3}]|【\\d{1,3}】")
    private val MAGNITUDE = mapOf("亿" to 8, "万" to 4, "千" to 3)
    const val APP_HEADER = "現在のアプリ:"
    private fun isAppHeader(line: String): Boolean = line.startsWith(APP_HEADER) || line.startsWith("当前 App:")
    /** ベースのテキスト フラグメントは短すぎて、どの画面 (「画面上」「設定」) にも収まる可能性があるため、カウントされません。 */
    private const val MIN_FRAGMENT = 4
    /** 画面上の行がベースに表示される場合、この行には少なくとも複数の単語が含まれている必要があります。 */
    private const val MIN_LINE_IN_BASIS = 2

    private data class Num(val value: String, val magnitude: String) {
        /** は単位変換後の値です (53,000 は 53000 と同じです)。計算できない場合は null です。 */
        val scaled: String? = value.toBigDecimalOrNull()?.let { v ->
            (MAGNITUDE[magnitude]?.let { v.movePointRight(it) } ?: v).stripTrailingZeros().toPlainString()
        }
        fun matches(screen: Num) = (value == screen.value && (magnitude.isEmpty() || magnitude == screen.magnitude)) ||
            (scaled != null && scaled == screen.scaled)
    }

    /** [Token]: 英字に挟まれた数字(G7541、a3f899c)は、数字ではなく単語単位で比較されます。 [Quote] には根拠に引用が含まれています。 */
    enum class Kind { Quote, Number, Date, Token }

    /** の回答にある特定の事実。数値と日付はテキストとして書き込むことができる値です。数字のない引用は、画面上にのみ表示されることがあります (ラジオ ボタンが選択されている場合など)。 */
    data class Claim(val kind: Kind, val text: String) {
        val visualOnly: Boolean get() = kind == Kind.Quote && text.none(Char::isDigit)
    }

    /**
     * 全てを知った結果。 [unmatched] は、どの証拠ソースにも見つからない事実です。 [anchored] は、少なくとも 1 つの事実または根拠の 1 つが一致することを意味します。
     * [found]: true、false ではありません。回答にはチェックするものは何もありません (または数値のみ、引用や根拠の位置付けはありません)。
     * [items] は、回答内の適切に文書化されたエントリの数です (レビュー内の回答の根拠については、[check] を参照してください)。
     */
    class Result(
        val found: Boolean?, val claims: List<Claim>, val unmatched: List<Claim>, val anchored: Boolean, val located: Boolean = false, val items: Int = 0,
    )

    /** NFKCの後は…です。 */
    private const val CUT = "..."
    /** 回答内の... の隣の単語 (比較には少なくともいくつかの単語が使用されます)。 */
    private val BEFORE_CUT = Regex("([^\\n.「」『』“”\"]{2,20})\\.\\.\\.")

    private fun isCut(line: String) = line.trimEnd().endsWith(CUT)

    /**
     * 回答は切り詰められたテキストに基づいています: 回答内の... の隣の単語と、引用符で囲まれた元のテキスト (最初と最後の... を削除) は、画面上の切り詰められた行にのみ表示されます。
     * であり、カットオフポイントに接続されています。切り詰められたテキストは値の一部にすぎず、完全な値として検証することはできません。
     * 画面上の単語が切り詰められているかどうかを確認してください。次のように答えてください。引用の後半を省略すると、画面上の行は完全になり、切り詰めとはみなされません。
     */
    fun builtOnTruncated(answer: String, screens: List<String>): Boolean {
        val a = canon(answer)
        val raw = screens.flatMap { canon(it).lines() }.filter { it.isNotBlank() && !isAppHeader(it) }
        fun onlyOnCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> isCut(l) && norm(l.trimEnd().removeSuffix(CUT)).endsWith(p) }
        }
        // 引用文は ... で始まります (画面上の行の前半が切り取られています): この単語は ... で始まる行にのみ表示されます。
        fun onlyOnHeadCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> l.trimStart().startsWith(CUT) && norm(l.trimStart().removePrefix(CUT)).startsWith(p) }
        }
        val quoted = quotes(a).map(String::trim)
        if (quoted.any { q -> q.endsWith(CUT) && onlyOnCutLines(q.removeSuffix(CUT)) }) return true
        if (quoted.any { q -> q.startsWith(CUT) && onlyOnHeadCutLines(q.removePrefix(CUT).removeSuffix(CUT)) }) return true
        if (quoted.any(::onlyOnCutLines)) return true
        // の後に、単語... の直前に独自の回答 (「Kotlin + Jetpack が必要です...」) を続けることができます。これまでに画面に表示された最長のテールを取得して比較します。
        return BEFORE_CUT.findAll(a).any { m ->
            val cap = m.groupValues[1]
            val tail = cap.indices.map { cap.substring(it) }.firstOrNull { t -> norm(t).length >= 2 && raw.any { norm(t) in norm(it) } }
            tail != null && onlyOnCutLines(tail)
        }
    }

    /** 参照番号と脚注を削除します (回答の内容ではありません)。 */
    fun stripRefs(s: String): String = s.replace(REF_CITATION, " ").replace(FOOTNOTE, " ")

    /** 全角と半角（NFKC）、ゼロ幅文字、組版マーク、参照番号、脚注など、両面を比較する前に表記方法を統一してください。 */
    private fun canon(s: String): String =
        stripRefs(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replace(ZERO_WIDTH, "").replace(MARKUP, ""))

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }

    // 空の引用符 (JSON の ""、"") には検証可能な元のテキストがありません: スキップされ、タスク全体が異常に中断されることはありません。
    private fun quotes(s: String) = QUOTED.findAll(s).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }.toList()

    private fun num(m: MatchResult): Num {
        val raw = m.value.substring(0, m.value.length - m.groupValues[1].length)
        return Num(if (THOUSANDS.matches(raw)) raw.replace(",", "") else raw, m.groupValues[1].trim())
    }

    private fun numbers(s: String): List<Num> = NUMBER.findAll(s).map(::num).toList()

    /**
     * [s] でファクトとしてカウントされる数値: 大きさ、単位または通貨記号、小数、および 3 桁を超える整数を含む数値。
     * 2桁以内の単位のない整数は個数、通し番号、ランキングとなりカウントされません。 画面上の
     */
    private fun valueNumbers(s: String, known: Set<String>): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        if (n.scaled != null && n.scaled in known) return@mapNotNull null
        val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 9))
        val unit = n.magnitude.isNotEmpty() || UNIT_AFTER.containsMatchIn(after) || CURRENCY_BEFORE.containsMatchIn(s.substring(0, m.range.first))
        val decimal = '.' in n.value
        val long = n.value.substringBefore('.').substringBefore(',').length >= 3
        n.takeIf { unit || decimal || long }
    }.toList()

    private fun components(s: String) = DIGITS.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiAlnum() = isAsciiLetter() || this in '0'..'9'

    /**
     * [s] [start] ～ [end] (排他的) 数字は完全な単語ですか。最初の単語は英語の文字や数字であってはなりません。その後に英語の文字が続く場合、
     * は文字列 (128GB などの単位) でのみ終わることができ、それ以上の数字を追加することはできません。ハッシュ内の数値、URL 内の数値、a3f899c などの ID は画面上ではカウントされません。
     * 数値の小数点と 1000 の位は影響を受けません。
     */
    private fun wholeToken(s: String, start: Int, end: Int): Boolean {
        if (s.getOrNull(start - 1)?.isAsciiAlnum() == true) return false
        var i = end
        while (i < s.length && s[i].isAsciiLetter()) i++
        return i == end || i == s.length || s[i] !in '0'..'9'
    }

    /**
     * [text] ([text] の行番号による) のどの行が回答の事実と一致しますか: 検証モデルに抜粋を表示する場合、これらの行は残す必要があります ([EvidenceLedger.excerpt])。ルールを確認していない。
     */
    fun hitLines(goal: String, answer: String, text: String, today: java.time.LocalDate? = null): Set<Int> {
        val r = check(goal, answer, listOf(text), today)
        val ok = r.claims.filter { it !in r.unmatched }
        if (ok.isEmpty()) return emptySet()
        val pieces = ok.filter { it.kind == Kind.Quote }.flatMap { q -> q.text.split(CUT).flatMap { it.split(PIECE_BREAK) } }.map(::norm).filter { it.length >= 2 }
        val raw = canon(text).lines()
        return raw.indices.filter { i ->
            val l = raw[i]
            if (l.isBlank() || isAppHeader(l)) return@filter false
            val line = lines(listOf(l)).single()
            pieces.any { it in line.text } || ok.any { c ->
                when (c.kind) {
                    Kind.Quote -> false
                    Kind.Number -> numbers(c.text).firstOrNull()?.let { n -> line.nums.any(n::matches) } == true
                    Kind.Date -> components(c.text).let { v -> v.isNotEmpty() && line.digits.windowed(v.size).any { it == v } } ||
                        components(c.text).drop(1).let { v -> v.size == 2 && line.digits.windowed(2).any { it == v } }
                    Kind.Token -> c.text.lowercase() in line.tokens
                }
            }
        }.toSet()
    }

    /** 画面上の 1 行の完全な番号 ([wholeToken] を参照)。 */
    private fun screenNumbers(s: String): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        n.takeIf { wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
    }.toList()

    /** 画面上の 1 行の完全な数値文字列 (日付用)。 */
    private fun screenComponents(s: String) = DIGITS.findAll(s).filter { wholeToken(s, it.range.first, it.range.last + 1) }
        .map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private class Line(val text: String, val nums: List<Num>, val digits: List<String>, val tokens: Set<String>)

    private val ALNUM_RUN = Regex("[A-Za-z0-9]+")

    /** 1 行の英数字の文字列 (大文字と小文字は関係なく、数字に使用されます)。 */
    private fun alnumRuns(s: String) = ALNUM_RUN.findAll(s).map { it.value.lowercase() }.toSet()

    /**
     * [s] 英語の文字で挟まれた数字 (数字は完全な単語ではありません。[wholeToken] を参照)、およびそれらを同じ長さの空白に置き換えた後のテキスト:
     * の数値全体が検証用の 1 つの値として扱われ、内部の数値はカウントされなくなります。
     */
    private fun idTokens(s: String): Pair<List<String>, String> {
        val ids = NUMBER.findAll(s).filterNot { m -> wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
            .mapNotNull { m ->
                var a = m.range.first
                var b = m.range.last + 1
                while (a > 0 && s[a - 1].isAsciiAlnum()) a--
                while (b < s.length && s[b].isAsciiAlnum()) b++
                a until b
            }.distinct().toList()
        var t = s
        ids.forEach { r -> t = t.replaceRange(r, " ".repeat(r.last - r.first + 1)) }
        return ids.map { s.substring(it) } to t
    }

    private fun lines(screens: List<String>) = screens.flatMap { canon(it).lines() }
        .filter { it.isNotBlank() && !isAppHeader(it) }.map { Line(norm(it), screenNumbers(it), screenComponents(it), alnumRuns(it)) }

    /** 画面上の特定の行に、引用文の少なくとも 1 行 (複数行にわたる場合があります) が見つかります。 */
    fun quoteInReads(quote: String, screens: List<String>): Boolean {
        val ls = lines(screens)
        return canon(quote).lines().map(::norm).filter { it.length >= 2 }.any { q -> ls.any { q in it.text } }
    }

    /** 正しい場合は true を返し、正しくない場合は false を返します。回答にチェックするものが何もない場合は null を返します。 */
    fun found(goal: String, answer: String, screens: List<String>): Boolean? = check(goal, answer, screens).found

    /** 相対日: 今日、明日、明後日、昨日、第 X 週 / 第 X 週。 */
    private val RELATIVE_DAY = Regex("今天|今日|明天|明日|后天|昨天|昨日|(?:周|星期|礼拜)([一二三四五六日天])")
    private val WEEKDAY_CHARS = "一二三四五六日"
    /** Week X 特定の日付に変換される範囲: 昨日から 1 週間後。 */
    private const val WEEK_AHEAD = 7L

    /** [word] [today] に従って変換された日付 (週 X には、昨日と 1 週間後の間に 2 つある場合があります)。 */
    private fun resolve(word: MatchResult, today: java.time.LocalDate): List<java.time.LocalDate> = when (word.value) {
        "今天", "今日" -> listOf(today)
        "明天", "明日" -> listOf(today.plusDays(1))
        "后天" -> listOf(today.plusDays(2))
        "昨天", "昨日" -> listOf(today.minusDays(1))
        else -> {
            val c = word.groupValues[1].replace("天", "日")
            val dow = java.time.DayOfWeek.of(WEEKDAY_CHARS.indexOf(c) + 1)
            (-1L..WEEK_AHEAD).map(today::plusDays).filter { it.dayOfWeek == dow }
        }
    }

    /** 日付の書き込みにおける年、月、日。年、月、または時刻のみがある場合は null になります。 */
    private fun dateParts(d: String): Triple<Int?, Int, Int>? {
        if (':' in d) return null
        val c = DIGITS.findAll(d).map { it.value.toInt() }.toList()
        return when {
            c.size == 3 && c[0] >= 1000 -> Triple(c[0], c[1], c[2])
            c.size == 2 && !Regex("^\\d{4}").containsMatchIn(d.trim()) -> Triple(null, c[0], c[1])
            else -> null
        }
    }

    /**
     * 時計が証明できる日付: 質問または証拠の特定の行に「明日」または「火曜日」と記載されており、日付は [today] に従って変換されます。
     * 回答のテキスト (引用符と「基準:」を除く) が特定の日付に書かれている場合、時計がそれを証明します。回答にのみ現れる相対的な記述は何も証明しません。引用符内の時刻、日付、
     * ベース内の日付 (答えはページから読み取られたものです) は時計によって検証されません。
     */
    private fun clockDates(body: String, basis: String, said: List<String>, today: java.time.LocalDate?): Set<String> {
        today ?: return emptySet()
        val meant = said.flatMap { t -> RELATIVE_DAY.findAll(t).flatMap { resolve(it, today) }.toList() }.toSet()
        if (meant.isEmpty()) return emptySet()
        val plain = QUOTED.replace(body) { " ".repeat(it.value.length) }
        val quotedOrRead = quotes(body).joinToString("\n") + "\n" + basis
        return plain.lines().flatMap { DATE.findAll(it).toList() }.mapNotNull { m ->
            val text = m.value.trim()
            val (y, mo, d) = dateParts(text) ?: return@mapNotNull null
            if (DATE.findAll(quotedOrRead).any { it.value.trim() == text }) return@mapNotNull null
            text.takeIf { meant.any { it.monthValue == mo && it.dayOfMonth == d && (y == null || it.year == y) } }
        }.toSet()
    }

    private const val NUM_TOKEN = "(\\d+(?:,\\d{3})*(?:\\.\\d+)?)"
    /** 1 つのオペランド: 通貨記号、次の単位 (文字、℃、°、%)、および括弧内に記述されたソース (「399 (製品ページ)」) を含めることができます。 */
    private const val OPERAND = "([¥$€£]?)\\s*$NUM_TOKEN\\s*([\\p{L}℃°%]{0,4})\\s*(?:[(（][^()（）\\n]{1,20}[)）])?\\s*"
    /**
     * の回答に記載されているワンステップ操作: a 演算子 b = c (または ≈ c,= 約 c);演算子は + - − × * ÷ / です。 c の後に % が続く場合、÷ と / の結果はパーセンテージとして計算されます。
     */
    private val EXPRESSION = Regex(
        OPERAND + "([+\\-−×*÷/])\\s*" + OPERAND + "(=|≈)\\s*(约|≈)?\\s*([¥$€£]?)\\s*$NUM_TOKEN(\\s*%)?\\s*([\\p{L}℃°]{0,4})",
    )

    /** 標準単位: 寸法と倍数を基本単位に換算したもの。 */
    private class Unit(val dimension: String, val factor: java.math.BigDecimal)

    /**
     * 標準単位の同義表記と変換 (非公開テーブル、他の推測なし): キロメートル/キロメートル/km、メートル/m、元/ブロック/¥/RMB/人民元、グラム/g、キログラム/kg/kg、
     * 時間/時、分/分、%/パーセント。最も長い書き方が最初に認識されます。
     */
    private val UNITS: List<Pair<String, Unit>> = listOf(
        "公里" to Unit("length", java.math.BigDecimal(1000)), "千米" to Unit("length", java.math.BigDecimal(1000)), "km" to Unit("length", java.math.BigDecimal(1000)),
        "米" to Unit("length", java.math.BigDecimal.ONE), "m" to Unit("length", java.math.BigDecimal.ONE),
        "人民币" to Unit("cny", java.math.BigDecimal.ONE), "RMB" to Unit("cny", java.math.BigDecimal.ONE), "元" to Unit("cny", java.math.BigDecimal.ONE),
        "块" to Unit("cny", java.math.BigDecimal.ONE), "¥" to Unit("cny", java.math.BigDecimal.ONE),
        "千克" to Unit("mass", java.math.BigDecimal(1000)), "公斤" to Unit("mass", java.math.BigDecimal(1000)), "kg" to Unit("mass", java.math.BigDecimal(1000)),
        "克" to Unit("mass", java.math.BigDecimal.ONE), "g" to Unit("mass", java.math.BigDecimal.ONE),
        "小时" to Unit("time", java.math.BigDecimal(3600)), "h" to Unit("time", java.math.BigDecimal(3600)),
        "分钟" to Unit("time", java.math.BigDecimal(60)), "min" to Unit("time", java.math.BigDecimal(60)),
        "百分之" to Unit("percent", java.math.BigDecimal.ONE), "%" to Unit("percent", java.math.BigDecimal.ONE),
    ).sortedByDescending { it.first.length }

    /** 通貨記号と数値の後の単語で認識される標準単位。認識されない場合は null (単位は書き込まれません)。 */
    private fun unitOf(before: String, after: String): Unit? =
        UNITS.firstOrNull { (k, _) -> before == k }?.second ?: UNITS.firstOrNull { (k, _) -> after.startsWith(k, ignoreCase = k.all { it.isAsciiLetter() }) && !after.drop(k.length).take(1).any { it.isAsciiLetter() } }?.second

    /** 式のオペランドの単位: 表内のオペランドの単位は標準単位です。表にないもの (ドル記号、マイルなど) は、それ自体と同じ 1 つの次元で書かれています。 */
    private fun operandUnit(before: String, after: String): Unit? =
        unitOf(before, after) ?: (before + after.trim()).takeIf { it.isNotBlank() }?.let { Unit("other:" + it.lowercase(), java.math.BigDecimal.ONE) }
    /** は数値の直前にある記号で、それが約数であることを示します。 */
    private val APPROX_BEFORE = Regex("(?:约|≈)\\s*[¥$€£]?\\s*$")

    /**
     * [x] は、[d] の丸め結果です。これが約数ではない場合、[x] に書き込まれた最後の桁のみが丸められます (整数は等しい必要があり、小数点以下の桁は四捨五入する必要があります)。
     * が約数 (およそ、≈) であると明示されている場合、末尾の 0 も正確であるとみなされ (百の位は 900 となります)、相対誤差は [MAX_ROUND_ERROR] を超えません。
     */
    private fun roundsTo(x: java.math.BigDecimal, d: java.math.BigDecimal, approx: Boolean): Boolean {
        val diff = (x - d).abs()
        if (diff.signum() == 0) return true
        val power = when {
            x.scale() > 0 -> -x.scale()
            approx -> minOf(-x.stripTrailingZeros().scale(), MAX_ROUND_POWER).coerceAtLeast(0)
            else -> 0
        }
        val half = java.math.BigDecimal.ONE.scaleByPowerOfTen(power).divide(java.math.BigDecimal(2))
        if (diff > half) return false
        return !approx || (d.signum() != 0 && diff.divide(d.abs(), java.math.MathContext.DECIMAL64) <= MAX_ROUND_ERROR)
    }

    private const val MAX_ROUND_POWER = 6
    private val MAX_ROUND_ERROR = java.math.BigDecimal("0.05")
    private val HUNDRED = java.math.BigDecimal(100)

    private fun plainValue(s: String) = s.replace(",", "").toBigDecimalOrNull()

    /**
     * 解答には、根拠にある計算式と両オペランドの一致結果（「6363−5464=899」）、つまり返される結果の書き方（解答の数値の書き方と一致します）が記載されています。
     * [grounded] 数字が証拠と一致するかどうかを判断します (質問内の数字を除く)。
     */
    private fun computed(body: String, grounded: (String) -> Boolean): Set<String> = body.lines().flatMap { EXPRESSION.findAll(it).toList() }.mapNotNull { m ->
        val g = m.groupValues
        val (aCur, aRaw, aUnit, op) = listOf(g[1], g[2], g[3], g[4])
        val (bCur, bRaw, bUnit, eq) = listOf(g[5], g[6], g[7], g[8])
        val (approxWord, cCur, cRaw, pct) = listOf(g[9], g[10], g[11], g[12])
        val cUnitText = g[13]
        if (!grounded(aRaw) || !grounded(bRaw)) return@mapNotNull null
        var a = plainValue(aRaw) ?: return@mapNotNull null
        var b = plainValue(bRaw) ?: return@mapNotNull null
        var c = plainValue(cRaw) ?: return@mapNotNull null
        val mc = java.math.MathContext.DECIMAL64
        // 加算と減算は同じ次元でなければなりません。単位が記述されている場合は、標準単位に従って変換します (キロメートルとキロメートルは同じもので、キロメートルとメートルの差は 1000 倍です)。次元が異なる数式はカウントされません
        if (op in setOf("+", "-", "−")) {
            val ua = operandUnit(aCur, aUnit)
            val ub = operandUnit(bCur, bUnit)
            val uc = unitOf(cCur, if (pct.isNotBlank()) "%" else cUnitText)
            val units = listOfNotNull(ua, ub, uc)
            if (units.map { it.dimension }.distinct().size > 1) return@mapNotNull null
            val base = ua ?: ub ?: uc
            if (base != null) {
                a = a * (ua ?: base).factor; b = b * (ub ?: base).factor
                c = c * (uc ?: ua ?: base).factor
            }
        }
        val r = when (op) {
            "+" -> a + b
            "-", "−" -> a - b
            "×", "*" -> a * b
            else -> if (b.signum() == 0) return@mapNotNull null else a.divide(b, mc)
        }
        val result = if (pct.isNotBlank() && (op == "÷" || op == "/")) r * HUNDRED else r
        val approx = eq == "≈" || approxWord.isNotEmpty()
        cRaw.replace(",", "").takeIf { roundsTo(c, result, approx) }
    }.toSet()

    /** 引用符内の数字を置き換える文字 (「Today +N」の N)。 */
    private val PLACEHOLDER = Regex("(?<![A-Za-z])[A-Za-z](?![A-Za-z])")

    /**
     * 引用 [q] は、独自の答えの書き方について話しています。数字の代わりに文字が含まれています。文字を数字に変更すると、それは回答のテキストに書かれた段落になります (引用符を除く) (「Today +N」と「Today +2,271」)。
     */
    private fun template(q: String, plain: String): Boolean {
        if (!PLACEHOLDER.containsMatchIn(q)) return false
        val pattern = q.trim().split(Regex("\\s+")).joinToString("\\s*") { w ->
            PLACEHOLDER.split(w).joinToString("\\d+(?:[.,]\\d+)*") { Regex.escape(it) }
        }
        return Regex(pattern).containsMatchIn(plain)
    }

    private val EMPHASIS = Regex("\\*\\*|__|`")
    /** 行の先頭にトークンをリストします (1. 2, 3) ① - * •)。 */
    private val LIST_MARK = Regex("^[ \\t]*(?:\\(?\\d{1,3}[.、)）:：]|（\\d{1,3}）|[①-⑳]|[-*•·])[ \\t]*")
    /** リスト内の「タグ:値」の値。 */
    private val LABEL_VALUE = Regex("^[^:：]{1,12}[:：]\\s*(.+)$")
    /** エントリには少なくとも数語が必要です (単一の単語と句読点はどの画面でも使用でき、数値を構成するために使用することはできません)。 */
    private const val MIN_ITEM = 2

    fun check(goal: String, answer: String, screens: List<String>, today: java.time.LocalDate? = null): Result {
        val a = canon(answer)
        val entries = screens.map { lines(listOf(it)) }
        val ls = entries.flatten()
        val g = canon(goal)
        val goalNums = numbers(g).map { it.value }.toSet()
        // 質問内の数値は値によって比較されます (1000.0 と 1000 は同じ数値です)
        val goalValues = numbers(g).mapNotNull { it.scaled }.toSet()
        val goalText = norm(g)
        val at = EVIDENCE.find(a)
        val basis = at?.let { a.substring(it.range.last + 1) }.orEmpty()
        val body = at?.let { a.substring(0, it.range.first) } ?: a

        fun numOnScreen(n: Num) = ls.any { l -> l.nums.any(n::matches) }
        /** [q] これらの行 [where]: 段落全体が特定の行にあるか、句読点スペースで区切られた各段落が特定の行にあります (数値は整数に比例します)。 */
        fun textIn(q: String, where: List<Line>): Boolean {
            val t = norm(q)
            if (t.isEmpty() || where.any { t in it.text }) return true
            val pieces = q.split(PIECE_BREAK).map(::norm).filter(String::isNotEmpty)
            // 行頭のシリアルナンバー（『5 千と千尋の神隠し』）と文字の間に挟まれた数字（AQ3A）は数字ではないため比較対象外となります。
            return pieces.size > 1 && pieces.all { p -> where.any { p in it.text } } &&
                screenNumbers(ORDINAL.replace(q.trim(), " ")).all { n -> where.any { l -> l.nums.any(n::matches) } }
        }
        /** 引用は資料内の特定の場所にあります。... で区切られた段落は互いに一致し、同じ場所にあります。 */
        fun quoteFound(q: String): Boolean {
            val parts = q.split(CUT).filter { norm(it).isNotEmpty() }.ifEmpty { listOf(q) }
            return entries.any { e -> parts.all { textIn(it, e) } }
        }
        fun dateOnScreen(d: String): Boolean {
            val c = components(d)
            val variants = listOfNotNull(c, c.takeIf { it.size == 3 && it[0].length == 4 }?.drop(1))
            return variants.any { v -> ls.any { l -> l.digits.windowed(v.size).any { it == v } } }
        }

        // 値は行ごとに取得されます。回答行は次の行と結合されず、行先頭のリスト番号が行ごとに削除されます。
        fun values(line: String): List<Claim> {
            val dates = DATE.findAll(line).map { it.value }.filter { d -> components(d).any { it !in goalNums } }.toList()
            val (ids, plain) = idTokens(ORDINAL.replace(DATE.replace(line, " "), " "))
            return dates.map { Claim(Kind.Date, it.trim()) } + ids.filter { norm(it) !in goalText }.map { Claim(Kind.Token, it) } +
                valueNumbers(plain, goalValues).map { Claim(Kind.Number, it.value + it.magnitude) }
        }
        val plainBody = QUOTED.replace(body, " ")
        val bodyQuotes = quotes(body).filter { norm(it).length >= 2 && norm(it) !in goalText && !template(it, plainBody) }
        val basisQuotes = quotes(basis).filter { norm(it).length >= 2 }
        val bodyLines = body.lines().map { it.replace(EMPHASIS, "") }
        val lineClaims = bodyLines.map(::values)
        val claims = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten() + basisQuotes.map { Claim(Kind.Quote, it) }).distinct()

        // 証拠に「明日」と「火曜日」と書かれている箇所：タイトルと証拠の各行
        val byClock = clockDates(body, basis, listOf(g) + ls.map { it.text }, today)
        fun matched(c: Claim) = when (c.kind) {
            Kind.Quote -> quoteFound(c.text)
            Kind.Number -> numbers(c.text).firstOrNull()?.let(::numOnScreen) ?: false
            Kind.Date -> dateOnScreen(c.text) || c.text in byClock
            Kind.Token -> ls.any { c.text.lowercase() in it.tokens }
        }
        // 証拠と一致する数字 (質問内の数字はカウントされません。質問内の数字の計算結果は画面上で読み取る必要があります)
        fun grounded(n: Num) = (n.scaled == null || n.scaled !in goalValues) && numOnScreen(n)
        fun groundedRaw(raw: String) = numbers(raw).firstOrNull()?.let(::grounded) == true
        val known = a.lines().flatMap { numbers(ORDINAL.replace(DATE.replace(it, " "), " ")) }.filter(::grounded).mapNotNull { it.scaled?.toBigDecimalOrNull() }
        // 計算結果を述べ、それが約数 (近似値、≈)、つまり、書かれた精度に丸めた後、一致する特定の数値に等しい数値であることを述べます。
        val derivedNums = computed(body, ::groundedRaw) + body.lines().flatMap { line ->
            NUMBER.findAll(line).mapNotNull { m ->
                val n = num(m)
                val x = n.scaled?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (!APPROX_BEFORE.containsMatchIn(line.substring(0, m.range.first))) return@mapNotNull null
                (n.value + n.magnitude).takeIf { known.any { roundsTo(x, it, approx = true) } }
            }.toList()
        }
        val unmatched = claims.filterNot { matched(it) || (it.kind == Kind.Number && it.text in derivedNums) }

        // 引用文以外の文言によると、位置決めに役立つだけ（前のものと一致するだけ）で、一致しない場合は拒否されません。
        val basisNorm = norm(basis)
        val fragments = QUOTED.replace(basis, " ").split(FRAGMENT_BREAK).map(::norm).filter { it.length >= MIN_FRAGMENT }
        val located = (basisQuotes.isNotEmpty() && basisQuotes.all(::quoteFound)) || fragments.any { f -> ls.any { f in it.text } } ||
            ls.any { it.text.length >= MIN_LINE_IN_BASIS && it.text in basisNorm }

        val quoteMatched = claims.any { it.kind == Kind.Quote && it !in unmatched }
        val found = when {
            unmatched.isNotEmpty() -> false
            quoteMatched || located -> true
            // には出典も根拠も場所もありません。正しいのは数字だけです。数字が画面上に表示されることがあります。それらだけではカウントされません。
            else -> null
        }

        // 適切に文書化されたエントリ: 回答内で事実が一致する行 (同じ内容の行は 1 回のみカウントされます)、段落全体が特定の行に含まれる値のないエントリのリスト。
        // それと上記のペアの間でより多くの異なるファクトを取得します。
        val okClaims = claims.filter { it !in unmatched }.toSet()
        val rows = mutableSetOf<String>()
        val bare = mutableSetOf<String>()
        bodyLines.forEachIndexed { i, raw ->
            val key = norm(raw)
            if (key.isEmpty()) return@forEachIndexed
            val inLine = lineClaims[i] + quotes(raw).map { Claim(Kind.Quote, it) }
            if (inLine.any { it in okClaims }) { rows += key; return@forEachIndexed }
            if (inLine.isNotEmpty() || !LIST_MARK.containsMatchIn(raw)) return@forEachIndexed
            val item = raw.replace(LIST_MARK, "").trim().let { t -> LABEL_VALUE.find(t)?.groupValues?.get(1) ?: t }
            if (norm(item).length >= MIN_ITEM && norm(item) !in goalText && textIn(item, ls)) bare += norm(item)
        }
        val okInBody = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten()).filter { it in okClaims }.toSet()
        val items = maxOf(rows.size, okInBody.size) + bare.size
        return Result(found, claims, unmatched, anchored = located || claims.size > unmatched.size, located = located, items = items)
    }
}
