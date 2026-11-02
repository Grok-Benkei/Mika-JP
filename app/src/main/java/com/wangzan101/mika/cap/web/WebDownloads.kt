package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxTree
import android.util.Log
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.cap.web.DownloadAddressPolicy
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web ページのダウンロード: ファイルは、この会話のワークスペースの downloads/ に分類されます (サンドボックスに表示されるのは /workspace/downloads/<name> です)。
 * ワークスペースはアプリのプライベート ディレクトリ内の単なるフォルダーであり、ダウンロードはサンドボックスに依存しません。ダウンロードされたファイルは保存されるだけで、開いたりインストールされたりしません (APK についても同様です)。
 *
 * - 名前は Content-Disposition (優先ファイル名*) を取得します。それ以外の場合は、ページ上のリンクに指定された名前 (`<a download>`、ページ スクリプトによって要求されます) を取得します。それ以外の場合は、URL の最後の段落を取得します。
 *   (blob:/data: の URL はカウントされません);パス、制御文字、双方向制御文字、および先頭のドットを削除します。
 *   は最大 80 文字で、バイト単位で切り捨てられます (拡張子は保持されます)。空の名前にはダウンロードを使用し、重複する名前には「(2)」を追加します。名前は Web ページによって付けられ、モデルに渡されるときに Web ページ データとしてマークされます。
 * - ダウンロード手順 [gate]: downloads/ に新しいファイルを作成し (既存の名前を決して上書きしないでください)、ハンドルで確認し、同じハンドルに書き込みます。失敗した場合は、ファイル自体を削除してください。
 *   downloads/ がリンクに置き換えられると (サンドボックス内のコマンドでこれを行うことができます)、それは拒否され、unsafe_path としてマークされます。
 * - URL (ジャンプ後のアドレスに従って各ジャンプが実行されます)、UA、およびソース ページを含む Cookie をリクエストします。 http/https のみを取得します。
 *   は、ホップごとに [addressRefusal] を通過し (ローカル マシンと LAN が許可されない点を除き、web_navigate と同じルール)、blocked_redirect とマークされた禁止アドレスにジャンプします。
 *   ドメイン名は、イントラネット ([dns]) に解決される場合にも拒否されます。
 * - 単一ファイルの上限は [maxBytes] で、中止のコンテンツ制限を超え、ファイルの半分を削除し、too_large;blob:/data: [maxBlobBytes] とマークされます。
 * - 1 つのタスクには最大 [maxFiles] ファイルを含めることができ、合計 [maxTotalBytes] (download_limit)。 1 つのダウンロード (すべてのジャンプを含む) には、最大で [timeoutMs] (timed_out) を含めることができます。
 */
class WebDownloads(
    private val workspaceDir: File,
    private val gate: WorkspaceFileGate,
    /** ワークスペースが配置されているツリーのロック: ダウンロード ディスクはその権限に書き込まれます。 */
    private val tree: SandboxTree,
    http: OkHttpClient,
    private val cookies: (String) -> String?,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 200L shl 20,
    private val maxBlobBytes: Long = 20L shl 20,
    private val maxFiles: Int = 50,
    private val maxTotalBytes: Long = 1L shl 30,
    private val timeoutMs: Long = 10 * 60_000L,
    /** が取得できない（null = 取得できる）理由;ローカル テスト サーバーはテスト中に解放されます。 */
    private val addressRefusal: (String) -> String? = DownloadAddressPolicy::refusal,
    dns: Dns = DownloadAddressPolicy.guardedDns(),
) {
    private class Entry(val file: String, val mime: String?) {
        @Volatile var bytes = 0L
        @Volatile var status = RUNNING
        @Volatile var error: String? = null
        /** 書き込み用ハンドル (チェックポイント検証に合格)。終了（完了または失敗）すると閉じられます。 */
        var gated: GatedFile? = null
        /** はタスク クォータ (1 ファイル + [charged] 書き込みバイト) を占有します。 */
        var counted = false
        var charged = 0L
    }

    private val entries = mutableListOf<Entry>()
    /** クォータ: 占有ファイル数と書き込まれたバイト数は両方とも synchronized(entries) で変更されます。 */
    private var files = 0
    private var totalBytes = 0L

    private val client = http.newBuilder()
        // 1 つずつジャンプし、ジャンプごとにアドレスを確認します。
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        // ジャンプ後の各リクエストには、独自のアドレスに従って Cookie が含まれます。
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val c = cookies(req.url.toString())
            chain.proceed(if (c.isNullOrEmpty()) req else req.newBuilder().header("Cookie", c).build())
        }
        .build()

    /** はページで名前を求めていますが、まだダウンロードに登録していません。 */
    private val naming = AtomicInteger()

    /**
     * ページのダウンロードに付けられた名前 (`<a download>`) はページ内にのみ存在し、ダウンロード監視では取得できません。まず [pageName] を使用してページに問い合わせ、問い合わせた後 (または問い合わせられなかった後)、登録のために [then] に渡します。
     * が名前を尋ねた瞬間、ダウンロードが開始され、[awaitNamed] は登録が完了するのを待っています。 [then] は、`files/sandbox/` に書き込まれた権限で実行されます (SandboxTree を参照)。
     */
    fun withPageName(pageName: suspend () -> String?, then: (String?) -> Unit) {
        naming.incrementAndGet()
        scope.launch {
            try {
                val name = try { pageName() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                tree.shared { then(name) }
            } finally {
                naming.decrementAndGet()
            }
        }
    }

    /** および名前を尋ねられているその他のダウンロードはすべて、少なくとも [capMs] に登録されています。 */
    suspend fun awaitNamed(capMs: Long) {
        withTimeoutOrNull(capMs) { while (naming.get() > 0) delay(NAMING_POLL_MS) }
    }

    /**
     * は [url] のダウンロードを開始します。すぐにサンドボックス パスに戻り、ステータスは実行中になります。 [suggested] はページによって付けられた名前です。
     * [viaPage]: TLS/ハンドシェイク エラー (まだバイトが書き込まれていない) が原因で接続できない場合、ページはブラウザー独自のネットワーク スタックを使用してデータを取得します (指定されたバイト数まで)。
     * は(content, type)として取得されますが、取得されていない場合はnullとなります。渡さないと交換されません。呼び出し元は、URL とページのオリジンが同じである場合にのみそれを渡します (ページはオリジンのみを取得し、ジャンプに従いません)。
     * パスを変更する前に、アドレス ルールをもう一度確認してください。
     */
    fun start(
        url: String, ua: String, contentDisposition: String?, mime: String?, referer: String?, suggested: String? = null,
        viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = null,
    ): String {
        val name = fileName(contentDisposition, url, mime, suggested)
        if (WebUrlPolicy.checkNavigable(url) != null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
            return failed(name, mime, "not_a_web_address")
        }
        if (addressRefusal(url) != null) return failed(name, mime, BLOCKED_ADDRESS)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            val unreached = try {
                tree.shared { fetch(url, ua, referer, entry) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                networkError(e)
            }
            if (unreached != null) fallBack(entry, url, unreached, viaPage)
        }
        return entry.file
    }

    /**
     * は単独では接続されません ([reason] には実際のエラー タイプがあります)。これは TLS/ハンドシェイク タイプです。 [viaPage] が指定されていても、アドレスがルールを通過する場合、そのアドレスはページからフェッチされます。
     * 入手したらすぐに保存してください。そうしないと、理由に従って失敗します。
     */
    private suspend fun fallBack(entry: Entry, url: String, reason: String, viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)?) {
        val usable = viaPage?.takeIf { reason.startsWith(TLS_ERROR) && addressRefusal(url) == null }
        val got = usable?.let { read ->
            try { read(maxBlobBytes) } catch (e: CancellationException) { fail(entry, "cancelled"); throw e } catch (e: Exception) { null }
        }
        if (got == null) return fail(entry, reason)
        if (got.first.size > maxBlobBytes) return fail(entry, TOO_LARGE)
        tree.shared { write(entry, got.first) }
    }

    /** ページ スクリプトによって読み取られた blob: コンテンツ、またはデコードされた data: コンテンツは、直接保存されます。 */
    fun save(sourceUrl: String, contentDisposition: String?, mime: String?, bytes: ByteArray, suggested: String? = null): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        if (bytes.size > maxBlobBytes) return failed(name, mime, TOO_LARGE)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        write(entry, bytes)
        return entry.file
    }

    /**
     * BLOB: ダウンロード: 最初に登録 (実行) し、次に [read] を使用してページにコンテンツ (最大 [maxBlobBytes]) を読み取らせます。読み取れない場合は、その理由を教えてください。
     * [read] 戻り値 (内容、ページ レポートの種類) または失敗の理由。
     */
    fun startFrom(
        sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String? = null,
        read: suspend (Long) -> Pair<ByteArray, String?>?,
    ): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            try {
                val got = read(maxBlobBytes) ?: return@launch fail(entry, "unreadable")
                if (got.first.size > maxBlobBytes) return@launch fail(entry, TOO_LARGE)
                tree.shared { write(entry, got.first) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: BlobRefused) {
                fail(entry, e.reason)
            } catch (e: Exception) {
                Log.w(TAG, "blob download failed", e)
                fail(entry, "unreadable")
            }
        }
        return entry.file
    }

    /** [startFrom] の読者が明確な理由 (too_large など) を報告しました。 */
    class BlobRefused(val reason: String) : Exception(reason)

    private fun localName(sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String?) =
        fileName(contentDisposition, sourceUrl.takeUnless { it.startsWith("blob:", true) || it.startsWith("data:", true) }.orEmpty(), mime, suggested)

    private fun write(entry: Entry, bytes: ByteArray) {
        try {
            if (!charge(entry, bytes.size.toLong())) return fail(entry, DOWNLOAD_LIMIT)
            entry.gated!!.write(bytes)
            entry.bytes = bytes.size.toLong()
            done(entry)
        } catch (e: IOException) {
            fail(entry, "write_failed")
        }
    }

    /**
     * データ: URL: Base64 またはパーセント記号でデコードして保存します。デコードできない場合は、失敗として記録されます。
     * デコードする前に長さで判断します。base64 の 4 ワードごとに 3 バイト、パーセント エンコードの各バイトは少なくとも 1 ワードです。上限を超えると不可解です。
     */
    fun dataUrl(url: String, contentDisposition: String?, suggested: String? = null): String {
        val comma = url.indexOf(',')
        val header = if (comma < 0) "" else url.substring(0, comma)
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { null }
        val base64 = header.endsWith(";base64", true)
        val payloadLength = if (comma < 0) 0L else (url.length - comma - 1).toLong()
        val limit = if (base64) maxBlobBytes * 4 / 3 + 8 else maxBlobBytes * 3
        if (payloadLength > limit) return failed(fileName(contentDisposition, "", mime, suggested), mime, TOO_LARGE)
        val payload = if (comma < 0) "" else url.substring(comma + 1)
        val bytes = runCatching {
            if (base64) Base64.getDecoder().decode(payload) else URLDecoder.decode(payload, "UTF-8").toByteArray()
        }.getOrNull() ?: return failed(fileName(contentDisposition, "", mime, suggested), mime, "bad_data_url")
        return save(url, contentDisposition, mime, bytes, suggested)
    }

    fun list(): List<DownloadInfo> = synchronized(entries) {
        entries.map { DownloadInfo(it.file, it.bytes, it.mime, it.status, it.error) }
    }

    /** この時間以降に新たにダウンロードが開始されました。ダウンロードをトリガーしたアクションの結果に言及するために使用されます。 */
    fun count(): Int = synchronized(entries) { entries.size }

    fun since(count: Int): List<DownloadInfo> = list().drop(count)

    /**
     * はホップごとにフェッチされます。最初に各ホップのアドレスを確認します。ダウンロード全体 (ジャンプやテキストの読み取りを含む) は [timeoutMs] を超えません。
     * が接続に失敗した場合 (まだバイトが書き込まれていない場合)、その失敗は記録されず、戻りの理由は呼び出し元にパスを変更するよう委ねられます。それ以外の場合は、それ自体が記録され、null が返されます。
     */
    private fun fetch(url: String, ua: String, referer: String?, entry: Entry): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var current = url.toHttpUrlOrNull() ?: return fail(entry, "not_a_web_address").let { null }
        var hops = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return fail(entry, TIMED_OUT).let { null }
            val req = Request.Builder().url(current).header("User-Agent", ua).apply { referer?.let { header("Referer", it) } }.build()
            val call = client.newCall(req)
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                val reason = failureOf(e, hops, deadline)
                // TLS/ハンドシェイク タイプのエラー。それらはまだ最初のホップにあります。代わりにページからフェッチするかどうかの決定は呼び出し元に任せます。アドレス拒否、タイムアウト、ジャンプ後のエラーをそのまま記録
                if (reason.startsWith(TLS_ERROR) && hops == 0) return reason
                return fail(entry, reason).let { null }
            }
            try {
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return fail(entry, "http_${resp.code}").let { null }
                    if (++hops > MAX_REDIRECTS) return fail(entry, "too_many_redirects").let { null }
                    if (addressRefusal(next.toString()) != null) return fail(entry, BLOCKED_REDIRECT).let { null }
                    current = next
                    continue
                }
                if (!resp.isSuccessful) return fail(entry, "http_${resp.code}").let { null }
                val body = resp.body ?: return fail(entry, "empty_response").let { null }
                if (body.contentLength() > maxBytes) return fail(entry, TOO_LARGE).let { null }
                val out = entry.gated ?: return null
                val input = body.byteStream()
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (e: IOException) { return fail(entry, failureOf(e, hops, deadline)).let { null } }
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return fail(entry, TOO_LARGE).let { null }
                    if (!charge(entry, n.toLong())) return fail(entry, DOWNLOAD_LIMIT).let { null }
                    out.write(if (n == buf.size) buf else buf.copyOf(n))
                    entry.bytes = total
                    if (System.nanoTime() > deadline) return fail(entry, TIMED_OUT).let { null }
                }
                return done(entry).let { null }
            } finally {
                resp.close()
            }
        }
    }

    private fun failureOf(e: IOException, hops: Int, deadline: Long): String = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is DownloadAddressPolicy.BlockedAddress } -> if (hops == 0) BLOCKED_ADDRESS else BLOCKED_REDIRECT
        System.nanoTime() >= deadline || e is java.io.InterruptedIOException -> TIMED_OUT
        else -> networkError(e)
    }

    /**
     * ネットワーク エラーは、tls_error (SSLHandshakeException: …)、network_error (UnknownHostException: …) などの実際のエラー タイプ (最も内側の理由) をもたらします。
     * 証明書とハンドシェイク タイプ (SSLException) は個別に tls_error と呼ばれます。
     */
    private fun networkError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val root = chain.last()
        val detail = root.message?.lineSequence()?.firstOrNull()?.take(MAX_ERROR_DETAIL)?.let { ": $it" }.orEmpty()
        val kind = if (chain.any { it is javax.net.ssl.SSLException }) TLS_ERROR else NETWORK_ERROR
        return "$kind (${root.javaClass.simpleName}$detail)"
    }

    /**
     * は名前と共有を占有します。ゲートウェイ経由で downloads/ に新しい空のファイルを作成し (リンクをたどったり、既存のファイルを上書きしたりしないでください)、書き込み用のハンドルを残します。
     * クォータが不十分な場合、またはディレクトリが安全でない場合は、失敗したレコードを返します。
     */
    private fun reserve(name: String, mime: String?): Entry = synchronized(entries) {
        if (files >= maxFiles) return failedEntry(name, mime, DOWNLOAD_LIMIT)
        // ゲートウェイが拒否されました (ダウンロード/リンクで置き換えられるなど) か、構築できません: unsafe_path を覚えておいてください
        val opened = try {
            workspaceDir.mkdirs()
            gate.createNumbered(workspaceDir, DIR, name, MAX_SAME_NAME)
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } ?: return failedEntry(name, mime, UNSAFE_PATH)
        files++
        Entry("$SANDBOX_DIR/${opened.first}", mime).also {
            it.gated = opened.second
            it.counted = true
            entries += it
        }
    }

    /** は、書き込まれたばかりの [n] バイトを記録します。超過したタスクの合計数は false です。 */
    private fun charge(entry: Entry, n: Long): Boolean = synchronized(entries) {
        if (totalBytes + n > maxTotalBytes) return false
        totalBytes += n
        entry.charged += n
        true
    }

    private fun done(entry: Entry) = synchronized(entry) {
        if (entry.status != RUNNING) return
        entry.status = DONE
        entry.gated?.close()
        entry.gated = null
    }

    private fun failed(name: String, mime: String?, error: String): String = failedEntry(name, mime, error).file

    private fun failedEntry(name: String, mime: String?, error: String): Entry =
        Entry("$SANDBOX_DIR/$name", mime).also { it.status = FAILED; it.error = error; synchronized(entries) { entries += it } }

    /** 失敗: ダウンロードして作成したファイルを削除します (ハンドルが確認されたファイルは、名前を変更しても移動しません)。クレジットは返金されます。 */
    private fun fail(entry: Entry, error: String) {
        synchronized(entry) {
            if (entry.status != RUNNING) return
            entry.status = FAILED
            entry.error = error
            entry.gated?.let { g -> runCatching { g.delete() }; g.close() }
            entry.gated = null
        }
        synchronized(entries) {
            if (entry.counted) {
                entry.counted = false
                files--
                totalBytes -= entry.charged
            }
        }
    }

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val TOO_LARGE = "too_large"
        const val UNSAFE_PATH = "unsafe_path"
        const val BLOCKED_ADDRESS = "blocked_address"
        const val BLOCKED_REDIRECT = "blocked_redirect"
        const val DOWNLOAD_LIMIT = "download_limit"
        const val TIMED_OUT = "timed_out"
        const val NETWORK_ERROR = "network_error"
        const val TLS_ERROR = "tls_error"
        private const val MAX_ERROR_DETAIL = 120
        private const val MAX_REDIRECTS = 10
        /** という名前はウェブサイトで付けられたもので、モデルに渡すまでの長さは制限されています。 */
        const val MAX_NAME_CHARS = 80
        private const val TAG = "MikaDownloads"
        private const val DIR = "downloads"
        private const val SANDBOX_DIR = "/workspace/downloads"
        private const val BUFFER = 64 * 1024
        private const val MAX_SAME_NAME = 999
        private const val MAX_NAME_BYTES = 200
        private const val MAX_EXT_BYTES = 20
        private const val FALLBACK = "download"
        private const val NAMING_POLL_MS = 20L

        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME_QUOTED = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
        private val FILENAME_TOKEN = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        /** 制御文字、双方向制御文字、幅ゼロ文字。「exe」を「pdf」に見せるなど、表示を偽装できる。 */
        private val UNSAFE_CHARS = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
        private val MIME_EXT = mapOf(
            "application/pdf" to "pdf", "text/csv" to "csv", "text/plain" to "txt", "application/json" to "json",
            "application/zip" to "zip", "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls", "application/msword" to "doc", "text/html" to "html",
        )

        /** ダウンロードするファイルの名前。クラスの説明を参照。[suggested] はページが指定した名前（`<a download>`）で、応答ヘッダーより後、URLより前に使う。 */
        fun fileName(contentDisposition: String?, url: String, mime: String?, suggested: String? = null): String {
            val fromHeader = contentDisposition?.let { cd ->
                FILENAME_STAR.find(cd)?.let { m ->
                    val charset = m.groupValues[1].ifBlank { "UTF-8" }
                    runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                } ?: FILENAME_QUOTED.find(cd)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                    ?: FILENAME_TOKEN.find(cd)?.groupValues?.get(1)
            }
            val fromUrl = runCatching { URI(url).rawPath }.getOrNull()?.substringAfterLast('/')
                ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            var name = clean(fromHeader ?: "").ifEmpty { clean(suggested ?: "") }.ifEmpty { clean(fromUrl ?: "") }.ifEmpty { FALLBACK }
            if (!name.contains('.')) MIME_EXT[mime?.substringBefore(';')?.trim()?.lowercase()]?.let { name = "$name.$it" }
            return fitBytes(name)
        }

        private fun clean(raw: String): String {
            // 末尾のパス要素だけを残す。スラッシュとバックスラッシュを区切りとして扱う。
            val last = raw.substringAfterLast('/').substringAfterLast('\\')
            return last.replace(UNSAFE_CHARS, "").trim().trimStart('.').trim().takeUnless { it == "." || it == ".." }.orEmpty()
        }

        private fun fitBytes(name: String): String {
            fun chars(t: String) = t.codePointCount(0, t.length)
            if (name.toByteArray().size <= MAX_NAME_BYTES && chars(name) <= MAX_NAME_CHARS) return name
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_EXT_BYTES }?.let { ".$it" }.orEmpty()
            var base = name.removeSuffix(ext)
            // コードポイント単位で切り詰め、サロゲートペアを分断しない。
            while (((base + ext).toByteArray().size > MAX_NAME_BYTES || chars(base + ext) > MAX_NAME_CHARS) && base.isNotEmpty()) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            return (base + ext).ifEmpty { FALLBACK }
        }
    }
}
