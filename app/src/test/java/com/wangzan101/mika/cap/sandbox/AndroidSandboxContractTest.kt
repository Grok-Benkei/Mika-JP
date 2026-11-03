package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxPathContract
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AndroidSandbox のパス ルールが JVM 上で実行されます。ディレクトリが一時ディレクトリに変更され、プロセスを開始できません。 */
class AndroidSandboxContractTest : SandboxPathContract() {
    private val tmp: File = Files.createTempDirectory("android-sandbox").toFile()
    private fun dirs() = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    override fun sandbox(): SandboxPort = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
    override fun outsideDir(): File = File(tmp, "outside").apply { mkdirs() }

    // ファイルの読み取り、ファイルの検索、およびアップロード パスの確認には、ツリーに書き込む権限がありません。ワークスペース ディレクトリがスムーズに作成された場合、リセットが途中で完了したときに最後の rmdir は ENOTEMPTY になります。
    @Test fun lookingUpPathsNeverCreatesTheWorkspace() {
        val sb = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
        assertNotNull(sb.hostRoot("fresh", "/workspace/a.txt", forWrite = false))
        sb.uploadCheck("fresh", "/workspace/a.txt")
        assertFalse(dirs().workspace("fresh").exists())
        assertFalse(dirs().work.exists())
    }
}
