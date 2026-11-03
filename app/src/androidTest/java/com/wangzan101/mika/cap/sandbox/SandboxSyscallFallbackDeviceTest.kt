package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一部の Android バージョンでは、アプリが新しいシステム コール (io_uring、clone3、faccessat2、epoll_pwait2、rseq...) を行うために seccomp ポリシーをインストールします。
 * は SIGSYS を送信し、一部は直接解放されます。サンドボックス内のプログラムは、両方のプラットフォームのシグナルによって強制終了できません。ブロックされた呼び出しは「サポートされていません」(ENOSYS) を受け取ります。
 * は独自の撤退を行い、解放された呼び出しはカーネル独自の結果を取得します。パスを含む呼び出しは、サンドボックス内のパスに従って判断する必要があります。
 * すべて製品パス AndroidSandbox.run → mikarun → PRoot 経由。
 */
@RunWith(AndroidJUnit4::class)
class SandboxSyscallFallbackDeviceTest {
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { sb.run("dev-sigsys", listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun nodeEvaluatesJs() {
        val r = sh("node -e 'console.log(1+1)'")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("2", r.output.trim())
    }

    @Test fun nodeFsAndTimers() {
        val script = """
            const fs = require('fs');
            fs.writeFileSync('sigsys-node.txt', 'written');
            setTimeout(async () => {
              const text = await fs.promises.readFile('sigsys-node.txt', 'utf8');
              fs.unlinkSync('sigsys-node.txt');
              setImmediate(() => console.log('read:' + text));
            }, 50);
        """.trimIndent()
        val r = sh("cat > sigsys-node.js <<'EOF'\n$script\nEOF\nnode sigsys-node.js; rc=\$?; rm -f sigsys-node.js; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("read:written", r.output.trim())
    }

    /** musl の faccessat(..., AT_EACCESS) まず faccessat2 を試してください。答えは実際の権限と一致している必要があります。 */
    @Test fun effectiveAccessChecks() {
        val script = """
            import os
            open('sigsys-acc.txt', 'w').close()
            os.chmod('sigsys-acc.txt', 0o644)
            print(os.access('sigsys-acc.txt', os.R_OK, effective_ids=True),
                  os.access('sigsys-acc.txt', os.X_OK, effective_ids=True),
                  os.access('/bin/sh', os.X_OK, effective_ids=True),
                  os.access('sigsys-missing', os.F_OK, effective_ids=True))
            os.remove('sigsys-acc.txt')
        """.trimIndent()
        val r = sh(
            "cat > sigsys-acc.py <<'EOF'\n$script\nEOF\npython3 sigsys-acc.py; rc=\$?; rm -f sigsys-acc.py; " +
                "touch sigsys-t; [ -r sigsys-t ] && echo test-r-ok; rm -f sigsys-t; exit \$rc",
        )
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("True False True False", "test-r-ok"), r.output.trim().lines())
    }

    /**
     * プロセスは生きており、各呼び出しは -1 を返します: プラットフォームによってブロックされている場合は ENOSYS。プラットフォームによってリリースされた場合、カーネルはここでの無効なパラメータに従ってエラー (EINVAL) を報告します。
     * io_uring_setup は、PRoot 自体の ENOSYS によって応答されます (io_uring のパス操作はシステムによって呼び出されず、PRoot によって変換できません)。これはプラットフォームとは関係ありません。
     */
    @Test fun trappedSyscallsReturnEnosys() {
        // io_uring_setup、clone3、epoll_pwait2、rseq(arm64 番号)
        val script = """
            import ctypes, errno
            libc = ctypes.CDLL(None, use_errno=True)
            for nr in (425, 435, 441, 293):
                ctypes.set_errno(0)
                rc = libc.syscall(nr, 0, 0, 0, 0, 0, 0)
                print(nr, rc, errno.errorcode.get(ctypes.get_errno()))
        """.trimIndent()
        val r = sh("cat > sigsys-nr.py <<'EOF'\n$script\nEOF\npython3 sigsys-nr.py; rc=\$?; rm -f sigsys-nr.py; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        val lines = r.output.trim().lines()
        assertEquals(r.output, 4, lines.size)
        assertEquals("425 -1 ENOSYS", lines[0])
        for ((line, nr) in lines.drop(1).zip(listOf(435, 441, 293))) {
            assertTrue(r.output, line == "$nr -1 ENOSYS" || line == "$nr -1 EINVAL")
        }
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
