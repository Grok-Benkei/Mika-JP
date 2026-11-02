package com.wangzan101.mika.cap.web

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Web ページ上のファイルのタスク: [workspaceDir] (この会話のワークスペース) のダウンロード/をダウンロードし、このワークスペース内のファイルのみをアップロードします。
 * アップロードでは、ワークスペース ファイル自体は渡されません。ファイルはゲートウェイを介してアプリのプライベート転送領域 ([UploadStaging]) にコピーされ、ファイルプロバイダーは転送領域を開くだけです。
 * 選択ボックスが削除された後、コピーは [keepMs] (ページがフォームを送信するときに読み取り専用) のままになり、後でまたはタスクの終了時に削除されます。削除されなかったものはすぐに削除されます。
 */
class WebFiles(
    private val context: Context,
    val workspaceDir: File,
    val downloads: WebDownloads,
    gate: WorkspaceFileGate,
    private val scope: CoroutineScope,
    private val keepMs: Long = 10 * 60_000L,
) {
    private val staging = UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate)
    private val live = mutableMapOf<UploadStaging.Staged, Job?>()

    sealed interface Upload {
        class Ok(val uris: List<Uri>, val names: List<String>, val staged: UploadStaging.Staged) : Upload
        /** [error] は、file_not_found、path_outside_workspace、linked_file、または file_too_large です。 */
        class Refused(val error: String, val path: String) : Upload
    }

    fun prepareUpload(paths: List<String>): Upload {
        val staged = when (val r = staging.stage(workspaceDir, paths)) {
            is UploadStaging.Result.Refused -> return Upload.Refused(r.error, r.path)
            is UploadStaging.Result.Ok -> r.staged
        }
        val uris = try {
            staged.files.map { FileProvider.getUriForFile(context, "${context.packageName}.webfiles", it) }
        } catch (e: IllegalArgumentException) {
            staging.delete(staged)
            return Upload.Refused("file_not_found", paths.first())
        }
        synchronized(live) { live[staged] = null }
        return Upload.Ok(uris, staged.files.map { it.name }, staged)
    }

    /** のアップロードが終了します。選択ボックス ([consumed]) が削除されたら、[keepMs] に移動して削除します。削除されていない場合は、すぐに削除してください。 */
    fun release(staged: UploadStaging.Staged, consumed: Boolean) {
        if (!consumed) return drop(staged)
        val job = scope.launch {
            delay(keepMs)
            drop(staged)
        }
        synchronized(live) { if (staged in live) live[staged] = job else job.cancel() }
    }

    private fun drop(staged: UploadStaging.Staged) {
        synchronized(live) { live.remove(staged) }?.cancel()
        staging.delete(staged)
    }

    /** ミッションは終了しました: 残りのコピーはすべて削除されます。 */
    fun close() {
        val all = synchronized(live) { live.keys.toList() }
        all.forEach(::drop)
    }

    companion object {
        /** は、開始時に最後のプロセスによって残されたコピーをクリアします。 */
        fun clearStale(context: Context, gate: WorkspaceFileGate) {
            UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate).clear()
        }
    }
}
