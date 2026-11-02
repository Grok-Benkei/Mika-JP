package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class AndroidSandboxRunTest {
    private val tmp: File = Files.createTempDirectory("android-sandbox-run").toFile()
    private val sb = AndroidSandbox(SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree()), { SandboxStatus.Ready(false) }, { emptyList() })

    @Test fun cwdOutsideWorkspaceRejected() = runBlocking {
        for (cwd in listOf("../..", "/etc", "/opt/mika/skills/x", "a/../../b")) {
            try {
                sb.run("c1", listOf("true"), cwd, 1_000) {}
                fail("cwd $cwd was accepted")
            } catch (e: SandboxError) {
                assertEquals(cwd, "path_outside_workspace", e.code)
            }
        }
    }

    /** スキル ディレクトリがまだ同期されていない場合は、最初に作成してからバインドします。ソース ディレクトリが見つからない場合、PRoot はコマンド出力に警告を書き込みます。 */
    @Test fun bindSourcesExistBeforeLaunch() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s3/files/sandbox"), File(tmp, "s3/cache"), File(tmp, "s3/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertFalse(dirs.skills.exists())
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // JVM にはランチャーがないため、起動自体が失敗します PRoot が
        assertTrue(dirs.skills.isDirectory)
        assertTrue(dirs.workspace("c1").isDirectory)
        assertTrue(dirs.tmp.isDirectory)
    }

    @Test fun existingSandboxGetsUpdatedClientScript() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "client/files/sandbox"), File(tmp, "client/cache"), File(tmp, "client/lib"), JVM_GATE, SandboxTree())
        val client = File(dirs.rootfs, "usr/local/bin/mika")
        client.parentFile!!.mkdirs()
        client.writeText("old bridge")
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            clientScript = { "new bridge".toByteArray() })
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // JVM で起動しない
        assertEquals("new bridge", client.readText())
        assertTrue(client.canExecute())
    }

    /** ツールを呼び出したときに実行環境の準備ができていません。準備を開始して待ちます。準備が完了すると準備完了となり、タイムアウトは現在の状態に戻ります。待機はサポートされていません。 */
    @Test fun awaitReadyStartsPreparationAndWaits() = runBlocking {
        var status: SandboxStatus = SandboxStatus.NotInstalled
        var starts = 0
        val dirs = SandboxDirs(File(tmp, "s4/files/sandbox"), File(tmp, "s4/cache"), File(tmp, "s4/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = {
            starts++
            kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(50); status = SandboxStatus.Ready(false) }
        })
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts)
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts) // は準備ができていますが、もう準備はできていません

        status = SandboxStatus.Installing(10, "解包")
        val slow = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(60_000) } })
        assertEquals(SandboxStatus.Installing(10, "解包"), slow.awaitReady(100))

        status = SandboxStatus.Unsupported("x86")
        assertEquals(SandboxStatus.Unsupported("x86"), AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { fail("must not prepare"); null }).awaitReady(100))
        status = SandboxStatus.Broken("坏了")
        assertEquals(SandboxStatus.Broken("坏了"), AndroidSandbox(dirs, { status }, { emptyList() }).awaitReady(100))
    }

    @Test fun preparerNeedsWork() {
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.NotInstalled, null, "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Ready(false), "v1", "v2", skipBroken = false)) // ミカがアップグレードされました
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Ready(true), "v2", "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = true))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Installing(5, "解包"), null, "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Unsupported("x86"), null, "v2", skipBroken = false))
    }

    @Test fun exit124OnlyCountsAsTimeoutAtTheLimit() {
        assertFalse(sb.launcherTimedOut(124, 200, 60))
        assertTrue(sb.launcherTimedOut(124, 59_600, 60))
        assertFalse(sb.launcherTimedOut(1, 60_000, 60))
    }

    @Test fun shareGoesToExporterWithItsOwnWorkspace() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s2/files/sandbox"), File(tmp, "s2/cache"), File(tmp, "s2/lib"), JVM_GATE, SandboxTree())
        val calls = mutableListOf<Pair<String, String>>()
        var fail: Exception? = null
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, exporter = { g, n ->
            // は、パスを渡したエクスポート ハンドラーに与えられます。そこからコンテンツが読み取られます。
            calls += String(g.read(0, 100)) to n; fail?.let { throw it }; com.wangzan101.mika.chat.SharedFile(n, g.size, "Download/Mika/$n", "content://x")
        })
        val f = sb.hostPath("c1", "out/a.txt", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals("Download/Mika/a.txt", sb.share(f, "a.txt"))
        assertEquals("a" to "a.txt", calls.single())
        // ワークスペース内のハード リンク (Mika のプライベート ファイルにリンクされている可能性がある) はエクスポートされません
        val private = File(tmp, "s2/private.db").apply { writeText("secret") }
        val linked = File(f.parentFile, "linked.db")
        Files.createLink(linked.toPath(), private.toPath())
        assertEquals("linked_file", runCatching { sb.share(linked, "linked.db") }.exceptionOrNull().let { (it as SandboxError).code })
        assertEquals(1, calls.size)
        fail = FileTooLarge(1L shl 30)
        assertEquals("file_too_large", runCatching { sb.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val outside = File(tmp, "elsewhere.txt").apply { writeText("x") }
        assertEquals("path_outside_workspace", runCatching { sb.share(outside, "x.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val none = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertEquals("share_unavailable", runCatching { none.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
    }

    // Web ページのアップロードとサンドボックスの同じパス ルール セット: このダイアログ ワークスペースでは通常のファイルのみを認識します
    @Test fun uploadCheckUsesWorkspaceRules() {
        val ok = sb.hostPath("c1", "in/a.pdf", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok, sb.uploadCheck("c1", "/workspace/in/a.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in/none.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/opt/mika/skills/skills/x/SKILL.md"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/workspace/../etc/passwd"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c2", "/workspace/in/a.pdf")) // 他の会話は表示されません
        // ワークスペースの外を指すリンクはたどりません。
        val outside = File(tmp, "secret.txt").apply { writeText("s") }
        Files.createSymbolicLink(File(ok.parentFile, "link.txt").toPath(), outside.toPath())
        assertTrue(sb.uploadCheck("c1", "/workspace/in/link.txt") != com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok)
    }
}
