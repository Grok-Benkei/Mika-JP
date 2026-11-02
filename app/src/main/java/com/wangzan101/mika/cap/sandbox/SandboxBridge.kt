package com.wangzan101.mika.cap.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val MAX_CONNECTIONS = 4
/** 接続中に発言しないクライアント、または文章の途中で停止したクライアントは、30 秒後に切断されます。 */
private const val CLIENT_TIMEOUT_MS = 30_000
private const val WAKE_DEADLINE_MS = 1_000L
private const val WAKE_RETRY_MS = 50L
private const val TAG = "SandboxBridge"

/**
 * サンドボックス コマンド コールバック ミカのブリッジ: 実行されるたびに、抽象名前空間内の Unix ソケット (ランダムな名前) とワンタイム トークンが開かれ、環境変数を介してコマンドに渡されます。
 * は、コマンド終了後 (タイムアウト、強制終了、キャンセルを含む) 閉じられます。ネットワーク切断フィルターは AF_INET/AF_INET6 のみをブロックするため、ネットワークが切断されてもブリッジは引き続き使用できます。
 * は、Mika と同じ ID (uid) を持つ接続のみを受け入れます。プロトコルについては、[BridgeProtocol] を参照してください。
 */
class SandboxBridge(
    private val onProgress: (String) -> Unit,
    private val onShare: suspend (sandboxPath: String, name: String?) -> String,
    private val info: () -> JsonObject = { JsonObject(emptyMap()) },
    private val onPhone: (suspend (String, JsonObject) -> JsonObject)? = null,
) {
    fun open(): Session {
        val rnd = SecureRandom()
        val name = "mika-bridge-" + String(CharArray(16) { NAME_CHARS[rnd.nextInt(NAME_CHARS.length)] })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))
        return Session(name, token, LocalServerSocket(name), onProgress, onShare, info, onPhone)
    }

    class Session internal constructor(
        val name: String, val token: String,
        private val server: LocalServerSocket,
        private val onProgress: (String) -> Unit,
        private val onShare: suspend (String, String?) -> String,
        private val info: () -> JsonObject,
        private val onPhone: (suspend (String, JsonObject) -> JsonObject)?,
    ) : Closeable {
        @Volatile private var closed = false
        private val clients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
        private val gate = ProgressGate()
        /** 進行中のエクスポートはすべてここにあります。セッションが閉じられると、それらは一緒にキャンセルされ、それ以上は書き込まれません。 */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** ツール コンテキストは同じタスクに属しており、複数の CLI 接続からの携帯電話リクエストは順番に実行されます。 */
        private val phoneLock = Mutex()
        private val acceptor = thread(isDaemon = true, name = "mika-bridge-accept") { acceptLoop() }

        private fun acceptLoop() {
            while (!closed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (closed) { runCatching { s.close() }; break }
                // 同じ ID を持つ場合のみ受け入れます。接続が多すぎる場合は拒否します (1 つのコマンドに多くの接続は必要ありません)
                val uid = runCatching { s.peerCredentials.uid }.getOrNull()
                if (uid != Process.myUid() || clients.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                runCatching { s.soTimeout = CLIENT_TIMEOUT_MS }
                clients += s
                thread(isDaemon = true, name = "mika-bridge-conn") { serve(s) }
            }
        }

        private fun serve(s: LocalSocket) {
            try {
                val input = s.inputStream.buffered()
                val output = s.outputStream
                while (!closed) {
                    val reply = when (val line = BridgeProtocol.readLine(input)) {
                        BridgeProtocol.Line.End -> break
                        BridgeProtocol.Line.TooLarge -> BridgeReply("""{"ok":false,"error":"too_large"}""", close = true)
                        is BridgeProtocol.Line.Text -> runBlocking {
                            BridgeProtocol.handle(line.text, token, ::progress, ::share, info, ::phone) { Log.w(TAG, "bridge request failed", it) }
                        }
                    }
                    output.write((reply.json + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (reply.close) break
                }
            } catch (_: IOException) {
                // ピアが切断されたか、セッションが閉じられました
            } catch (_: CancellationException) {
                // セッションが閉じられたときに進行中のエクスポートがキャンセルされました
            } finally {
                clients -= s
                runCatching { s.close() }
            }
        }

        private suspend fun share(path: String, name: String?): String {
            if (closed) throw CancellationException("bridge session closed")
            return work.async { onShare(path, name) }.await()
        }

        private suspend fun phone(tool: String, args: JsonObject): JsonObject {
            if (closed) throw CancellationException("bridge session closed")
            val handler = onPhone ?: return kotlinx.serialization.json.buildJsonObject {
                put("ok", false); put("error", "phone_unavailable")
            }
            return work.async { phoneLock.withLock { handler(tool, args) } }.await()
        }

        private fun progress(text: String): Boolean {
            if (closed || !gate.offer()) return false
            onProgress(text)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            work.cancel()
            // accept() はクローズのため戻らない可能性があります。期限までに自分で接続してウェイクアップしてください (接続できない場合は、もう一度試してください)。再び閉じる ホスト上の
            val deadline = System.currentTimeMillis() + WAKE_DEADLINE_MS
            while (acceptor.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
                acceptor.join(WAKE_RETRY_MS)
            }
            runCatching { server.close() }
            synchronized(clients) { clients.toList() }.forEach { c ->
                runCatching { c.shutdownInput() }
                runCatching { c.close() }
            }
        }
    }
}
