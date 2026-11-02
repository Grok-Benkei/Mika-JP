package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Test

class TaskRecoveryTest {
    private class FakeAudio(val modes: MutableMap<String, String>) : AudioPort {
        override fun mode(packageName: String) = modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean { modes[packageName] = "ignore"; return true }
        override fun setMode(packageName: String, mode: String): Boolean { modes[packageName] = mode; return true }
    }

    // レビュー フォーカス 5: 2 つのタスクがそれぞれ独自のアプリをミュートし、プロセスが強制終了されました。両方のレコードは次回起動時に復元されます。
    @Test fun restoresAllMutedPackagesOfAllRuns() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow", "clock.app" to "default"))
        val a = MemoryMarker()
        val b = MemoryMarker()
        assertTrue(AudioMute(audio, a).mute("settings.app"))
        assertTrue(AudioMute(audio, b).mute("clock.app"))

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a, "run-b" to b), audio, live = emptySet()))
        assertEquals("allow", audio.modes["settings.app"])
        assertEquals("default", audio.modes["clock.app"])
        assertNull(a.saved)
        assertNull(b.saved)
    }

    // 最初のタスクがまだ実行されている間は、2 番目のタスクのリカバリを移動できません。
    @Test fun leavesLiveRunsAlone() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow"))
        val a = MemoryMarker()
        AudioMute(audio, a).mute("settings.app")

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a), audio, live = setOf("run-a")))
        assertEquals("ignore", audio.modes["settings.app"])
        assertFalse(a.saved.isNullOrEmpty())
    }

    private class XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    // Focus 5 のクリップボード半分: クリップボードを介して貼り付けたときにミカが殺され、ユーザーの元のテキストは次回起動時にユーザーの元のテキストに戻されます。残りのコンテンツは暗号化され、使用後に削除されます。
    @Test fun restoresClipboardFromEncryptedBackupAfterKill() {
        var stored: String? = null
        val backup = ClipBackup({ stored }, { stored = it }, XorCipher())
        backup.save("用户原来的文字", "Mika 要粘贴的")
        assertFalse(stored!!.contains("用户原来的文字"))

        val restored = mutableListOf<Pair<String, String>>()
        assertTrue(TaskRecovery.restoreClip(ClipBackup({ stored }, { stored = it }, XorCipher())) { o, u -> restored += o to u })
        assertEquals(listOf("用户原来的文字" to "Mika 要粘贴的"), restored)
        assertNull(stored)
        // 痕跡を残さずに何もしない
        assertTrue(TaskRecovery.restoreClip(backup) { _, _ -> error("nothing to restore") })
    }
}
