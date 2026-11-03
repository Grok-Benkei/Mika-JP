package com.wangzan101.mika.cap.phone.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** サイレント レコード: 解析、ルールの復元 (書き込み許可が設定されていない)、ファイルのドロップ、リカバリの開始、レコード内のパッケージの移動のみ。 */
class AudioMuteLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "ledger")

    @Test fun parseDistinguishesUnsetFromEveryMode() {
        assertNull(AudioMuteLedger.parseSetMode("No operations."))
        assertNull(AudioMuteLedger.parseSetMode(""))
        assertEquals("allow", AudioMuteLedger.parseSetMode("PLAY_AUDIO: allow; time=+1d"))
        assertEquals("ignore", AudioMuteLedger.parseSetMode("PLAY_AUDIO: ignore; time=+2s"))
        assertEquals("deny", AudioMuteLedger.parseSetMode("PLAY_AUDIO: deny"))
        assertEquals("default", AudioMuteLedger.parseSetMode("PLAY_AUDIO: default"))
        // getAudioMode 外部: 設定されておらず、明確なデフォルトは「default」です。
        assertEquals("default", ShellService.parsePlayAudioMode("No operations."))
        assertEquals("ignore", ShellService.parsePlayAudioMode("PLAY_AUDIO: ignore"))
    }

    @Test fun restoreModeWritesAllowForUnsetAndKeepsExplicitModes() {
        assertEquals("allow", AudioMuteLedger.restoreMode("default"))
        assertEquals("allow", AudioMuteLedger.restoreMode(null))
        assertEquals("allow", AudioMuteLedger.restoreMode(""))
        for (m in listOf("allow", "ignore", "deny", "foreground")) assertEquals(m, AudioMuteLedger.restoreMode(m))
    }

    @Test fun recordsSurviveRestartAndAreRemovedOnRestore() {
        val a = AudioMuteLedger(file())
        a.put("a.app", "default")
        a.put("b.app", "deny")
        val b = AudioMuteLedger(file()) // プロセスが強制終了された後に再起動されます
        assertEquals(listOf("a.app", "b.app"), b.packages())
        assertEquals("deny", b["b.app"])
        b.remove("a.app")
        b.remove("b.app")
        assertFalse(file().exists())
        assertTrue(AudioMuteLedger(file()).packages().isEmpty())
    }

    @Test fun startupRestoreOnlyTouchesRecordedPackagesAndKeepsFailures() {
        AudioMuteLedger(file()).apply { put("a.app", "default"); put("b.app", "deny"); put("c.app", "allow") }
        val written = mutableListOf<Pair<String, String>>()
        val ledger = AudioMuteLedger(file())
        val all = ledger.restoreAll { pkg, mode -> written += pkg to mode; pkg != "c.app" }
        assertFalse(all)
        assertEquals(listOf("a.app" to "allow", "b.app" to "deny", "c.app" to "allow"), written)
        // 失敗したものはそのままにして、次回もう一度試してください。成功したものを削除します。
        assertEquals(listOf("c.app"), AudioMuteLedger(file()).packages())
        assertTrue(AudioMuteLedger(file()).restoreAll { _, _ -> true })
        assertFalse(file().exists())
    }

    @Test fun unwritableLocationFallsBackToMemory() {
        val ledger = AudioMuteLedger(File(tmp.root, "no/such/dir/ledger"))
        ledger.put("a.app", "ignore")
        assertEquals("ignore", ledger["a.app"])
    }

    @Test fun garbageLinesAreIgnored() {
        file().writeText("a.app default\nnot a record\n; rm -rf /\nb.app ignore\n")
        assertEquals(listOf("a.app", "b.app"), AudioMuteLedger(file()).packages())
    }

    @Test fun copyInAnotherUserKeepsItsOwnRecord() {
        AudioMuteLedger(file()).apply { put("a.app", "allow"); put(AppAudioKey.of("a.app", 10), "deny") }
        val back = AudioMuteLedger(file())
        assertEquals(listOf("a.app", "a.app@10"), back.packages())
        assertEquals("deny", back["a.app@10"])
    }
}
