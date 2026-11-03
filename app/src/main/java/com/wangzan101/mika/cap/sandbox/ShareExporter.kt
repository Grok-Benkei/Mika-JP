package com.wangzan101.mika.cap.sandbox

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

class FileTooLarge(val bytes: Long) : Exception("file is larger than ${ShareRules.MAX_BYTES shr 20} MB")

/** ワークスペース内のファイルを携帯電話の「Download/Mika」(MediaStore、ストレージ権限は必要ありません)に保存します。 */
class ShareExporter(private val context: Context) {
    /**
     * [file] はチェックポイント検証に合格しました (一度リンクをたどって開かないでください。ハンドルを押して、それが独自のワークスペースにあることを確認します。これは通常のファイルであり、ハード リンクは 1 つだけです)。
     * ここでも、サイズは 100 MB を超えず、この同じハンドルからコピーされます。確認後にリンクや別のファイルに置き換えても外部のコンテンツを読み込むことはできません。
     * は、savedAs が「Download/Mika/<実際の名前>」であるエクスポートされたファイルを返します (名前が重複する場合、システムはシリアル番号を追加します)。ミカさんの私道は引き継がれません。
     */
    suspend fun export(file: GatedFile, requestedName: String): SharedFile = withContext(Dispatchers.IO) {
        if (!ShareRules.plainName(requestedName)) throw SandboxError("invalid_arguments", "name: must be a plain file name", recoverable = true)
        val name = ShareRules.fitName(requestedName)
        if (ShareRules.refusedType(name) || ShareRules.refusedType(file.name)) {
            throw SandboxError("share_refused_type", "App installation packages cannot be shared.", recoverable = false)
        }
        if (file.size > ShareRules.MAX_BYTES) throw FileTooLarge(file.size)
        copyOut(file.input(), name)
    }

    private suspend fun copyOut(input: InputStream, name: String): SharedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Mika/")
            mimeOf(name)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        var size = 0L
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // セッションが閉じられると (タスクがキャンセルされ、コマンドが終了すると)、それ以上の書き込みは行われません。
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    // 確認後、ファイルが再び大きくなりました。上限に基づいて同じ計算が行われます。
                    if (size > ShareRules.MAX_BYTES) throw FileTooLarge(size)
                    out.write(buf, 0, n)
                }
            }
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(uri) ?: name
        return SharedFile(actual, size, "Download/Mika/$actual", uri.toString())
    }

    /**
     * このアプリが「Download/Mika」に残した未完了のエントリを削除します（書き込み途中でプロセスが強制終了すると、IS_PENDINGの空のシェルが残ります）。起動時に呼び出されます。
     */
    fun cleanPending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${android.os.Environment.DIRECTORY_DOWNLOADS}/Mika/%", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun mimeOf(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
