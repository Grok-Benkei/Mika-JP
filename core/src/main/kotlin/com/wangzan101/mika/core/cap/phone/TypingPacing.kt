package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.Pace
import com.wangzan101.mika.core.uistate.TextField
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 入力ボックスに入力するリズム (入力ボックスに触れずに純粋なロジック): ユーザーは一度に 1 単語ずつ入力し、入力メソッドを通じて中国語、日本語、韓国語の文字が 1 つずつ画面に表示されます。
 * 単語ごとに速度が異なり、句読点やスペースの後に一時停止が生じることがあります。 [plan] 単語の段落を一連のより長いプレフィックスに配置し、各段落の前に待機する時間を指定します。
 * 最後の段落は常に全文です。
 *
 * - ラテン文字、数字、句読点、各文字など。[Limits.latinMs] からランダムに選択された段落。中国語、日本語、韓国語の文字、各文字など [Limits.cjkMs]、
 *   と複数の接続されたもの ([Limits.cjkGroup]) が一緒に画面に表示されます。
 * - 各段落の後に ([Limits.pauseChance]、句読点とスペースの後に 2 倍)、さらに [Limits.pauseMs] を停止する機会があります。
 * - 単語が多い場合、複数の単語が 1 つの段落 (最大 [Limits.maxSteps] 段落) に結合されます。合計の長さが [Limits.maxTotalMs] を超えると、各段落は比例して短くなり、長いメッセージは数分間入力されなくなります。
 * - 単語を分割しないでください。サロゲート ペア、結合された追加記号、バリアント セレクター、およびゼロ幅で連結された式シーケンスは、前の文字と 1 つとしてカウントされます。
 *
 * [Instant] 待たずに全文を一度に書きます（「速い」、テスト、リズムを必要としない発信者に使用します）。
 */
class TypingPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** ミリ秒単位の継続時間。 */
    data class Limits(
        val latinMs: LongRange = 60L..170L,
        val cjkMs: LongRange = 160L..380L,
        val cjkGroup: IntRange = 1..3,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 300L..900L,
        val maxSteps: Int = 40,
        val maxTotalMs: Long = 6_000L,
    ) {
        companion object {
            /** ブラウジング速度を制限するアプリ (および「いいね!」の場合はすべてのアプリ): 入力が遅くなり、停止回数が増え、長いメッセージは最大 12 秒続くことがあります。 */
            val CAREFUL = Limits(
                latinMs = 90L..230L, cjkMs = 220L..520L, pauseChance = 0.1, pauseMs = 500L..1_400L, maxSteps = 60, maxTotalMs = 12_000L,
            )
        }
    }

    /** セクション: [delayMs] を待機した後、入力ボックスにはフルテキストの最初の [end] UTF-16 ユニットが含まれます。 */
    data class Step(val end: Int, val delayMs: Long)

    /** にはリズムがあります ([Instant] ではありません)。 */
    val paced: Boolean get() = random != null

    /** は、[text] を段落に配置します。 [Instant]、文字列が空の場合、段落は 1 つだけです (全文、範囲指定)。 */
    fun plan(text: String): List<Step> {
        val r = random ?: return listOf(Step(text.length, 0))
        val units = units(text)
        if (units.size <= 1) return listOf(Step(text.length, Pace.pick(r, limits.latinMs)))
        val per = ceil(units.size.toDouble() / limits.maxSteps).toInt().coerceAtLeast(1)
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < units.size) {
            // 中国語、日本語、韓国語の文字は、入力方法に従って一度に 1 単語ずつ画面に表示されます (他の単語にまたがらず、複数の連続した単語)。単語が多い場合は、段落ごとに少なくとも 1 つの単語を含める
            val group = if (isCjk(text, units[i])) {
                val want = Pace.pick(r, limits.cjkGroup)
                var k = 1
                while (k < want && i + k < units.size && isCjk(text, units[i + k])) k++
                k
            } else 1
            val upto = minOf(i + maxOf(group, per), units.size)
            var wait = 0L
            for (j in i until upto) wait += Pace.pick(r, if (isCjk(text, units[j])) limits.cjkMs else limits.latinMs)
            val end = if (upto == units.size) text.length else units[upto]
            val last = text.codePointBefore(end)
            val boundary = Character.isWhitespace(last) || isPunctuation(last)
            if (r.nextDouble() < limits.pauseChance * (if (boundary) 2 else 1)) wait += Pace.pick(r, limits.pauseMs)
            steps += Step(end, wait)
            i = upto
        }
        val total = steps.sumOf { it.delayMs }
        if (total <= limits.maxTotalMs) return steps
        val scale = limits.maxTotalMs.toDouble() / total
        return steps.map { it.copy(delayMs = (it.delayMs * scale).roundToLong()) }
    }

    /**
     * この入力ボックスに使用されるリズム: パスワード ボックス ([secret]) はセグメント化されていません (元のテキストを読み戻すことはできず、単語の半分はチェックできません)。 Web ページ ボックス ([web]) は、入力を受け取るために貼り付ける必要があります
     * はセグメント化されていません (書かれた単語はアプリで受信できません)。これら 2 つのタイプは依然として 1 回書き込まれ ([Instant])、残りはこのリズムに従います。
     */
    fun forField(web: Boolean, secret: Boolean): TypingPacing = if (web || secret) Instant else this

    /**
     * [plan] を押して、[text] のプレフィックスをセクションごとに [field] に書き込み、全文セクションを呼び出し側に残し (それでもセクション全体を書き込んで確認のために読み戻します)、最後のセクションが一時停止されるまで待ってから戻ります。
     * 最初の段落の書き込み後に読み戻してチェックします。ボックスは半語を受け入れません (読み戻しが一致しない、または読み取れない)、または書き込みを入力できない場合はすぐに停止し、false を返します。呼び出し側は依然として全文を一度に書き込みます。
     * は、すべてのプレフィックスが書き込まれると true を返します。セグメンテーションがない場合 ([Instant]、1 ワードのみ)、何も書き込まれず false が返されます。
     */
    suspend fun leadIn(field: TextField, text: String, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val steps = plan(text)
        if (steps.size < 2) return false
        for ((k, s) in steps.dropLast(1).withIndex()) {
            sleep(s.delayMs)
            val prefix = text.substring(0, s.end)
            if (!field.write(prefix)) return false
            if (k == 0) {
                val back = field.read() ?: return false
                if (back.filterNot { it.isWhitespace() } != prefix.filterNot { it.isWhitespace() }) return false
            }
        }
        sleep(steps.last().delayMs)
        return true
    }

    companion object {
        /** はテキスト全体を一度に書き込みます。 */
        val Instant = TypingPacing(null)

        /** は人間のタイピングのリズムに似ています。 [random] は固定シード (テスト) に変更できます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = TypingPacing(random, limits)

        /** [text] の各単語の開始点 (UTF-16 添え字): サロゲート ペアのシーケンス、追加のシンボル、バリアント セレクター、ゼロ幅の連結、および前の単語は 1 つとしてカウントされます。 */
        internal fun units(text: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            var joinNext = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val attach = out.isNotEmpty() && (joinNext || attaches(cp))
                if (!attach) out += i
                joinNext = cp == ZWJ
                i += Character.charCount(cp)
            }
            return out
        }

        private const val ZWJ = 0x200D

        private fun attaches(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
        }

        private fun isCjk(text: String, at: Int): Boolean = when (Character.UnicodeScript.of(text.codePointAt(at))) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
