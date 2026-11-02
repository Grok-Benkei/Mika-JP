package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * ページによって発行された XHR/フェッチ リクエストとその応答。 [id] はプロトコルの requestId です (サブセッションには `会话:` というプレフィックスが付きます)。
 * [size] は、デコードされたバイト数です (使用できない場合は null)。 [body] はテキストを取得した後にのみ使用できます。[bodyNote] はテキストがない理由を説明しています。
 */
data class CapturedResponse(
    val id: String,
    val url: String,
    val method: String,
    val type: String,
    val status: Int?,
    val mime: String?,
    val size: Long?,
    val fromCache: Boolean,
    val state: State,
    val error: String?,
    val startedMs: Long,
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    val bodyNote: String? = null,
) {
    enum class State(val wire: String) { PENDING("pending"), DONE("done"), FAILED("failed") }
}

/**
 * ラベル付きのネットワーク家計簿 (純粋なロジック、プロトコル接続なし): `Network.*` イベントをフィードし、「転送中のリクエストはありますか?」と回答します。 「どれくらい静かでしたか？」
 * および最新の [maxCaptured] XHR/フェッチ応答を保持します。テキストはオンデマンドで取得されます (呼び出し元は `Network.getResponseBody` を使用してテキストを取得し、それを [putBody] に渡します)。
 * は JSON/テキストのみを受け入れ、個々のテキストは [maxBodyBytes] を超えません。すべてのテキストの合計が [maxTotalBodyBytes] を超える場合、最も古いテキストが最初に破棄されます。
 *
 * の転送中カウントには、イベント ストリーム、メディア、ビーコン、プリフェッチ、および [staleMs] を超えて送信したがまだ応答していない接続 (ロング ポーリング) などの長い接続はカウントされません。
 * はスレッドセーフではないため、呼び出し元が自分でシリアル化します。
 */
class NetworkLedger(
    val quietMs: Long = 500,
    val staleMs: Long = 10_000,
    val maxCaptured: Int = 200,
    val maxBodyBytes: Int = 256 * 1024,
    val maxTotalBodyBytes: Int = 2 * 1024 * 1024,
) {
    data class BodyFetch(val requestId: String, val sessionId: String?)

    private class Flight(val startedMs: Long, var longLived: Boolean, val actionSeq: Int)

    private class Entry(
        val id: String,
        val requestId: String,
        val sessionId: String?,
        var url: String,
        var method: String,
        val type: String,
        val startedMs: Long,
        val actionSeq: Int,
    ) {
        var status: Int? = null
        var mime: String? = null
        var decoded = 0L
        var encoded: Long? = null
        var fromCache = false
        var state = CapturedResponse.State.PENDING
        var error: String? = null
        var body: String? = null
        var bodyBytes = 0
        var bodyTruncated = false
    }

    private val flights = HashMap<String, Flight>()
    private val captured = LinkedHashMap<String, Entry>()
    // はテキストの順序を取得します。これは、最も古いものを最初に破棄するために使用されます。
    private val bodyOrder = ArrayDeque<String>()
    private var bodyTotal = 0
    private var lastBusyMs = Long.MIN_VALUE / 2
    // は、このアクション以降に発行されたリクエストのみをカウントします
    private var lastActionBusyMs = Long.MIN_VALUE / 2
    private var actionSeq = 0

    fun onEvent(method: String, params: JsonObject, now: Long, sessionId: String? = null) {
        val requestId = params.str("requestId") ?: return
        val key = if (sessionId == null) requestId else "$sessionId:$requestId"
        when (method) {
            "Network.requestWillBeSent" -> {
                val request = params["request"] as? JsonObject ?: return
                val url = request.str("url") ?: return
                val type = params.str("type") ?: "Other"
                val m = request.str("method") ?: "GET"
                val f = flights[key]
                if (f == null) {
                    pruneStale(now)
                    flights[key] = Flight(now, type in LONG_LIVED_TYPES, actionSeq)
                }
                val flight = flights.getValue(key)
                if (!flight.longLived) {
                    lastBusyMs = now
                    if (flight.actionSeq == actionSeq) lastActionBusyMs = now
                }
                val e = captured[key]
                if (e != null) {
                    // リダイレクト: 前のホップからの応答とともに同じ requestId が再度送信されます。
                    e.url = url
                    e.method = m
                } else if (type in CAPTURED_TYPES) {
                    captured[key] = Entry(key, requestId, sessionId, url, m, type, now, actionSeq)
                    while (captured.size > maxCaptured) drop(captured.keys.first())
                }
            }
            "Network.requestServedFromCache" -> captured[key]?.fromCache = true
            "Network.responseReceived" -> {
                val r = params["response"] as? JsonObject ?: return
                val mime = r.str("mimeType")
                if (mime != null && mime.lowercase().startsWith(EVENT_STREAM)) flights[key]?.longLived = true
                captured[key]?.apply {
                    status = (r["status"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                    this.mime = mime
                    if (r.bool("fromDiskCache") || r.bool("fromPrefetchCache") || r.bool("fromServiceWorker")) fromCache = true
                }
            }
            "Network.dataReceived" -> captured[key]?.let { it.decoded += params.long("dataLength") ?: 0 }
            "Network.loadingFinished" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.DONE
                    encoded = params.long("encodedDataLength")
                }
            }
            "Network.loadingFailed" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.FAILED
                    error = params.str("errorText")?.takeIf { it.isNotBlank() }
                        ?: if (params.bool("canceled")) "canceled" else params.str("blockedReason") ?: "failed"
                }
            }
        }
    }

    /** はまだ転送中であり、「ネットワーク アイドル」リクエストの数を抑制します。 */
    fun inFlight(now: Long): Int = flights.values.count { counts(it, now) }

    /** には送信中のリクエストがなく、最新のリクエストの開始または終了は [quietMs] を超えています。 */
    fun isIdle(now: Long): Boolean = inFlight(now) == 0 && now - lastBusyMs >= quietMs

    /** アクションの開始時に調整: それ以降に送信されたリクエストは「最後のアクション以降」にカウントされます。 */
    /**
     * このアクションによって発行されたリクエスト ([markAction] 以降) はまだ進行中か、最後のリクエストはまだ完了していません。 [quietMs]: アクション後の待機時間がそれに応じて延長されます。
     * 以前に進行中のアクション、長時間の接続、長時間のポーリングはカウントされません。また、ページ自体が常に変更されるわけでもありません。
     */
    fun actionBusy(now: Long): Boolean =
        flights.values.any { it.actionSeq == actionSeq && counts(it, now) } || now - lastActionBusyMs < quietMs

    fun markAction() {
        lastActionBusyMs = Long.MIN_VALUE / 2
        actionSeq++
    }

    fun list(urlContains: String? = null, method: String? = null, sinceLastAction: Boolean = false): List<CapturedResponse> =
        captured.values.filter { e ->
            (urlContains == null || e.url.contains(urlContains, ignoreCase = true)) &&
                (method == null || e.method.equals(method, ignoreCase = true)) &&
                (!sinceLastAction || e.actionSeq == actionSeq)
        }.map { view(it) }

    fun get(id: String): CapturedResponse? = captured[id]?.let { view(it) }

    /** は、テキストをフェッチする必要がある場合のプロトコル パラメーターを指定します。フェッチされた場合、またはフェッチすべきではない場合 (テキストではない、大きすぎる、完了していない) 場合は null です。理由については、[CapturedResponse.bodyNote] を参照してください。 */
    fun bodyToFetch(id: String): BodyFetch? {
        val e = captured[id] ?: return null
        if (e.body != null || noBodyReason(e) != null) return null
        return BodyFetch(e.requestId, e.sessionId)
    }

    fun putBody(id: String, body: String, base64Encoded: Boolean) {
        val e = captured[id] ?: return
        forgetBody(e)
        val text = if (base64Encoded) runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrNull() ?: return else body
        var kept = text
        var bytes = kept.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBodyBytes) {
            kept = cutToBytes(kept, maxBodyBytes)
            bytes = kept.toByteArray(Charsets.UTF_8).size
            e.bodyTruncated = true
        }
        e.body = kept
        e.bodyBytes = bytes
        bodyOrder.addLast(id)
        bodyTotal += bytes
        while (bodyTotal > maxTotalBodyBytes && bodyOrder.size > 1) {
            captured[bodyOrder.removeFirst()]?.let(::forgetBody)
        }
    }

    fun totalBodyBytes(): Int = bodyTotal

    private fun counts(f: Flight, now: Long) = !f.longLived && now - f.startedMs < staleMs

    private fun end(key: String, now: Long) {
        val f = flights.remove(key) ?: return
        if (counts(f, now)) {
            lastBusyMs = now
            if (f.actionSeq == actionSeq) lastActionBusyMs = now
        }
    }

    private fun pruneStale(now: Long) {
        if (flights.size < MAX_FLIGHTS) return
        flights.entries.removeAll { now - it.value.startedMs >= staleMs }
    }

    private fun drop(key: String) {
        captured.remove(key)?.let(::forgetBody)
    }

    private fun forgetBody(e: Entry) {
        if (e.body == null) return
        bodyTotal -= e.bodyBytes
        bodyOrder.remove(e.id)
        e.body = null
        e.bodyBytes = 0
        e.bodyTruncated = false
    }

    private fun size(e: Entry): Long? = e.decoded.takeIf { it > 0 } ?: e.encoded?.takeIf { it > 0 }

    private fun noBodyReason(e: Entry): String? = when {
        e.state == CapturedResponse.State.FAILED -> "failed"
        e.state != CapturedResponse.State.DONE -> "not_finished"
        !isText(e.mime) -> "not_text"
        (size(e) ?: 0) > maxBodyBytes -> "too_large"
        else -> null
    }

    private fun view(e: Entry) = CapturedResponse(
        id = e.id, url = e.url, method = e.method, type = e.type, status = e.status, mime = e.mime, size = size(e),
        fromCache = e.fromCache, state = e.state, error = e.error, startedMs = e.startedMs,
        body = e.body, bodyTruncated = e.bodyTruncated, bodyNote = if (e.body == null) noBodyReason(e) else null,
    )

    companion object {
        /** はページ スクリプトからのリクエストのみを受け入れます。 当然のことながら、 */
        private val CAPTURED_TYPES = setOf("XHR", "Fetch")
        /** は長時間終了しないか、ページがそのリクエストを待機しません。つまり、「ネットワーク アイドル」が保持されません。 */
        private val LONG_LIVED_TYPES = setOf("EventSource", "WebSocket", "Media", "Ping", "Prefetch", "CSPViolationReport")
        private const val EVENT_STREAM = "text/event-stream"
        private const val MAX_FLIGHTS = 512

        fun isText(mime: String?): Boolean {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return false
            return m.startsWith("text/") || m.endsWith("json") || m.endsWith("+json") || m.endsWith("xml") || m == "application/javascript"
        }

        private fun cutToBytes(s: String, max: Int): String {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > max) break
                bytes += n
                i += Character.charCount(cp)
            }
            return s.substring(0, i)
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()
    }
}
