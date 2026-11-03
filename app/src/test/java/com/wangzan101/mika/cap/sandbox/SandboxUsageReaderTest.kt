package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 使用済みスペース: サイズがリンクに従っていません。ハンドルによるクリーンアップ削除は、リンクをたどりません。実行中のタスクのダイアログが不明瞭です。 */
class SandboxUsageReaderTest {
    private val tmp: File = Files.createTempDirectory("usage").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private val active = mutableListOf<String>()
    private val reader = SandboxUsageReader(dirs, { listOf(ConversationSummary("a", "查快递", 0), ConversationSummary("b", "整理表格", 0)) }, { active })

    /** はワークスペースの外にある大きなファイルであり、ワークスペース内にそのファイル (およびそのファイルが配置されているディレクトリ) へのリンクがあります。 */
    private val outside = File(tmp, "outside").apply { mkdirs() }
    private val big = File(outside, "big.bin").apply { writeBytes(ByteArray(50_000)) }

    private fun workspace(id: String, bytes: Int): File = dirs.workspace(id).apply {
        mkdirs()
        File(this, "inbox").mkdirs()
        File(this, "inbox/a.txt").writeBytes(ByteArray(bytes))
        Files.createSymbolicLink(File(this, "link-file").toPath(), big.toPath())
        Files.createSymbolicLink(File(this, "link-dir").toPath(), outside.toPath())
    }

    @Test fun sizesDoNotFollowLinksAndNameTheChat() {
        workspace("a", 300)
        workspace("b", 1_000)
        File(dirs.work, "c-gone").apply { mkdirs(); File(this, "x").writeBytes(ByteArray(10)) }
        File(dirs.rootfs, "bin").apply { mkdirs(); File(this, "sh").writeBytes(ByteArray(70)) }
        active += "a"
        val u = reader.read()
        assertEquals(listOf(1_000L, 300L, 10L), u.workspaces.map { it.bytes })
        assertEquals(listOf("整理表格", "查快递", null), u.workspaces.map { it.title })
        assertEquals(listOf(false, true, false), u.workspaces.map { it.busy })
        assertNull("会話が削除されている場合は識別できない", u.workspaces.last().conversationId)
        assertEquals(70L, u.runtime)
        assertEquals(70L + 1_310L, u.total)
    }

    @Test fun clearingDeletesOnlyTheWorkspaceNotWhatLinksPointTo() = runBlocking {
        val b = workspace("b", 1_000)
        assertTrue(reader.clear(b.name))
        assertFalse(b.exists())
        assertTrue("リンク先のファイルは残っている", big.isFile)
        assertEquals(50_000L, big.length())
    }

    @Test fun aChatWithARunningTaskIsNotCleared() = runBlocking {
        val a = workspace("a", 300)
        val b = workspace("b", 1_000)
        active += "a"
        assertFalse(reader.clear(a.name))
        assertTrue(File(a, "inbox/a.txt").isFile)
        assertEquals("実行中の 1 件を残す", 1, reader.clearAll())
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(big.isFile)
    }
}
