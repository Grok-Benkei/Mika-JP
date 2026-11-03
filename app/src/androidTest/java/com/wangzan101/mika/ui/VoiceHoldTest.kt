package com.wangzan101.mika.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.wangzan101.mika.voice.SpeechFailure
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 長押ししてダイアログ入力フィールドで話します。入力ボックスを長押しして「リスニング」カードを浮かせ、放すと単語が入力ボックスに接続されます。上にスライドしてキャンセルし、何も入力しないでください。マイクを使わずに偽の音声ソースを使用します。 */
@RunWith(AndroidJUnit4::class)
class VoiceHoldTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 偽のセッション: イベントはテストによって供給されます。リリース数とキャンセル数を記録します。 */
    private class FakeSession : SpeechSession {
        val out = Channel<SpeechEvent>(Channel.UNLIMITED)
        override val events: Flow<SpeechEvent> = out.receiveAsFlow()
        override val live = true
        @Volatile var released = 0
        @Volatile var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++; out.close() }
    }

    private class FakeVoice : VoiceSource {
        val sessions = mutableListOf<FakeSession>()
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.System(onDevice = true)
        override fun start(language: String?): SpeechSession = FakeSession().also { sessions += it }
    }

    private val voice = FakeVoice()

    @Before fun grantMic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun composer(text: String = "") = compose.setContent {
        MikaTheme {
            var t by remember { mutableStateOf(text) }
            Box(Modifier.fillMaxSize()) {
                Composer(
                    Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = {}, onStop = {},
                    voice = voice,
                    onVoiceText = { t = com.wangzan101.mika.voice.HoldToTalk.join(t, it) },
                )
            }
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitNoTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun holdShowsTheListenCardAndReleaseFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        // 0.5 秒以上押した場合は、誤ってタッチしたとはみなされません。
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    @Test fun focusedFieldKeepsLongPressForTheSystem() {
        composer("帮我订票")
        // クリック: 入力に集中します
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        // フォーカスした後、長押ししてもシステムの単語選択と貼り付けとなり、リスニングは開始されません。
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        Thread.sleep(800)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0, voice.sessions.size)
        assertEquals(0, compose.onAllNodesWithTag("voice-listen").fetchSemanticsNodes().size)
    }

    @Test fun focusedMicButtonFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        Thread.sleep(700)
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    /** は録音を許可されていますが、認識サービスは依然として録音エラーを報告します。メッセージがポップアップ表示されます (マイク マスター スイッチ、権限管理)。他のマイクをオンにできない場合でも、プロンプトは 1 文だけです。 */
    @Test fun audioErrorExplainsWhereToCheckButClientErrorKeepsTheOldHint() {
        composer()
        fun failWith(why: SpeechFailure) {
            compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
            waitTag("voice-listen")
            voice.sessions.last().out.trySend(SpeechEvent.Failed(why))
            compose.onNodeWithTag("composer-field").performTouchInput { up() }
        }
        failWith(SpeechFailure.MicUnavailable)
        waitTag("voice-toast")
        compose.onNodeWithText("麦克风用不了，可能正被别的 App 占着").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().size)
        failWith(SpeechFailure.AudioError)
        waitTag("voice-sheet")
        compose.onNodeWithText("麦克风还是用不了").assertExists()
    }

    @Test fun slidingUpCancels() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -400f)) }
        waitTag("voice-cancel-hint")
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.cancelled == 1 }
        assertEquals(0, session.released)
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }
}
