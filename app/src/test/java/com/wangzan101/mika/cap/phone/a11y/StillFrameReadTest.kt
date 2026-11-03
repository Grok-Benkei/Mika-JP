package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillFrameReadTest {
    private val cells = FrameMath.GRID_W * FrameMath.GRID_H
    private val pageA = IntArray(cells) { 200 }
    private val pageB = IntArray(cells) { 20 }
    /** には、50 ミリ秒ごとに異なる遷移フレームがあります (ページがスライドインし、キーボードが上昇します)。 */
    private fun moving(t: Long) = IntArray(cells) { ((t / 50 * 37 + it * 11) % 256).toInt() }
    /** A ページには動き続ける小さな領域 (アニメーション化されたアバター) があり、数フレームしか動きません。 */
    private fun animatedA(t: Long) = pageA.copyOf().also { f -> for (i in 0 until 4) f[i] = if ((t / 100) % 2 == 0L) 0 else 255 }

    private class Clock { var now = 0L }

    @Test fun waitsForTheTransitionToEndBeforeReading() = runTest {
        val c = Clock()
        val frame = { if (c.now < 500) moving(c.now) else pageA }
        var readAt = -1L
        val r = StillFrameRead.read({ c.now }, { c.now += it }, frame) { readAt = c.now; "lines" }
        assertEquals("lines", r)
        assertTrue("read at $readAt", readAt >= 500)
    }

    @Test fun smallAnimationsDoNotDiscardTheText() = runTest {
        val c = Clock()
        // アバターは動き続けます。上限に達すると認識します。認識後は数フレームのみ変更されています。通常どおりお使いください。
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { animatedA(c.now) }) { c.now += 300; "lines" }
        assertEquals("lines", r)
        assertTrue(c.now <= StillFrameRead.STILL_CAP_MS + 400)
    }

    @Test fun pageSwitchDuringRecognitionReadsAgainOnTheNewPage() = runTest {
        val c = Clock()
        var reads = 0
        // は、初めて認識されたときにページを変更しました。それを捨てて、新しいページを押して再度認識します
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { if (reads == 0 || c.now < 1_000) pageA else pageB }) {
            reads++
            if (reads == 1) c.now = 1_000
            "read $reads"
        }
        assertEquals("read 2", r)
        // はページ全体で変化し続けます。2 回正解できないので、与えません。
        var n = 0
        assertNull(StillFrameRead.read({ c.now }, { c.now += it }, { if (n % 2 == 0) pageA else pageB }) { n++; "x" })
    }

    @Test fun noFrameMeansNothingToRead() = runTest {
        val c = Clock()
        assertNull(StillFrameRead.read<String>({ c.now }, { c.now += it }, { null }) { null })
        assertTrue(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { null }))
        assertFalse(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { moving(c.now) }))
    }

    @Test fun pageChangedNeedsAQuarterOfTheScreen() {
        val few = pageA.copyOf().also { for (i in 0 until 10) it[i] = 0 }
        assertTrue(FrameMath.differs(pageA, few))
        assertFalse(FrameMath.pageChanged(pageA, few))
        assertTrue(FrameMath.pageChanged(pageA, pageB))
        assertFalse(FrameMath.pageChanged(null, pageB))
    }
}
