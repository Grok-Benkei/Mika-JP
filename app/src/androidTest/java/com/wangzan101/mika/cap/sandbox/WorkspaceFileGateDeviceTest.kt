package com.wangzan101.mika.cap.sandbox

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.workspace.FdSys
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGateContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** は、ハンドルの Android 実装に従って同じパス ルールのセットを実行します。さらに、実際のマシンでのみ測定されたものに加えて、FIFO はブロックされず、ファイルはチェック後に変更できず、外部に触れることができません。 */
@RunWith(AndroidJUnit4::class)
class WorkspaceFileGateDeviceTest : WorkspaceFileGateContract() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    override val sys: FdSys = AndroidFdSys
    override fun tempDir(): File = File(context.filesDir, "gate-test-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() { tmp.deleteRecursively() }

    @Test fun fifoRefusedWithoutBlocking() {
        Os.mkfifo(File(ws, "pipe").path, 0x180)
        val begin = System.currentTimeMillis()
        for (op in listOf<() -> Unit>({ gate.openRead(ws, "pipe").close() }, { gate.openWrite(ws, "pipe", append = false).close() })) {
            try { op(); fail("FIFO accepted") } catch (e: SandboxError) { assertEquals("not_a_file", e.code) }
        }
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 3_000)
    }

    /** を開いた後、ディレクトリを外部を指すリンクに変更します。ハンドルでの読み取りと書き込みは元のファイルのままであり、外部ファイルは影響を受けません。 */
    @Test fun swapAfterOpenDoesNotRedirect() {
        File(ws, "d").mkdirs(); File(ws, "d/a.txt").writeText("inside")
        gate.openRead(ws, "d/a.txt").use { g ->
            assertTrue(File(ws, "d").renameTo(File(ws, "d-old")))
            Os.symlink(secret.path, File(ws, "d").path)
            assertEquals("inside", String(g.read(0, 100)))
        }
        val w = gate.createNew(ws, "e/new.txt")
        File(ws, "e").renameTo(File(ws, "e-old")); Os.symlink(secret.path, File(ws, "e").path)
        w.use { it.write("x".toByteArray()) }
        assertFalse(File(secret, "new.txt").exists())
        assertEquals("x", File(ws, "e-old/new.txt").readText())
    }

    /** /proc/self/fd によって読み取られるルート ディレクトリとファイル パスは同じソースからのものです。 /data/user/0と/data/dataの書き込みの違いは判定に影響しません。 */
    @Test fun realPathsComeFromTheHandle() {
        File(ws, "x.txt").writeText("1")
        gate.openRead(File(context.dataDir, ws.relativeTo(context.dataDir).path), "x.txt").use { assertEquals(1L, it.size) }
    }
}
