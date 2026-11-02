package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.adb.OwnListeningPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * ログイン コールバックによって呼び出されるローカルの小規模サービス (ChatGPT および OpenRouter ログインと共有): 127.0.0.1 のみをバインドし (他のデバイスは接続できません)、システムはアイドル ポートを選択します。ログイン後にブラウザが戻る
 * `http://127.0.0.1:<端口>/auth/callback?…` (または localhost として記述)、ここでパラメーターを読み取り、ログインを完了し (トークンの変更、確認)、結果のページに戻ります。プロセス中にブラウザ
 * はこの接続を待っています。他のパス (ブラウザ アイコンなど) は 404 を返します。コールバックは 1 つだけ収集されます。それを使い切るか放棄するには、[close] の費用がかかります。
 */
class LoopbackCallback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    // ワイヤレス デバッグ ポートを探す場合は、このマシンをスキャンします。自分のモニターをスキャンしないでください。
    init { OwnListeningPorts.add(server.localPort) }
    val redirectUri: String get() = redirectUri(HOST)

    /** コールバック アドレスは、[host] (`127.0.0.1` または `localhost`、どちらもローカル マシンを指します) として書き込まれます。一部のサービスは、これらの書き込み方法の 1 つだけを認識します。 */
    fun redirectUri(host: String): String = "http://$host:$port$PATH"

    /**
     * およびその他のコールバック (最大 [timeoutMs])。タイムアウトはnullです。コールバックを受信したら、まず [page] を呼び出します (一時停止可能: 内部でログインを完了します)。
     * 提供された Web ページがブラウザに返されるため、最終結果を Web ページに書き込むことができます。コルーチンがキャンセルされると (接続が閉じられると)、すぐに戻ります。
     * [accept] コールバックが認識されない場合 (状態が一致しない、このログインによって開始されなかったなど) は、400 を返し、待機します。
     */
    suspend fun await(
        timeoutMs: Long,
        accept: (Map<String, String>) -> Boolean = { true },
        page: suspend (Map<String, String>) -> String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@withContext null }
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = try { reader.readLine() } catch (e: IOException) { null } ?: return@use
                // リクエストヘッダーを読み取ります
                while (true) {
                    val line = try { reader.readLine() } catch (e: IOException) { null }
                    if (line.isNullOrEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                if (parts.firstOrNull() != "GET" || path != PATH) {
                    respond(s.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                    return@use
                }
                // 不適切に書かれた文字 (order % など) をエスケープし、このコールバックではないものとして扱います。
                val params = runCatching { parseQuery(target.substringAfter('?', "")) }.getOrNull()
                if (params == null || !accept(params)) {
                    respond(s.getOutputStream(), 400, "Bad Request", "text/plain", "Bad Request")
                    return@use
                }
                respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", page(params))
                return@withContext params
            }
        }
        null
    }

    override fun close() {
        OwnListeningPorts.remove(server.localPort)
        runCatching { server.close() }
    }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000

        /** はモニターを開きます。 [port] が 0 の場合、システムによって選択されます。 */
        fun open(port: Int = 0): LoopbackCallback = LoopbackCallback(ServerSocket(port, 4, InetAddress.getByName(HOST)))

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
