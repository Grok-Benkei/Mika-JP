package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.util.UUID

/**
 * Web ページアップロード用の転送領域: ワークスペース内のファイルは、[gate] によって一度開かれ、ハンドルで検証され、このハンドルから [root]/<ランダム>/ にコピーされます。
 * [root] はアプリのプライベート キャッシュ ディレクトリ内にあり、サンドボックスからはアクセスできません。このコピーのみがページに渡され、チェック後にワークスペース内のファイルをどのように変更しても影響を受けません。
 */
class UploadStaging(
    private val root: File,
    private val gate: WorkspaceFileGate,
    private val maxFileBytes: Long = 100L shl 20,
    private val maxTotalBytes: Long = 200L shl 20,
) {
    /** アップロードされたコピー 1 つ: [dir] 使用後、ディレクトリ全体が削除されます。 */
    class Staged(val dir: File, val files: List<File>)

    sealed interface Result {
        class Ok(val staged: Staged) : Result
        /** [error]:path_outside_workspace、file_not_found、linked_file、file_too_large。 */
        class Refused(val error: String, val path: String) : Result
    }

    fun stage(workspaceDir: File, paths: List<String>): Result {
        val dir = File(root, UUID.randomUUID().toString())
        val out = mutableListOf<File>()
        var total = 0L
        try {
            for ((i, p) in paths.withIndex()) {
                val rel = SandboxPaths.normalize(p)?.takeIf(SandboxPaths::isWritable)?.removePrefix(SandboxPaths.WORKSPACE)?.trimStart('/')
                    ?.takeIf { it.isNotEmpty() } ?: return refuse(dir, "path_outside_workspace", p)
                val g = try { gate.openRead(workspaceDir, rel) } catch (e: SandboxError) { return refuse(dir, codeOf(e), p) }
                g.use {
                    if (g.size > maxFileBytes || total + g.size > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                    // 各ファイルには 1 レベルのディレクトリがあります。異なるディレクトリにある同じ名前のファイルは、元の名前を保持します。
                    val target = File(File(dir, i.toString()).apply { mkdirs() }, g.name)
                    var copied = 0L
                    target.outputStream().use { o ->
                        val input = g.input()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            // 検証後、ファイルは再び大きくなりました。計算は依然として上限に基づいています。
                            if (copied > maxFileBytes || total + copied > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                            o.write(buf, 0, n)
                        }
                    }
                    total += copied
                    out += target
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return Result.Ok(Staged(dir, out))
    }

    fun delete(staged: Staged) {
        staged.dir.deleteRecursively()
    }

    /** すべてのコピーを削除します (開始時に最後のプロセスによって残されたコピーをクリアします)。 */
    fun clear() {
        root.deleteRecursively()
    }

    private fun refuse(dir: File, error: String, path: String): Result {
        dir.deleteRecursively()
        return Result.Refused(error, path)
    }

    private fun codeOf(e: SandboxError) = when (e.code) {
        WorkspaceFileGate.OUTSIDE -> "path_outside_workspace"
        WorkspaceFileGate.LINKED_FILE -> WorkspaceFileGate.LINKED_FILE
        else -> "file_not_found"
    }

    companion object {
        const val FILE_TOO_LARGE = "file_too_large"
        /** キャッシュ ディレクトリの下のステージング領域。 FileProvider はこのディレクトリ (res/xml/web_files.xml) のみを開きます。 */
        const val DIR = "webupload"
    }
}
