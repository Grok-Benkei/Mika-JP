package com.wangzan101.mika.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSpeechTest {
    @Test
    fun errorCodesMapToFailures() {
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechFailure.Busy, SystemSpeech.failureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(SpeechFailure.NoPermission, SystemSpeech.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SpeechFailure.AudioError, SystemSpeech.failureOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(SpeechFailure.MicUnavailable, SystemSpeech.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun silenceFromAServiceThatNeverAnsweredIsNoResponse() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterSilence(answered = false),
        )
        // 通話に応答したサービスは、このセクションでのみ停止しています。聞こえた内容の最後を押してください。録音されていない場合、応答しません。
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterSilence(answered = true))
    }

    @Test
    fun releaseWaitStillEndsWhenTheRecognizerNeverAnswered() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterReleaseWait(answered = false),
        )
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterReleaseWait(answered = true))
    }

    @Test
    fun languageTagsAreAlwaysJapanese() {
        // 日本語専用: 旧選択やシステム言語が何でも ja-JP
        for (language in com.wangzan101.mika.i18n.AppLanguage.entries) {
            assertEquals(language.toString(), "ja-JP", SystemSpeech.speechTag(language, java.util.Locale.US))
            assertEquals(language.toString(), "ja-JP", SystemSpeech.speechTag(language, java.util.Locale.CHINA))
            assertEquals(language.toString(), "ja-JP", SystemSpeech.speechTag(language, java.util.Locale.FRANCE))
        }
        assertEquals("ja", SystemSpeech.isoCode("ja-JP"))
    }

    private fun svc(name: String, open: Boolean = true) = RecognizerService("a.b/.$name", open, null)

    /** 識別サービスが 1 つだけの場合、それがデフォルトのサービスです。デフォルトのサービスを試し、無駄に名前を付けずに再試行してください。 */
    @Test
    fun aSingleServiceIsOnlyTriedAsTheDefault() {
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(listOf(svc("One")), remembered = null))
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(emptyList(), remembered = null))
    }

    /** デフォルトで返信しない場合は、他の人を名前で試し、他のアプリで開いている人が最初に表示されます。合計数には上限があります。 */
    @Test
    fun otherServicesFollowTheDefaultOpenOnesFirst() {
        val services = listOf(svc("Closed", open = false), svc("Open"), svc("Third"))
        assertEquals(listOf(null, "a.b/.Open", "a.b/.Third"), SystemSpeech.attempts(services, remembered = null))
        assertEquals(SystemSpeech.MAX_ATTEMPTS, SystemSpeech.attempts(services + svc("Fourth"), null).size)
    }

    /** は前回の点呼テストで 1 位にランクされており、繰り返されません。携帯電話にはもうないので、無視してください。 */
    @Test
    fun theServiceThatAnsweredBeforeGoesFirst() {
        val services = listOf(svc("One"), svc("Two"))
        assertEquals(listOf("a.b/.Two", null, "a.b/.One"), SystemSpeech.attempts(services, remembered = "a.b/.Two"))
        assertEquals(listOf(null, "a.b/.One", "a.b/.Two"), SystemSpeech.attempts(services, remembered = "a.b/.Gone"))
    }
}
