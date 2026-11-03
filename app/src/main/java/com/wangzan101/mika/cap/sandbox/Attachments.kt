package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** は添付されたファイルのバッチです。 [added] は (サンドボックスのパス、サイズ)、[rejected] は没収されたファイルの名前です (大きすぎるか、読み取ることができません)。 */
data class ImportResult(val added: List<Pair<String, Long>>, val rejected: List<String>)

/** チャットでユーザーが添付したファイル: 現在の会話ワークスペースの inbox/ にコピーされ、サンドボックスは /workspace/inbox/ にあります。 */
object Attachments {
    const val MAX_BYTES = 200L shl 20
    const val MIN_FREE = 500L shl 20
    const val WORKSPACE_MAX = 4L shl 30
    private const val CHECK_EVERY = 16L shl 20
    private const val MAX_NAME = 120

    /** は、パス区切り文字、制御文字、フォーマット文字を削除し、中国語の文字とスペースを保持し、120 ワードを超えません。何も残らないとき、それは執着と呼ばれます。 */
    fun safeName(display: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < display.length) {
            val cp = display.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '/'.code || cp == '\\'.code || ShareRules.hiddenChar(cp)) continue
            sb.appendCodePoint(cp)
        }
        val name = sb.toString().trim().trimStart('.')
        // ファイル システムの長さはバイト単位で計算されます。中国語名は 120 文字で、255 バイトを超える場合があります。拡張子は切り詰められても保持されます
        return ShareRules.fitName(name.ifEmpty { "attachment" }, maxChars = MAX_NAME)
    }

    /** すでに同名のファイルがある場合は、「(2)」「(3)」…を追加し、末尾に拡張子を残します。 */
    fun numbered(name: String, n: Int): String = WorkspaceFileGate.numbered(name, n)

    /**
     * は、[workspace] の下の inbox/ にコピーされます。各ファイルは 200 MB 以下です (超過したファイルは削除され、拒否されたものとして記録されます)。
     * 各ファイルを開始する前に容量を確認します (コピー中も 16 MB ごと)。携帯電話の使用可能な容量が 500 MB 未満である場合、またはワークスペースが 4 GB を超える場合、容量は収集されません。
     * via [gate] inbox/ に新しいファイルを作成します (上書きではなく、ハンドルで確認します): サンドボックス内のコマンドによってリンクに置き換えられても、受信ボックスに書き込むことはできません。失敗すると、作成したファイルのみが削除されます。
     */
    suspend fun importInto(
        context: Context, uris: List<Uri>, workspace: File, gate: WorkspaceFileGate, tree: SandboxTree,
        freeBytes: () -> Long = { workspace.usableSpace }, workspaceBytes: () -> Long = { 0L },
    ): ImportResult = tree.shared { withContext(Dispatchers.IO) { // は files/sandbox/: に書き込み、ミューテックスをリセットします
        val added = mutableListOf<Pair<String, Long>>()
        val rejected = mutableListOf<String>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val display = displayName(context, uri) ?: uri.lastPathSegment ?: "attachment"
            val base = safeName(display)
            if (!roomLeft(freeBytes, workspaceBytes)) { rejected += base; continue }
            val result = try { copyOne(context, uri, workspace, gate, base) { roomLeft(freeBytes, workspaceBytes) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (result == null) rejected += base else added += result
        }
        ImportResult(added, rejected)
    } }

    private fun roomLeft(freeBytes: () -> Long, workspaceBytes: () -> Long) =
        freeBytes() >= MIN_FREE && workspaceBytes() <= WORKSPACE_MAX

    private suspend fun copyOne(context: Context, uri: Uri, workspace: File, gate: WorkspaceFileGate, base: String, room: () -> Boolean): Pair<String, Long>? {
        workspace.mkdirs()
        val (name, g) = try { gate.createNumbered(workspace, INBOX, base, max = 1000) } catch (e: SandboxError) { return null }
        var size = 0L
        g.use {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                input.use {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = it.read(buf)
                        if (r < 0) break
                        size += r
                        if (size > MAX_BYTES) throw IllegalStateException("too large")
                        if (size % CHECK_EVERY < r && !room()) throw IllegalStateException("storage low")
                        g.write(if (r == buf.size) buf else buf.copyOf(r))
                    }
                }
            } catch (e: Throwable) {
                g.delete()
                throw e
            }
        }
        return "${SandboxPaths.WORKSPACE}/$INBOX/$name" to size
    }

    private const val INBOX = "inbox"

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
