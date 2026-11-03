package com.wangzan101.mika.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * ネットワーク プロキシが設定されている場合の更新ダウンロード: システム ダウンロード サービスは、アプリ独自のプロキシを使用しません。代わりに、Mika はプロセスでプロキシ [http] を使用します。
 * は最初に `<保存先>.part` に書き込まれ、次に完全にダウンロードされてからターゲット ファイルに名前が変更されるため、「ターゲット ファイルがそこにある」ということはダウンロードされたことを意味します。 プロセス内には
 * のコピーが 1 つだけあります (同時にダウンロードできるのは 1 つだけです)。プロセスが強制終了された後にダウンロードが完了しない場合、失敗とみなされ、ユーザーは再試行できます。
 */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var target: File? = null
    @Volatile private var bytes = 0L
    @Volatile private var total = -1L

    fun start(url: String, file: File) {
        cancel()
        target = file
        bytes = 0
        total = -1
        job = scope.launch {
            runCatching { fetch(http, url, file, onCall = { call = it }) { done, size -> bytes = done; total = size } }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /** [file] このコピーのステータス: ダウンロード中、ダウンロード、または失敗 (ダウンロードされておらず、完全なファイルがありません)。 */
    fun state(file: File): ApkDownloadState = when {
        job?.isActive == true && target == file -> ApkDownloadState(DownloadPhase.Downloading, bytes, total)
        file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
        else -> ApkDownloadState(DownloadPhase.Failed)
    }

    fun cancel() {
        job?.cancel()
        // このセクションを読み終わるのを待たずに接続を切断します。
        call?.cancel()
        job = null
        call = null
        target?.let { File(it.path + PART).delete() }
    }

    companion object {
        private const val PART = ".part"

        /** [url] から [file] へのダウンロード: ダウンロード中の進行状況を報告します (ダウンロード済み、合計、合計が -1 かどうかは不明)。 2xx でない場合、またはダウンロードが完了していない場合はエラーが発生し、対象のファイルは残りません。 */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
