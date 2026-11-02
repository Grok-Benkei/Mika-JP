package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 双方向バイト ストリーム。閉じた後は両方向が終了し、ブロックされた読み取りはエラーを返すかスローできる必要があります。 */
interface CdpStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * DevTools エンドポイントへのバイト チャネル。エンドポイントは不透明な文字列 (ローカルの抽象ソケット名、シェルによって中継されるアドレスなど) です。
 * は実装自体によって解釈されます。すべての [open] は新しい接続です。呼び出し元は IO スレッドを呼び出し、ブロックすることができます。
 */
fun interface CdpTransport {
    fun open(endpoint: String): CdpStream
}

@Serializable
data class CdpTargetInfo(
    val id: String,
    val type: String,
    val url: String = "",
    val title: String = "",
    /** このターゲットに他のクライアントが接続されている場合、DevTools はこのフィールドを提供しません。 */
    val webSocketDebuggerUrl: String? = null,
)

@Serializable
data class CdpBrowserVersion(
    @SerialName("Browser") val browser: String = "",
    @SerialName("Protocol-Version") val protocolVersion: String = "",
    @SerialName("User-Agent") val userAgent: String = "",
    val webSocketDebuggerUrl: String? = null,
)

/** DevTools HTTP 検出インターフェイス: `/json/version`、`/json/list`。リクエストごとに 1 つの接続が開かれます。 */
object CdpDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun version(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CdpBrowserVersion =
        parseVersion(get(transport, endpoint, "/json/version", host, timeoutMs))

    suspend fun list(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<CdpTargetInfo> =
        parseList(get(transport, endpoint, "/json/list", host, timeoutMs))

    fun parseVersion(body: String): CdpBrowserVersion = json.decodeFromString(body)

    fun parseList(body: String): List<CdpTargetInfo> = json.decodeFromString(body)

    /** `ws://localhost/devtools/page/ID` → `/devtools/page/ID`;トランスポート層はホストを考慮せず、パスのみを考慮します。 */
    fun webSocketPath(webSocketUrl: String): String {
        val rest = webSocketUrl.substringAfter("://", webSocketUrl)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    /** リクエスト全体は最大でも [timeoutMs] です。接続はこの時点で閉じられており、ブロックされた読み取りと書き込みは返され、[CdpTimeoutException] で失敗します。 */
    suspend fun get(transport: CdpTransport, endpoint: String, path: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        withStreamDeadline(timeoutMs, "GET $path", open = { transport.open(endpoint) }) { s ->
            s.use {
                s.output.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                s.output.flush()
                val input = s.input.buffered()
                val status = Http.readLine(input) ?: throw IOException("no HTTP response for $path")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                val headers = Http.readHeaders(input)
                val body = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Http.readChunked(input)
                    headers["content-length"] != null -> {
                        val n = headers.getValue("content-length").trim().toInt()
                        Http.readUpTo(input, n).also { if (it.size < n) throw IOException("HTTP body truncated") }
                    }
                    else -> input.readBytes()
                }
                if (code != 200) throw IOException("GET $path: $status")
                body.toString(Charsets.UTF_8)
            }
        }

    /** DevTools は、ホスト名 localhost または IP (DNS 再バインド防止) を使用したリクエストのみを受け入れます。 */
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_TIMEOUT_MS = 5_000L
}

private val deadlines = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * は、IO スレッドでストリームを開き、[timeoutMs] までのブロッキング読み取りおよび書き込みを実行します。ポイントに到達すると (または呼び出し元がキャンセルされます。closeOnCancel を参照)、ストリームは閉じられます。
 * ブロックされた読み取りと書き込みはエラーをスローして戻り、呼び出し元とその呼び出し元が保持するロックはスタックしません。 [CdpTimeoutException] でタイムアウトに失敗しました。
 * ブロックされたセクションは独立したコルーチンで実行され、呼び出し元は終了を待たずに戻ることができます。
 */
internal suspend fun <S : Closeable, T> withStreamDeadline(
    timeoutMs: Long,
    what: String,
    open: () -> S,
    /** は、呼び出し元がキャンセルされるとすぐにフローをシャットダウンします。 false の場合、読み取りと書き込みは自動的に完了します (依然として [timeoutMs] 制限の対象となります)。 */
    closeOnCancel: Boolean = true,
    block: (S) -> T,
): T {
    val fired = AtomicBoolean(false)
    val opened = java.util.concurrent.atomic.AtomicReference<Closeable?>(null)
    val work = deadlines.async {
        val s = open()
        opened.set(s)
        if (fired.get()) {
            runCatching { s.close() }
            throw CdpTimeoutException(what, timeoutMs)
        }
        block(s)
    }
    val watchdog = deadlines.launch {
        delay(timeoutMs)
        fired.set(true)
        opened.get()?.let { runCatching { it.close() } }
    }
    // ウォッチドッグは、呼び出し元ではなく、この読み取りと書き込みに従います。呼び出し元が最初に終了しましたが、読み取りと書き込みにはまだ上限があります。
    work.invokeOnCompletion { watchdog.cancel() }
    try {
        return work.await()
    } catch (e: CancellationException) {
        if (closeOnCancel) opened.get()?.let { runCatching { it.close() } }
        throw e
    } catch (e: IOException) {
        if (fired.get() && e !is CdpTimeoutException) throw CdpTimeoutException(what, timeoutMs)
        throw e
    }
}

internal object Http {
    private const val MAX_LINE = 16 * 1024

    /** は行を読み取ります (CRLF を削除します)。ストリームが行の先頭で終了する場合は null を返します。 */
    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw IOException("unexpected end of stream in header")
            }
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("header line too long")
        }
        val s = String(buf.toByteArray(), Charsets.ISO_8859_1)
        return s.removeSuffix("\r")
    }

    /** ヘッダー名はすべて小文字に変換されます。 */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("unexpected end of stream in headers")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("bad header line: $line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /**
     * は [n] バイトを読み取りました。ストリームが先に終了した場合は、読み取った部分が返されます。 InputStream.readNBytes は必要ありません。Android 13 でのみ利用可能です。
     * も ByteArrayOutputStream.toString(Charset) を使用しません。
     */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) Unit
                return out.toByteArray()
            }
            val chunk = readUpTo(input, size)
            if (chunk.size < size) throw IOException("HTTP chunk truncated")
            out.write(chunk)
            readLine(input)
        }
    }
}
