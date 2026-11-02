package com.wangzan101.mika.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldToTalkTest {
    /** 偽のセッション: release と cancel が呼び出された回数だけを覚えておいてください。イベントはテストによって [HoldToTalk.onEvent] に直接供給されます。 */
    private class FakeSession(override val live: Boolean = true) : SpeechSession {
        override val events: Flow<SpeechEvent> = emptyFlow()
        var released = 0
        var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++ }
    }

    private var now = 0L
    private val hold = HoldToTalk(clock = { now })

    private fun pressed(live: Boolean = true): FakeSession = FakeSession(live).also { hold.press(it) }

    @Test
    fun partialShowsLive() {
        pressed()
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertEquals("帮我", s.stable)
        assertEquals("订票", s.tail)
        assertTrue(s.live)
    }

    @Test
    fun releaseGivesDone() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun cancelArmedDropsText() {
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(1, session.cancelled)
        assertEquals(0, session.released)
        // キャンセル後、後期結果は表示されなくなります。
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun dragBackDisarms() {
        pressed()
        hold.drag(100f, 80f)
        hold.drag(10f, 80f)
        assertFalse((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
    }

    @Test
    fun tooShortIsNoMatch() {
        val session = pressed()
        now = 300
        hold.release()
        // は短すぎるため、会話には転送されません (サービス プロバイダーもクレジットを支払う必要があります)。そのまま捨てて、はっきりと聞こえなかったふりをしてください。
        assertEquals(0, session.released)
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
        // 後半のセッションの結果は「はっきりと聞こえませんでした」をカバーしていません
        hold.onEvent(SpeechEvent.Final("帮我"))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun blankFinalIsNoMatch() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.onEvent(SpeechEvent.Final("  "))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun minimumIsConfigurable() {
        // 入力フィールドのリスニングが開始されると、指は 300 ミリ秒間押され、カウントされるのは 200 ミリ秒だけです。合計で 0.5 秒未満は短すぎるとみなされます。
        val short = HoldToTalk(clock = { now }, minMs = 200)
        val session = FakeSession().also { short.press(it) }
        now = 250
        short.release()
        assertEquals(1, session.released)
        assertEquals(0, session.cancelled)
    }

    @Test
    fun maxDurationReleases() {
        val session = pressed()
        now = 30_000
        hold.tick()
        assertEquals(0, session.released)
        assertEquals(30_000L, (hold.state.value as HoldToTalk.State.Listening).elapsedMs)
        now = 60_000
        hold.tick()
        assertEquals(1, session.released)
        // エンディングを繰り返さずにポイントに到達した後にティックします
        now = 61_000
        hold.tick()
        assertEquals(1, session.released)
    }

    @Test
    fun providerShowsConverting() {
        pressed(live = false)
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Converting)
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        // サービス プロバイダーのエンジンが大幅に遅れている可能性があります。テキストの変換時にリスニングに戻らなくなりました。
        hold.onEvent(SpeechEvent.Level(0.5f))
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final("你好"))
        assertEquals(HoldToTalk.State.Done("你好"), hold.state.value)
    }

    @Test
    fun providerConvertsRightOnRelease() {
        val session = pressed(live = false)
        now = 2_000
        hold.release()
        // レコーダーが停止するまでに時間がかかり、Converting イベントの到着が遅れます。リリースされた瞬間に、プレースホルダーにフラッシュバックせずにテキスト変換が表示されるはずです。
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        assertEquals(1, session.released)
        hold.tick()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final(""))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun failureSurfaces() {
        pressed()
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertEquals(HoldToTalk.State.Problem(SpeechFailure.Busy), hold.state.value)
    }

    @Test
    fun disposeCancels() {
        val session = pressed()
        hold.dispose()
        hold.dispose()
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun levelMovesWhileListening() {
        pressed()
        hold.onEvent(SpeechEvent.Level(0.7f))
        assertEquals(0.7f, (hold.state.value as HoldToTalk.State.Listening).level, 0.0001f)
    }

    @Test
    fun resetAfterDoneGoesIdle() {
        pressed()
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Final("好"))
        hold.reset()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun pressAgainDropsTheOldSession() {
        val first = pressed()
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
    }

    @Test
    fun earlyFinalWaitsForReleaseThenAppliesOnce() {
        // 携帯電話の内蔵認識が、手を押したままの状態で勝手に終了しました。先に渡さないと指を離すとカウントされてしまいます。
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertTrue(s.ended)
        assertEquals("帮我订票", s.stable)
        now = 3_000
        hold.tick()
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
        // セッションは終了しました。これ以上終了しないでください。もう一度放して、二度と渡さないでください。
        assertEquals(0, session.released)
        hold.reset()
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalWithCancelArmedIsDropped() {
        val session = pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(0, session.released)
    }

    @Test
    fun earlyFinalThenDragToCancelIsDropped() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalThenDragBackOffCancelIsApplied() {
        pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(10f, 80f)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun earlyFailureWithCancelArmedIsDropped() {
        pressed()
        now = 2_000
        hold.armCancel(true)
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertTrue((hold.state.value as HoldToTalk.State.Listening).ended)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalAppliedWhenTimeRunsOut() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("好"))
        now = 60_000
        hold.tick()
        assertEquals(HoldToTalk.State.Done("好"), hold.state.value)
    }

    @Test
    fun pressWhileConvertingDropsTheOldSession() {
        // サービス プロバイダーのトランスクリプションが停止した場合、ユーザーはもう一度押すと、古い部分がキャンセルされ、新しい部分が通常どおりに聴取され、結果が通常どおり生成されます。
        val first = pressed(live = false)
        now = 2_000
        hold.release()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        now = 4_000
        hold.release()
        assertEquals(1, second.released)
        hold.onEvent(SpeechEvent.Final("新的"))
        assertEquals(HoldToTalk.State.Done("新的"), hold.state.value)
    }

    @Test
    fun joinRules() {
        assertEquals("你好", HoldToTalk.join("", "你好"))
        assertEquals("帮我订票", HoldToTalk.join("帮我", "订票"))
        assertEquals("book a train", HoldToTalk.join("book", "a train"))
        assertEquals("帮我 book", HoldToTalk.join("帮我", "book"))
        assertEquals("好的，订票", HoldToTalk.join("好的，", "订票"))
        assertEquals("book ", HoldToTalk.join("book ", ""))
        assertEquals("book a", HoldToTalk.join("book ", "a"))
    }
}
