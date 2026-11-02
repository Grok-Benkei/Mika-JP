package com.wangzan101.mika.cap.web.cdp

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.NetworkLedger
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * ラベルのネットワーク レコード: 初回使用時のみオープン ネットワーク (アクション、web_capture 後の自動待機)、
 * は、このタグ セッションの `Network.*` イベントをサブスクライブし、それを [NetworkLedger] にフィードします。セッションが切断されるか、ラベルが閉じられると停止します。新しいセッション ボタンをクリックすると、次回使用するときにセッションが再作成されます。
 * 1 つのセッションからのイベントのみが受信されるため、ラベルでフィルターする必要はありません。サブセッション (sessionId が空ではない) のリクエストはセッションごとに個別に記録されます。
 */
class NetworkTracker private constructor(
    private val session: CdpSession,
    private val clock: () -> Long,
) {
    private val ledger = NetworkLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bodyLock = Mutex()
    @Volatile var enabled = false
        private set

    // 最初にサブスクライブしてから Network.enable を送信します: イベント ストリームが再生されません
    private suspend fun start(): Boolean {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.method.startsWith(NETWORK)) synchronized(ledger) { ledger.onEvent(e.method, e.params, clock(), e.sessionId) }
            }
        }
        scope.launch { session.closed.first { it }; close() }
        enabled = try {
            // getResponseBody のレンダリング プロセスによって残されるテキストにも上限があります。個々のテキストは元帳の上限と一致します (大きいテキストはいずれにせよ取得されません)。
            // の合計金額は元帳の 2 倍です。画像やスクリプトなどの小さなリソースもこのバッファを占有するため、インターフェースの応答が圧迫されない余地が残されています。
            session.send(
                "Network.enable",
                buildJsonObject {
                    put("maxResourceBufferSize", ledger.maxBodyBytes)
                    put("maxTotalBufferSize", ledger.maxTotalBodyBytes * 2)
                },
                timeoutMs = CALL_TIMEOUT_MS,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network.enable failed: ${e.message}")
            false
        }
        if (!enabled) scope.cancel()
        return enabled
    }

    val alive: Boolean get() = enabled && !session.closed.value

    fun markAction() = synchronized(ledger) { ledger.markAction() }

    fun idle(): Boolean = synchronized(ledger) { ledger.isIdle(clock()) }

    /** このアクションによって送信されたリクエストはまだ進行中です (または終了したばかりです)。セッションは切断されており、ビジーではないと見なされます。 */
    fun actionBusy(): Boolean = alive && synchronized(ledger) { ledger.actionBusy(clock()) }

    /** ネットワークがアイドル状態になる (転送中のリクエストがなく、静かな [NetworkLedger.quietMs]) (最大 [capMs]) まで待ちます。まだアイドル状態でない場合は false を返します。 */
    suspend fun awaitIdle(capMs: Long): Boolean {
        val deadline = clock() + capMs
        while (true) {
            if (!alive) return true
            if (idle()) return true
            if (clock() >= deadline) return false
            delay(POLL_MS)
        }
    }

    fun list(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> =
        synchronized(ledger) { ledger.list(urlContains, method, sinceLastAction) }

    /** が応答を取得します。テキストがまだ取得されていない場合は、`Network.getResponseBody` を使用して取得します (テキストが取得できない場合、テキストは含まれません)。 */
    suspend fun get(id: String): CapturedResponse? = bodyLock.withLock {
        val fetch = synchronized(ledger) { ledger.bodyToFetch(id) }
        if (fetch != null && alive) {
            try {
                val r = session.send(
                    "Network.getResponseBody",
                    buildJsonObject { put("requestId", fetch.requestId) },
                    fetch.sessionId,
                    CALL_TIMEOUT_MS,
                )
                val body = (r["body"] as? JsonPrimitive)?.content
                val b64 = (r["base64Encoded"] as? JsonPrimitive)?.boolean == true
                if (body != null) synchronized(ledger) { ledger.putBody(id, body, b64) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ページがこの応答を失ったか (ナビゲーション後によくある)、または呼び出しがタイムアウトしました: テキストなしで通常どおりに戻ります
                Log.i(TAG, "getResponseBody $id failed: ${e.message}")
            }
        }
        synchronized(ledger) { ledger.get(id) }
    }

    fun close() {
        if (!scope.isActive) return
        val s = session
        if (enabled && !s.closed.value) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { s.send("Network.disable", timeoutMs = CALL_TIMEOUT_MS) } }
        }
        enabled = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "MikaNetTrack"
        private const val NETWORK = "Network."
        private const val POLL_MS = 100L
        private const val CALL_TIMEOUT_MS = 3_000L

        private val trackers = WeakHashMap<WebTab, NetworkTracker>()
        private val creating = Mutex()

        /**
         * このラベルのネットワーク レコード。初めて呼び出されたときにネットワークが開きます。 DevTools セッションがない場合、またはオープンに失敗した場合 (呼び出し元が古いルートを選択した場合)、Null になります。
         * セッションが変更 (再接続) されるか、ラベルが閉じられると、現在のセッションに従って再構築されます。
         */
        suspend fun of(tab: WebTab, clock: () -> Long): NetworkTracker? {
            if (tab.destroyed || tab.crashed) {
                release(tab)
                return null
            }
            val session = tab.devtools.session(tab) ?: return null
            return creating.withLock {
                synchronized(trackers) { trackers[tab] }?.takeIf { it.session === session && it.alive }?.let { return@withLock it }
                synchronized(trackers) { trackers.remove(tab) }?.close()
                val t = NetworkTracker(session, clock)
                if (!t.start()) return@withLock null
                synchronized(trackers) { trackers[tab] = t }
                t
            }
        }

        /** タグがオフです: 記録を停止します。 */
        fun release(tab: WebTab) {
            synchronized(trackers) { trackers.remove(tab) }?.close()
        }
    }
}
