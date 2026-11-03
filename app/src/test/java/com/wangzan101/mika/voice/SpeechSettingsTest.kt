package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSettingsTest {
    @Test fun freshIsAuto() {
        assertEquals(SpeechChoice.Auto, SpeechSettings(MemoryPrefs()).choice.value)
    }

    @Test fun everyChoiceSurvivesARestart() {
        val choices = listOf(
            SpeechChoice.Auto,
            SpeechChoice.System,
            SpeechChoice.Off,
            SpeechChoice.Doubao(),
            SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT),
            SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions),
            SpeechChoice.Provider("p2", "gpt-4o-audio", TranscribeMethod.ChatInputAudio),
            SpeechChoice.Provider("p3", "gemini-flash", TranscribeMethod.GeminiInline),
            SpeechChoice.Provider("p4", "asr-x", TranscribeMethod.ChatInputAudioUri),
            SpeechChoice.Provider("p5", "asr-y", TranscribeMethod.ChatInputAudioOnly),
        )
        for (c in choices) {
            val prefs = MemoryPrefs()
            val s = SpeechSettings(prefs)
            s.set(c)
            assertEquals(c, s.choice.value)
            assertEquals(c, SpeechSettings(prefs).choice.value)
        }
    }

    @Test fun silentMarkNeedsTwoSilentSessionsInARow() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        assertEquals(false, s.systemSilent.value)
        // が一度スタックした場合 (コールド スタートが遅い) はカウントされません
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
        assertEquals(false, SpeechSettings(prefs).systemSilent.value)
        // 回数も設定に記録されます。アプリを再起動して再び応答しなくなるだけで十分です。
        SpeechSettings(prefs).noteSystemSilent()
        assertEquals(true, SpeechSettings(prefs).systemSilent.value)
    }

    @Test fun anAnswerInBetweenStartsTheCountOver() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        s.noteSystemSilent()
        s.noteSystemAnswered()
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
        s.noteSystemSilent()
        assertEquals(true, s.systemSilent.value)
    }

    @Test fun silentMarkSurvivesUntilAutoOrSystemIsPickedAgain() {
        for (retry in listOf(SpeechChoice.System, SpeechChoice.Auto)) {
            val prefs = MemoryPrefs()
            val s = SpeechSettings(prefs)
            s.noteSystemSilent()
            s.noteSystemSilent()
            assertEquals(true, s.systemSilent.value)
            assertEquals(true, SpeechSettings(prefs).systemSilent.value)
            // 選択が不明瞭です: マークはこの携帯電話の識別サービスを指します。
            s.set(SpeechChoice.Off)
            s.set(SpeechChoice.Provider("p", "m", TranscribeMethod.AudioTranscriptions))
            assertEquals(true, SpeechSettings(prefs).systemSilent.value)
            s.set(retry)
            assertEquals(false, s.systemSilent.value)
            assertEquals(false, SpeechSettings(prefs).systemSilent.value)
            // 回数もクリアされます。再度応答がない場合は、すぐに記録されます。
            s.noteSystemSilent()
            assertEquals(retry.toString(), false, s.systemSilent.value)
        }
    }

    @Test fun pickingAutoOrSystemClearsAHalfCount() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        s.noteSystemSilent()
        s.set(SpeechChoice.Auto)
        s.noteSystemSilent()
        assertEquals(false, s.systemSilent.value)
    }

    @Test fun earlierStoredMethodStillReads() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("speech_choice", """{"type":"provider","providerId":"p2","model":"m","method":"ChatInputAudio"}""").commit()
        assertEquals(SpeechChoice.Provider("p2", "m", TranscribeMethod.ChatInputAudio), SpeechSettings(prefs).choice.value)
    }

    @Test fun brokenJsonReadsAsAuto() {
        for (bad in listOf("{not json", "[]", """{"type":"nope"}""", """{"type":"provider","providerId":"p1"}""")) {
            val prefs = MemoryPrefs()
            prefs.edit().putString("speech_choice", bad).commit()
            assertEquals(bad, SpeechChoice.Auto, SpeechSettings(prefs).choice.value)
        }
    }

    /** 点呼サービスは、アプリを再度開いた後も引き続き存在します。その後、システムはデフォルトで応答し、電話をかけた相手は記憶されなくなります。 */
    @Test fun theServiceThatAnsweredIsRemembered() {
        val prefs = MemoryPrefs()
        SpeechSettings(prefs).noteSystemAnswered("a.b/.Two")
        assertEquals("a.b/.Two", SpeechSettings(prefs).systemService)
        SpeechSettings(prefs).noteSystemAnswered(null)
        assertEquals(null, SpeechSettings(prefs).systemService)
    }
}
