package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.TextField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 入力のリズム: 接頭辞がどんどん長くなり、最後に全文が表示されます。言葉は分割されません。中国語、日本語、韓国語の文字が一度に 1 語ずつ画面に表示されます。長いテキストは分割されており、合計長さに上限があります。セグメント内での書き込みに失敗した場合は、段落全体での書き込みに戻ります。 */
class TypingPacingTest {
    private val limits = TypingPacing.Limits()

    private fun checkShape(text: String, steps: List<TypingPacing.Step>) {
        assertEquals(text.length, steps.last().end)
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a.end < b.end) }
        // セグメント境界が単語の途中にありません
        val units = TypingPacing.units(text).toSet() + text.length
        steps.forEach { assertTrue("${it.end} in $text", it.end in units) }
        steps.forEach { assertTrue(it.delayMs >= 0) }
    }

    @Test fun instantWritesTheWholeTextAtOnce() {
        assertEquals(listOf(TypingPacing.Step(5, 0)), TypingPacing.Instant.plan("hello"))
        assertFalse(TypingPacing.Instant.paced)
    }

    @Test fun latinTextGoesOutOneCharacterAtATimeWithBoundedDelays() {
        repeat(30) { seed ->
            val text = "hello world"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            assertEquals(text.length, steps.size)
            val most = limits.latinMs.last + limits.pauseMs.last
            steps.forEach { assertTrue("${it.delayMs}", it.delayMs in limits.latinMs.first..most) }
        }
    }

    @Test fun cjkTextGoesOutAWordAtATime() {
        repeat(30) { seed ->
            val text = "今天天气很好，我们去公园散步吧"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            // 段落ごとに 1 ～ 3 文字の漢字、句読点は独自の段落を形成します
            var start = 0
            for (s in steps) {
                val chunk = text.substring(start, s.end)
                if (chunk == "，") assertTrue(s.delayMs >= limits.latinMs.first)
                else {
                    assertTrue(chunk, chunk.length in limits.cjkGroup)
                    assertTrue("${s.delayMs}", s.delayMs >= chunk.length * limits.cjkMs.first)
                }
                start = s.end
            }
        }
    }

    @Test fun charactersAreNeverSplit() {
        // a、笑顔（エージェントペア）、b、いいねと肌の色、c、家族（ゼロ幅接続）、e、追加のアクセント、中
        val text = "a😀b👍🏽c👨‍👩‍👧é中"
        assertEquals(listOf(0, 1, 3, 4, 8, 9, 17, 19), TypingPacing.units(text))
        repeat(20) { seed -> checkShape(text, TypingPacing.human(Random(seed)).plan(text)) }
    }

    @Test fun longTextIsChunkedAndCappedInTotal() {
        val latin = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val cjk = "我们明天上午十点在公司门口见面，记得带上资料。".repeat(20)
        for (text in listOf(latin, cjk)) {
            for (l in listOf(TypingPacing.Limits(), TypingPacing.Limits.CAREFUL)) {
                val steps = TypingPacing.human(Random(7), l).plan(text)
                checkShape(text, steps)
                assertTrue("${steps.size}", steps.size <= l.maxSteps)
                // は比例的に短縮される場合に丸められ、ステップごとの最大差は 1 ミリ秒になります。
                assertTrue("${steps.sumOf { it.delayMs }}", steps.sumOf { it.delayMs } <= l.maxTotalMs + steps.size)
            }
        }
        // は短くて縮まない
        val short = TypingPacing.human(Random(3)).plan("ok")
        assertTrue(short.sumOf { it.delayMs } >= 2 * limits.latinMs.first)
    }

    @Test fun carefulIsSlower() {
        val text = "我想问一下这个还在吗"
        val normal = (0 until 20).sumOf { TypingPacing.human(Random(it)).plan(text).sumOf { s -> s.delayMs } }
        val careful = (0 until 20).sumOf { TypingPacing.human(Random(it), TypingPacing.Limits.CAREFUL).plan(text).sumOf { s -> s.delayMs } }
        assertTrue("$normal < $careful", normal < careful)
    }

    /** は、書き込まれる入力ボックスを書き留めます。 [accepts] が false の場合、ハーフワードを受け入れないボックスのようなものです (読み戻されると常に空になります)。 */
    private class Field(private val accepts: Boolean = true, private val writable: Boolean = true) : TextField {
        val writes = mutableListOf<String>()
        private var text = ""
        override fun read(): String? = if (accepts) text else ""
        override fun write(text: String): Boolean {
            if (!writable) return false
            writes += text
            this.text = text
            return true
        }
    }

    @Test fun leadInWritesGrowingPrefixesAndLeavesTheWholeTextToTheCaller() = runTest {
        val text = "你好，world"
        // 同一シードから排出されるセグメントは書き込み時と同じです。
        val plan = TypingPacing.human(Random(1)).plan(text)
        val pacing = TypingPacing.human(Random(1))
        val field = Field()
        val slept = mutableListOf<Long>()
        assertTrue(pacing.leadIn(field, text) { slept += it })
        assertEquals(plan.dropLast(1).map { text.substring(0, it.end) }, field.writes)
        assertEquals(plan.map { it.delayMs }, slept)
    }

    @Test fun leadInStopsWhenTheFieldRejectsPartialText() = runTest {
        val rejecting = Field(accepts = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(rejecting, "hello") {})
        assertEquals(listOf("h"), rejecting.writes)
        val locked = Field(writable = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(locked, "hello") {})
        // 分割されていない場合は何も書き込まれません。呼び出し元は通常どおりセグメント全体を書き込みます。
        val instant = Field()
        assertFalse(TypingPacing.Instant.leadIn(instant, "hello") { error("no wait") })
        assertTrue(instant.writes.isEmpty())
    }

    @Test fun passwordAndPasteOnlyWebFieldsAreWrittenAtOnce() {
        val human = TypingPacing.human(Random(1))
        assertSame(human, human.forField(web = false, secret = false))
        assertSame(TypingPacing.Instant, human.forField(web = false, secret = true))
        assertSame(TypingPacing.Instant, human.forField(web = true, secret = false))
    }
}
