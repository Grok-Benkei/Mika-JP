package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/** がブランチ ワークスペースをコピーした結果: [skipped] は没収されたアイテム (シンボリック リンク、範囲外のパス、特殊なファイル) です。 [truncated] が true の場合、上限を超えるため、ファイルはコピーされません。 */
data class BranchCopyResult(
    val copied: Int,
    val skipped: Int = 0,
    /** 上限を超えたためコピーを中止します。 */
    val truncated: Boolean = false,
)

/** は、分岐時にソース会話のワークスペースをコピーします (ユーザーが inbox/ にアタッチしている限り)。ステージ 2 は会話ビューによって挿入されます。 */
interface BranchWorkspace {
    /**
     * ソース会話ワークスペースの受信トレイ/のみをコピーします。ターゲット ワークスペースが存在しない場合は作成されます。
     * ソース ワークスペース/サンドボックスが利用できず、ソースに受信ボックスがない場合は、null が返されます。ターゲットにすでに受信トレイがある場合、上書きされず、スキップ = 1 が返されます。
     */
    suspend fun copyInbox(
        sourceId: String,
        targetId: String,
        maxBytes: Long = 200L * 1024 * 1024,
    ): BranchCopyResult?
}

/** 携帯電話での実装: 両方のワークスペースは files/sandbox/ にあり、コピー時に同じツリー ロックを取得し、リセットおよびタスク共有ワークスペースとは相互に排他的です。 */
class AndroidBranchWorkspace(private val dirs: SandboxDirs) : BranchWorkspace {
    constructor(context: Context) : this(SandboxDirs(context))

    override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? =
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                copyInboxTree(dirs.workspace(sourceId), dirs.workspace(targetId), maxBytes)
            }
        }
}

private const val INBOX = "inbox"

/**
 * [source]/inbox を [target]/inbox にコピーします。通常のファイルのみを受け入れ、シンボリック リンクをたどらないでください。パスは正規化後もそれぞれの受信ボックスの下に存在する必要があります。 まず
 * を target/.inbox-tmp-<uuid> にコピーし、すべてが成功した後で名前を変更します。 [maxBytes]を超えた場合は中止し、一時ディレクトリを削除してリターンします。
 * `BranchCopyResult(copied = 0, truncated = true)`、半製品は残りません。
 * ソースに受信トレイがない場合は、null を返します。ターゲットにすでに受信トレイがあり、カバーされていない場合は、`BranchCopyResult(copied = 0, skipped = 1)` を返します。
 * コピー中にエラーが発生し、nullが返されます。ツリーロックを外すことなく、携帯電話はロック内で調整できます。
 */
internal fun copyInboxTree(source: File, target: File, maxBytes: Long): BranchCopyResult? {
    val sourceInbox = File(source, INBOX)
    if (!sourceInbox.isDirectory || Files.isSymbolicLink(sourceInbox.toPath())) return null
    val sourceRoot = sourceInbox.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, INBOX)
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    val tmp = File(target, ".inbox-tmp-${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // パイプ、ソケット、およびユーザー添付ファイルに存在しないその他のものはコピーされません
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceInbox, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

/** は通常のファイルをコピーし、最大 [max] バイトを書き込むことができます。それを超える場合は、null が返されます (一時ディレクトリは呼び出し元によって完全に削除されます)。 */
private fun copyFile(source: File, dest: Path, max: Long): Long? {
    dest.parent?.let(::mkdirs)
    var total = 0L
    Files.newInputStream(source.toPath()).use { input ->
        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                output.write(buf, 0, n)
            }
        }
    }
    return total
}

private fun mkdirs(path: Path) {
    val f = path.toFile()
    if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create directory: $path")
}
