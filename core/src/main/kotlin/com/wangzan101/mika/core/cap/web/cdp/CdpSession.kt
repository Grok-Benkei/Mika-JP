package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** プロトコル イベント。空でない [sessionId] は、フラット アタッチメント (`Target.setAutoAttach` および `flatten=true`) からのサブセッションを示します。 */
data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String? = null)

/** ピアは特定の呼び出しに対してエラー オブジェクトを返しました。 */
class CdpException(val code: Int, val errorMessage: String, val method: String, val data: String? = null) :
    RuntimeException("$method failed ($code): $errorMessage" + (data?.let { " — $it" } ?: ""))

class CdpTimeoutException(val method: String, val timeoutMs: Long) : IOException("$method timed out after ${timeoutMs}ms")

/** 接続が切断されたか閉じられました。切断時に返されなかった通話は失敗します。 */
class CdpConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * DevTools WebSocket を介した JSON-RPC セッション。読み取りコルーチンはパケットの受信を担当します。ID による応答を返し、残りをイベントとして送信します。
 * は IO スレッドで送信され、フレームをシリアルに書き込みます。同じ接続に、sessionId によって区別される複数のフラット サブセッションを含めることができます。
 *
 * イベント ストリームは再生されず、サブスクライバーがいない場合、イベントは直接破棄されます。 [eventBuffer] アイテムはバッファリングされ、サブスクライバーが追いつかない場合は最も古いアイテムが破棄されます。
 * 読み取りコルーチンは、イベントによってブロックされることはありません (そうしないと、応答も停止します)。完全なイベント シーケンスを必要とするサブスクライバーは、それを時間内に消費する必要があります。
 * を実行し、イベントをトリガーするコマンドを発行する前にサブスクリプションを開始します。
 */
class CdpSession(
    private val ws: WebSocketConnection,
    eventBuffer: Int = DEFAULT_EVENT_BUFFER,
) : Closeable {
    private class Pending(val method: String, val result: CompletableDeferred<JsonObject>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closeError: CdpConnectionClosedException? = null

    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = eventBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    /**
     * 入力フィールド (マウス、ドラッグ アンド ドロップ) の座標と CSS ピクセルの比率: 送信された座標 = CSS ピクセル × それ。
     * は通常 1 です。ビューポート シミュレーションがズームされると (デスクトップの幅のページが携帯電話のビューにインデントされます)、入力はズーム前のビューの座標に従って解釈され、シミュレーションを設定する側によって変更されます。
     */
    @Volatile var inputScale: Double = 1.0

    /** 最後のマウス イベントが到達した場所 (CSS ピクセル)。まだ送信されておらず、null です。次の動きはここからの軌道をたどります。 */
    @Volatile var pointer: Pair<Double, Double>? = null

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private val reader = scope.launch { readLoop() }

    suspend fun send(
        method: String,
        params: JsonObject = EMPTY,
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        closeError?.let { throw CdpConnectionClosedException(it.message ?: "closed", it.cause) }
        val id = nextId.getAndIncrement()
        val p = Pending(method, CompletableDeferred())
        pending[id] = p
        // 読み取りコルーチンは、最初に closeError を設定し、次に保留をクリアします。これをここに配置して、見逃さないように再度確認します。
        closeError?.let {
            pending.remove(id)
            throw CdpConnectionClosedException(it.message ?: "closed", it.cause)
        }
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }.toString()
        try {
            // 書き込みにも上限があります。呼び出し側が書き込み中にスタックするのを防ぐために、接続が切断されるとピアは読み取りを行わず、バッファーがいっぱいになります。
            withStreamDeadline(timeoutMs, method, open = { Closeable { ws.abort() } }, closeOnCancel = false) { ws.sendText(msg) }
        } catch (e: IOException) {
            pending.remove(id)
            ws.abort()
            throw CdpConnectionClosedException("CDP connection lost while sending $method", e)
        }
        try {
            return withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw CdpTimeoutException(method, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    /** はクローズ フレームを送信して切断します。未処理の呼び出しはすぐに失敗します。 */
    override fun close() {
        if (closeError != null) return
        shutdown(CdpConnectionClosedException("CDP session closed"))
        runCatching { ws.sendClose() }
        // ピアにフレームを閉じる時間を与えてから、ハード ブレークします。
        scope.launch {
            withTimeoutOrNull(CLOSE_GRACE_MS) { reader.join() }
            ws.abort()
            scope.cancel()
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val text = ws.receive() ?: break
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) {
                    val p = pending[id] ?: continue
                    val error = obj["error"] as? JsonObject
                    if (error != null) {
                        p.result.completeExceptionally(
                            CdpException(
                                code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "",
                                method = p.method,
                                data = error["data"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
                            ),
                        )
                    } else {
                        p.result.complete(obj["result"] as? JsonObject ?: EMPTY)
                    }
                    continue
                }
                val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                _events.tryEmit(
                    CdpEvent(method, obj["params"] as? JsonObject ?: EMPTY, (obj["sessionId"] as? JsonPrimitive)?.contentOrNull),
                )
            }
        } catch (t: Throwable) {
            cause = t
        }
        val reason = when {
            closeError != null -> null
            ws.closeCode != null -> "CDP connection closed by peer (${ws.closeCode}${ws.closeReason.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""})"
            else -> "CDP connection lost: ${cause?.message ?: "end of stream"}"
        }
        if (reason != null) shutdown(CdpConnectionClosedException(reason, cause))
        ws.abort()
    }

    private fun shutdown(error: CdpConnectionClosedException) {
        synchronized(this) {
            if (closeError != null) return
            closeError = error
        }
        for (id in pending.keys.toList()) {
            pending.remove(id)?.result?.completeExceptionally(
                CdpConnectionClosedException(error.message ?: "closed", error.cause),
            )
        }
        _closed.value = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVENT_BUFFER = 1024
        private const val CLOSE_GRACE_MS = 1_000L
        private val EMPTY = JsonObject(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }

        /** は送信を開始し、WebSocket ハンドシェイクを実行して、セッションに戻ります。 [path] は、ターゲットの webSocketDebuggerUrl から取得されます。タイムアウト後に [CdpTimeoutException] をスローします。 */
        suspend fun connect(
            transport: CdpTransport,
            endpoint: String,
            path: String,
            host: String = CdpDiscovery.DEFAULT_HOST,
            maxMessageBytes: Int = WebSocketConnection.DEFAULT_MAX_MESSAGE_BYTES,
            eventBuffer: Int = DEFAULT_EVENT_BUFFER,
            handshakeTimeoutMs: Long = CdpDiscovery.DEFAULT_TIMEOUT_MS,
        ): CdpSession {
            // ハンドシェイクは最大 [handshakeTimeoutMs]: ストリームはこの時点でオフになり、スタック ハンドシェイクは失敗します。
            val ws = withStreamDeadline(handshakeTimeoutMs, "WebSocket handshake $path", open = { transport.open(endpoint) }) { s ->
                WebSocketConnection.connect(s, path, host, maxMessageBytes)
            }
            return CdpSession(ws, eventBuffer)
        }
    }
}
