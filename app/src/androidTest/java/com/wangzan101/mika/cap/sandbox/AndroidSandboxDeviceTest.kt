package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** AndroidSandbox は実マシン上でコマンドを実行します。ルート ファイル システムは、インストールされたサンドボックスには触れずに、スモーク テスト方法に従って files/sandbox-test/rootfs に準備されます。 */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxDeviceTest {
    // は、実際にインストールされたサンドボックスとは別に、自分で解決したルート ファイル システムをテストします
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, ws: String = "dev-a", timeoutMs: Long = 30_000): CommandResult =
        runBlocking { sb.run(ws, listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun echoRuns() {
        val r = sh("echo hi")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("hi\n", r.output)
        assertFalse(r.network)
    }

    @Test fun commandTimezoneFollowsPhone() {
        val zone = java.time.ZoneId.systemDefault()
        assertEquals(ProcRunner.timezone(), sh("printf '%s' \"\$TZ\"").output)
        checkTimezone(zone, "")
    }

    @Test fun bundledTimezonesSupportDstAndFractionalOffsets() {
        for (id in listOf("Asia/Shanghai", "America/New_York", "Asia/Kathmandu", "UTC")) {
            checkTimezone(java.time.ZoneId.of(id), "export TZ='$id'; ")
        }
    }

    private fun checkTimezone(zone: java.time.ZoneId, prefix: String) {
        // 冬と夏に 1 回測定: 現時点では、携帯電話の UTC オフセットを年間を通じてのオフセットにプログラムすることはできません。
        for (date in listOf("2026-01-15T04:08:50Z", "2026-07-15T04:08:50Z")) {
            val instant = java.time.Instant.parse(date)
            val seconds = instant.epochSecond
            val expected = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
                .format(instant.atZone(zone))
            val shell = sh(prefix + "date -d '@$seconds' '+%Y-%m-%d %H:%M:%S %z'")
            assertEquals(shell.output, 0, shell.exitCode)
            assertEquals(expected, shell.output.trim())
            val python = sh(prefix + "python3 -c \"import time; print(time.strftime('%Y-%m-%d %H:%M:%S %z', time.localtime($seconds)))\"")
            assertEquals(python.output, 0, python.exitCode)
            assertEquals(expected, python.output.trim())
            val node = sh(prefix + "node -e \"console.log(-new Date(${seconds}000).getTimezoneOffset()*60)\"")
            assertEquals(node.output, 0, node.exitCode)
            assertEquals(zone.rules.getOffset(instant).totalSeconds, node.output.trim().toInt())
        }
    }

    @Test fun exitCodeAndOutput() {
        val r = sh("echo out; echo err >&2; pwd; exit 3")
        assertEquals(r.output, 3, r.exitCode)
        assertEquals(listOf("out", "err", "/workspace"), r.output.trim().lines())
    }

    @Test fun timeoutKillsAndReports() {
        val begin = System.currentTimeMillis()
        val r = sh("sleep 30", timeoutMs = 2_000)
        assertTrue(r.output, r.timedOut)
        assertEquals(124, r.exitCode)
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 8_000)
    }

    @Test fun endlessOutputIsBounded() {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val r = sh("yes", timeoutMs = 3_000)
        val peak = rt.totalMemory() - rt.freeMemory()
        assertTrue(r.timedOut)
        assertTrue("dropped=${r.droppedBytes}", r.droppedBytes > 0)
        assertTrue(r.output.length <= ProcRunner.OUTPUT_LIMIT)
        assertTrue("heap grew ${(peak - before) shr 20} MB", peak - before < 8L shl 20)
    }

    @Test fun workspacesAreSeparate() {
        assertEquals(0, sh("echo a > only-a.txt", ws = "dev-sep-a").exitCode)
        assertEquals(0, sh("test -e only-a.txt", ws = "dev-sep-a").exitCode)
        assertTrue(sh("test -e only-a.txt", ws = "dev-sep-b").exitCode != 0)
    }

    @Test fun symlinkOutOfWorkspaceRejected() {
        val r = sh("rm -f x p; ln -s /proc/self/root/data x; ln -s /data/data/${context.packageName}/shared_prefs p; ls -la x p")
        assertEquals(r.output, 0, r.exitCode)
        assertNull(sb.hostPath("dev-a", "x", forWrite = false))
        assertNull(sb.hostPath("dev-a", "x/data", forWrite = false))
        assertNull(sb.hostPath("dev-a", "p", forWrite = true))
    }

    @Test fun cannotSeeSdcard() {
        val r = sh("ls /sdcard /storage/emulated/0 2>&1")
        assertTrue(r.output, r.exitCode != 0 || r.output.isBlank())
        // は、/proc/self/root を通じてホストのルート ディレクトリにバイパスされても表示されません。
        val viaProc = sh("ls /proc/self/root/sdcard /proc/self/root/storage/emulated/0 2>&1")
        assertTrue(viaProc.output, viaProc.exitCode != 0 || viaProc.output.isBlank())
    }

    /**
     * リリースゲート: ネットワークが切断されると、サンドボックス内のホストのソケットにアクセスできなくなります (dnsproxyd はサンドボックスのドメイン名を解決できます。これは、ネットワークから抜け出す方法を開くのと同じです)。
     * それらはすべて失敗する必要があります。サンドボックスは、実際のマシンで実行されるまで解放してはなりません。
     */
    @Test fun cannotReachHostSocketsWhenNetOff() {
        assertTrue(sh("ls /proc/self/root/dev/socket 2>&1").let { it.exitCode != 0 })
        val connect = sh(
            "if command -v python3 >/dev/null; then " +
                "python3 -c \"import socket; s = socket.socket(socket.AF_UNIX); s.connect('/proc/self/root/dev/socket/dnsproxyd')\"; " +
                "else echo x | nc -U /proc/self/root/dev/socket/dnsproxyd; fi 2>&1",
        )
        assertTrue(connect.output, connect.exitCode != 0)
        assertTrue(sh("ls /proc/1/root 2>&1").let { it.exitCode != 0 })
        val dns = sh("nslookup example.com 2>&1", timeoutMs = 20_000)
        assertTrue(dns.output, dns.exitCode != 0)
        assertFalse(dns.network)
    }

    /**
     * リリースゲート: /proc/self/root および /proc/1/root 経由でホストにアクセスできず、ホストのアプリ データ ディレクトリもこのアプリのshared_prefs も読み取ることができません。
     * `readlink /proc/self/root` は、ホスト上のルート ファイル システムのパスを表示します (PRoot はこのリンクを変換しません)。単にパスの文字列です。
     * フォローしてもまだサンドボックスの中にいて、そこにも到達できません。
     */
    @Test fun procRootStaysInGuest() {
        val pkg = context.packageName
        for (p in listOf(
            "/proc/self/root/data/data",
            "/proc/self/root/data/data/$pkg/shared_prefs",
            "/proc/self/root/data/user/0/$pkg/shared_prefs",
            "\"$(readlink /proc/self/root)/../../shared_prefs\"",
            "/proc/1/root",
        )) {
            val r = sh("ls $p 2>&1")
            assertTrue("$p: ${r.output}", r.exitCode != 0)
            assertTrue("$p: ${r.output}", r.output.contains("No such file or directory") || r.output.contains("Permission denied"))
        }
    }

    @Test fun fullOutputIsSavedInWorkspace() {
        val path = sb.saveOutput("dev-a", "完整输出")
        assertEquals("完整输出", sh("cat $path").output)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            SandboxSmokeTest.prepareTestSandbox()
        }
    }
}
