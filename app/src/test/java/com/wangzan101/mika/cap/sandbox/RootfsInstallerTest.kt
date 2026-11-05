package com.wangzan101.mika.cap.sandbox

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** に付属する実行環境は、ホスト tar によって作成され、xz によって圧縮された小さなパッケージに置き換えられます。インターネットに接続されていません。 */
class RootfsInstallerTest {
    @get:Rule val texts = ChineseTexts()

    private val tmp: File = Files.createTempDirectory("rootfs-installer").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private val state = SandboxState(dirs, File(tmp, "files/sandbox-settings.json")) { listOf("arm64-v8a") }
    private val v1: ByteArray = makeBundle("one")
    private var opens = 0

    private fun bundle(bytes: ByteArray = v1, version: String = "v1", sha: String = sha256(bytes), open: (() -> InputStream)? = null) =
        RuntimeBundle(version, sha, 64L shl 20, 1000, open ?: { opens++; ByteArrayInputStream(bytes) })

    private fun installer(
        b: RuntimeBundle = bundle(),
        client: ByteArray? = "#!/usr/bin/env python3\n".toByteArray(),
        free: Long = 4L shl 30,
        selfTest: Pair<Int, String> = 0 to "mikarun ok\nseccomp ok\n",
    ) = RootfsInstaller(
        dirs, state, b,
        abis = { listOf("arm64-v8a") },
        freeBytes = { free },
        selfTest = { selfTest },
        dnsServers = { emptyList() },
        procReadable = { it != "/proc/stat" },
        prepareDev = {},
        clientScript = { client },
    )

    @Test
    fun installsBundleWithoutNetwork() = runBlocking {
        val steps = mutableListOf<InstallStep>()
        installer().install { s, _ -> steps += s }
        assertEquals(listOf(InstallStep.Check, InstallStep.Extract, InstallStep.Configure, InstallStep.Skills, InstallStep.Done), steps)
        assertEquals(SandboxStatus.Ready(network = false), state.status.value)
        assertEquals("v1", state.installedVersion)
        assertEquals("one\n", File(dirs.rootfs, "etc/marker").readText())
        assertEquals("127.0.0.1 localhost\n::1 localhost\n", File(dirs.rootfs, "etc/hosts").readText())
        assertEquals("# device DNS unavailable; name lookup disabled\n", File(dirs.rootfs, "etc/resolv.conf").readText())
        assertFalse(File(dirs.rootfs, "etc/resolv.conf").readText().contains("1.1.1.1"))
        assertTrue(File(dirs.rootfs, "usr/local/bin/mika").canExecute())
        assertEquals(listOf("stat"), dirs.procStandIns.list()!!.toList())
        assertFalse(File(dirs.root, "rootfs.new").exists())
        assertFalse(File(dirs.root, "rootfs.old").exists())
    }

    @Test
    fun corruptBundleIsNotExtracted() = runBlocking {
        expectFailure { installer(bundle(sha = "0".repeat(64))).install { _, _ -> } }
        assertFalse("rootfs must not be created", Files.exists(dirs.rootfs.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(dirs.root, "rootfs.new").exists())
        assertTrue(state.status.value is SandboxStatus.Broken)
        assertEquals(InstallStep.Check, state.completedStep())
    }

    @Test
    fun truncatedBundleIsNotExtracted() = runBlocking {
        expectFailure { installer(bundle(bytes = v1.copyOf(v1.size / 2), sha = sha256(v1))).install { _, _ -> } }
        assertFalse(Files.exists(dirs.rootfs.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(dirs.root, "rootfs.new").exists())
    }

    @Test
    fun resumesAfterFailedStep() = runBlocking {
        expectFailure { installer(client = null).install { _, _ -> } }
        assertEquals(InstallStep.Extract, state.completedStep())
        assertTrue(state.status.value is SandboxStatus.Broken)
        // ステータスの再読み取り（プロセスの再起動など）も準備ができていません
        assertTrue(SandboxState(dirs, File(tmp, "files/sandbox-settings.json")) { listOf("arm64-v8a") }.status.value is SandboxStatus.Broken)
        val steps = mutableListOf<InstallStep>()
        installer().install { s, _ -> steps += s }
        assertEquals(InstallStep.Configure, steps.first())
        assertEquals(1, opens)
        assertEquals(SandboxStatus.Ready(network = false), state.status.value)
    }

    @Test
    fun interruptedExtractionStartsOver() = runBlocking {
        // 同様のプロセスが解凍中に強制終了されました: ロギングは Check で停止し、半分の rootfs.new が残ります
        installer().install { _, _ -> }
        state.markStep(InstallStep.Check)
        File(dirs.root, "rootfs.new/half").apply { parentFile?.mkdirs() }.writeText("x")
        installer().install { _, _ -> }
        assertFalse(File(dirs.root, "rootfs.new").exists())
        assertEquals(SandboxStatus.Ready(network = false), state.status.value)
    }

    @Test
    fun installWhenReadyDoesNothing() = runBlocking {
        installer().install { _, _ -> }
        val steps = mutableListOf<InstallStep>()
        installer().install { s, _ -> steps += s }
        assertEquals(listOf(InstallStep.Done), steps)
        assertEquals(1, opens)
    }

    @Test
    fun newBundledVersionReplacesRootfsAndKeepsWorkspaces() = runBlocking {
        installer().install { _, _ -> }
        File(dirs.rootfs, "usr/lib/stale").apply { parentFile?.mkdirs() }.writeText("old")
        val ws = File(dirs.work, "c-conv1/notes.txt").apply { parentFile?.mkdirs(); writeText("keep") }
        installer(bundle(makeBundle("two"), version = "v2")).install { _, _ -> }
        assertEquals("v2", state.installedVersion)
        assertEquals("two\n", File(dirs.rootfs, "etc/marker").readText())
        assertFalse("files of the old runtime are gone", File(dirs.rootfs, "usr/lib/stale").exists())
        assertEquals("keep", ws.readText())
        assertEquals(SandboxStatus.Ready(network = false), state.status.value)
    }

    @Test
    fun corruptUpgradeKeepsOldRootfs() = runBlocking {
        installer().install { _, _ -> }
        expectFailure { installer(bundle(makeBundle("two"), version = "v2", sha = "0".repeat(64))).install { _, _ -> } }
        assertEquals("one\n", File(dirs.rootfs, "etc/marker").readText())
        assertFalse(File(dirs.root, "rootfs.new").exists())
    }

    @Test
    fun notEnoughSpaceStopsAtCheck() = runBlocking {
        expectFailure { installer(free = 10L shl 20).install { _, _ -> } }
        assertEquals(0, opens)
        assertEquals(null, state.completedStep())
        assertEquals("空き容量が足りません: 128 MB が必要です(空き 10 MB)", (state.status.value as SandboxStatus.Broken).reason)
    }

    @Test
    fun selfTestFailureStopsAtCheck() = runBlocking {
        expectFailure { installer(selfTest = 125 to "mikarun: cannot exec\n").install { _, _ -> } }
        assertEquals(0, opens)
        assertEquals(null, state.completedStep())
        assertTrue(state.status.value is SandboxStatus.Broken)
    }

    @Test
    fun resetDeletesReadOnlyTree() = runBlocking {
        val outside = File(tmp, "outside").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val ro = File(dirs.rootfs, "usr/lib/ro").apply { mkdirs() }
        File(ro, "file").writeText("x")
        Files.setPosixFilePermissions(File(ro, "file").toPath(), PosixFilePermissions.fromString("r--r--r--"))
        Files.createSymbolicLink(File(ro, "out").toPath(), outside.toPath())
        Files.setPosixFilePermissions(ro.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        File(dirs.work, "conv1").mkdirs()
        state.markStep(InstallStep.Done)

        installer().reset()
        assertFalse(dirs.root.exists())
        assertTrue("symlink target outside must survive", keep.exists())
        assertEquals(SandboxStatus.NotInstalled, state.status.value)
        assertEquals(null, state.completedStep())
    }

    /**
     * files/sandbox/ の書き込み (スキルの同期、コマンドの実行など) とリセットは相互に排他的です。リセットは書き込みが完了するまで待ってから削除します。また、半分が削除されるまでは誰もディレクトリを作成しません。 ライセンス期間中にリセットされた
     * およびその他の同期は書き込まれなくなります (実マシン上でアプリが起動されたときの同期はリセットに達し、最後の rmdir は ENOTEMPTY を報告します)。
     */
    @Test
    fun resetWaitsForWritersAndLateSyncSkips() = runBlocking {
        installer().install { _, _ -> }
        assertTrue(state.status.value is SandboxStatus.Ready)
        val writing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = launch(kotlinx.coroutines.Dispatchers.IO) {
            dirs.tree.shared {
                writing.complete(Unit)
                release.await()
                File(dirs.work, "c-1/out.txt").apply { parentFile?.mkdirs(); writeText("x") }
            }
        }
        writing.await()
        val reset = launch(kotlinx.coroutines.Dispatchers.IO) { installer().reset() }
        // リセット キューがキューに入れられると、新しいライターもその後ろにキューに入れられます
        val late = launch(kotlinx.coroutines.Dispatchers.IO) {
            SkillsSync.sync(dirs, mapOf("skills/a/scripts/manifest.json" to "[]".toByteArray())) { state.status.value is SandboxStatus.Ready }
        }
        kotlinx.coroutines.delay(200)
        assertFalse("reset must wait for the writer", reset.isCompleted)
        assertTrue(dirs.rootfs.exists())
        release.complete(Unit)
        writer.join(); reset.join(); late.join()
        assertFalse(dirs.root.exists())
        assertEquals(SandboxStatus.NotInstalled, state.status.value)
    }

    @Test
    fun treeHoldsNestWithoutDeadlock() = runBlocking {
        val r = kotlinx.coroutines.withTimeout(5_000) {
            dirs.tree.exclusive { dirs.tree.shared { dirs.tree.exclusive { 42 } } }
        }
        assertEquals(42, r)
    }

    @Test
    fun clearWorkspacesKeepsRootfs() = runBlocking {
        File(dirs.rootfs, "bin").mkdirs()
        File(dirs.work, "conv1/a.txt").apply { parentFile?.mkdirs(); writeText("a") }
        installer().clearWorkspaces()
        assertFalse(dirs.work.exists())
        assertTrue(File(dirs.rootfs, "bin").exists())
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        fail("expected install to fail")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 小さなルート ファイル システム: bin/sh、etc/marker、複数のファイル。 tar パッケージをホストし、xz で圧縮します。 */
    private fun makeBundle(marker: String): ByteArray {
        val src = Files.createTempDirectory(tmp.toPath(), "src").toFile()
        File(src, "bin").mkdirs()
        File(src, "bin/sh").writeText("#!/bin/busybox\n")
        File(src, "etc/marker").apply { parentFile?.mkdirs() }.writeText("$marker\n")
        File(src, "etc/hosts").writeText("old\n")
        for (n in 1..5) File(src, "usr/share/f$n").apply { parentFile?.mkdirs(); writeText("data $n\n") }
        val tar = File(tmp, "rootfs-$marker.tar")
        val p = ProcessBuilder("tar", "-cf", tar.path, "-C", src.path, ".").redirectErrorStream(true)
            .apply { environment()["COPYFILE_DISABLE"] = "1" }.start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "tar failed: $log" }
        RootfsExtractor.deleteTree(src)
        val out = java.io.ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { it.write(tar.readBytes()) }
        tar.delete()
        return out.toByteArray()
    }
}
