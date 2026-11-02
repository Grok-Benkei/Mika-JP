package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeWaitTest {
    private val before = IntArray(288) { 200 }
    private val after = IntArray(288) { 20 }
    /** 画像は 50 ミリ秒ごとに異なる中間状態になります (描画と再生が続いています)。 */
    private fun moving(t: Long) = IntArray(288) { ((t / 50 * 37 + it * 11) % 256).toInt() }

    /** 偽の時計: 睡眠は時間を進めます。 FrameAt/lastEventAt は、画像と最新のイベントを時間ごとに提供します。 */
    private class Fake(
        val frameAt: (Long) -> IntArray?,
        val lastEventAt: (Long) -> Long = { -10_000 },
        val treeChangedAt: Long = Long.MAX_VALUE,
    ) {
        var now = 0L
        suspend fun run(before: IntArray?) = ChangeWait.awaitStableChange(
            now = { now },
            sleep = { now += it },
            frame = { frameAt(now) },
            quietForMs = { now - lastEventAt(now) },
            treeChanged = { now >= treeChangedAt },
            beforeFrame = before,
        )
    }

    @Test fun slowPageChangeIsReportedAfterFramesSettle() = runTest {
        // ページの描画開始には 1.2 秒、終了には 1.6 秒かかりました。イベントは静かであり、コントロール ツリーは変更されていません (自己描画インターフェイス)
        val f = Fake(frameAt = { t -> when { t < 1_200 -> before; t < 1_600 -> moving(t); else -> after } })
        assertTrue(f.run(before))
        assertTrue("waited for the frame to settle, at ${f.now}", f.now >= 1_600)
        assertTrue(f.now <= ChangeWait.CAP_MS)
    }

    @Test fun fastPathReturnsAsSoonAsChangeIsStable() = runTest {
        val f = Fake(frameAt = { t -> if (t < 100) before else after })
        assertTrue(f.run(before))
        assertTrue("returned early, at ${f.now}", f.now <= 2 * ChangeWait.POLL_MS)
    }

    @Test fun normalPageChangeReturnsSoonAfterEventsQuiet() = runTest {
        // 通常のページめくり: イベントは 100 ミリ秒継続し、コントロール ツリーと画面が変更されます。
        val f = Fake(frameAt = { t -> if (t < 100) before else after }, lastEventAt = { t -> minOf(t, 100) }, treeChangedAt = 50)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now in 100 + ChangeWait.QUIET_MS..100 + ChangeWait.QUIET_MS + ChangeWait.POLL_MS)
    }

    @Test fun neverStillPageWithTreeChangeReturnsFast() = runTest {
        // ビデオとライブ ブロードキャスト: 画像は動き続けますが、イベントは静かで、コントロール ツリーが変化します。画像が停止するまで待つ必要はありません。
        val f = Fake(frameAt = ::moving, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now <= ChangeWait.POLL_MS)
    }

    @Test fun nothingChangesReturnsFalseAtCap() = runTest {
        val f = Fake(frameAt = { before })
        assertFalse(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
        assertTrue(f.now < ChangeWait.CAP_MS + ChangeWait.POLL_MS)
    }

    @Test fun waitsForEventQuietBeforeJudging() = runTest {
        // コントロールツリーは最初に変化しますが、イベントは1秒まで続きます
        val f = Fake(frameAt = { after }, lastEventAt = { t -> minOf(t, 1_000) }, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("judged at ${f.now}", f.now >= 1_000 + ChangeWait.QUIET_MS)
    }

    @Test fun treeChangeWithoutFrameCounts() = runTest {
        // 画像なし (プレビュー フレームは利用できません): コントロール ツリーのみを表示します
        val f = Fake(frameAt = { null }, treeChangedAt = 900)
        assertTrue(f.run(null))
        assertTrue(f.now in 900..ChangeWait.CAP_MS)
    }

    @Test fun stillAnimatingAtCapButChangedCountsAsChanged() = runTest {
        val f = Fake(frameAt = ::moving)
        assertTrue(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
    }

    @Test fun eventsNeverQuietDecidesAtCapWithCurrentState() = runTest {
        // イベントが停止し続けます (進行状況バーなど)。上限に達すると、現在の状態に基づいて結論が導き出されます。
        val changed = Fake(frameAt = { after }, lastEventAt = { it }, treeChangedAt = 0)
        assertTrue(changed.run(before))
        assertTrue(changed.now >= ChangeWait.CAP_MS)
        assertFalse(Fake(frameAt = { before }, lastEventAt = { it }).run(before))
    }
}
