package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpException
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTimeoutException
import com.wangzan101.mika.core.cap.web.cdp.mainFrameNavigated
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import com.wangzan101.mika.core.cap.web.ScriptAbort
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptCall
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ラベルのメイン フレームにページ スクリプト (web_script) を実行する隔離されたワールドを構築します。
 * 送信ボックスはこの隔離された世界にのみ存在し、ページ自体のスクリプトは表示されません。モデル コードではページ オブジェクトを取得できません ([PageScriptProtocol] を参照)。
 * スクリプトの各 `mika.*` 呼び出しは送信ボックスに入れられ、常にハングするフェッチ評価 ([PageScriptProtocol.TAKE]) によって戻され、一度に 1 つずつ [ScriptBridge] (Mika のアクション パス) に渡され、結果は `Runtime.evaluate` と同じワールドに返されます。
 * 結論: スクリプトによって開始されたネットワーク リクエストと、mika アクションがない場合のメイン フレームのジャンプ (後者は最初に `Page.stopLoading`) はすべて、スクリプトがエラーで終了する原因となります。
 * ページ全体をジャンプすると孤立した世界が破壊され、スクリプトはそこで停止します ([ScriptRun.Navigated] に戻ります)。
 * 時間制限はスクリプト自体の時間のみをカウントします。mika のアクションが実行されている間 (ロック画面レベルの待機を含む)、時計は実行されません。制限時間に達したら、実行中のアクションが完了するまで待ってから終了してください。
 * [preflight] 各 mika 呼び出しの前にタグを確認します (引き継ぎ、クラッシュ)。 null でない場合は、スクリプトを終了します。
 */
class PageScriptRunner(
    private val session: CdpSession,
    private val sessionId: String? = null,
    private val preflight: suspend () -> ScriptRun.Failed? = { null },
) {

    suspend fun run(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun = coroutineScope {
        val world = PageScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
        val outcome = CompletableDeferred<ScriptRun>()
        val calls = Channel<ScriptCall>(Channel.UNLIMITED)
        // これを保持するとアクションが実行されます。終了する前に終了するまで待つ必要があり、アクションを途中で中断することはできません。
        val busy = Mutex()
        val context = AtomicInteger(-1)
        val gone = AtomicBoolean(false)
        val mainFrame = AtomicReference<String?>(null)
        val reader = AtomicInteger(-1)

        // 最初にサブスクライブしてからワールドを構築します: イベント ストリームが再生されません
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.sessionId != sessionId) return@collect
                val p = e.params
                when (e.method) {
                    "Runtime.executionContextDestroyed" -> if (p.int("executionContextId") == context.get()) gone.set(true)
                    "Runtime.executionContextsCleared" -> gone.set(true)
                    // ランタイムが有効になっていない場合、コンテキスト破棄イベントはありません。メイン フレームがドキュメントを変更し、世界は消滅します。
                    "Page.frameNavigated" -> if (mainFrameNavigated(p, mainFrame.get())) gone.set(true)
                    "Network.requestWillBeSent" -> if (context.get() >= 0 && PageScriptProtocol.requestFromScript(p)) {
                        outcome.complete(ScriptRun.Failed(BLOCKED_NETWORK, "the script sent a network request (${p.requestUrl()}); scripts may only read the page"))
                    }
                    "Page.frameRequestedNavigation" -> {
                        val frame = mainFrame.get() ?: return@collect
                        if (context.get() >= 0 && !outcome.isCompleted && PageScriptProtocol.navigationOutsideAction(p, frame, busy.isLocked)) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(ScriptRun.Failed(BLOCKED_NAVIGATION, "the page tried to navigate to ${p.str("url").orEmpty()} while no mika action was running; it was stopped. Use mika.navigate or mika.tap to move between pages"))
                        }
                    }
                }
            }
        }

        try {
            val contextId = try {
                setUp(world).let { w -> mainFrame.set(w.frameId); reader.set(w.reader); context.set(w.script); w.script }
            } catch (e: CdpException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for scripts: ${e.errorMessage}")
            } catch (e: CdpTimeoutException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
            }

            // ピックアップは常にあります。スクリプトの mika 呼び出しは 1 つずつ返されます。終了 (__mikaEnd) は null を返し、世界は消滅し、評価は失敗し、すべてがここで停止します。
            launch {
                while (!outcome.isCompleted) {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", PageScriptProtocol.TAKE); put("contextId", contextId)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        break
                    }
                    val payload = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
                    val call = PageScriptProtocol.parseCall(payload) ?: continue
                    if (call.op == "log") call.args.str("text")?.let(bridge::log) else calls.trySend(call)
                }
            }

            launch {
                outcome.complete(try {
                    // 上限なし: 制限時間は以下の時計によって管理されます (アクションの実行中は時計は動作しません)
                    val r = session.send("Runtime.evaluate", buildJsonObject {
                        put("expression", PageScriptProtocol.run(code)); put("contextId", contextId)
                        put("awaitPromise", true); put("returnByValue", true)
                    }, sessionId, Long.MAX_VALUE)
                    val details = r["exceptionDetails"] as? JsonObject
                    when {
                        gone.get() -> ScriptRun.Navigated
                        details != null -> PageScriptProtocol.error(details).let { (m, line) -> ScriptRun.Failed("script_error", m, line) }
                        else -> ScriptRun.Done((r["result"] as? JsonObject)?.get("value")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content)
                    }
                } catch (e: CdpException) {
                    // ページ全体で世界がジャンプし、スクリプトの Promise が消えます。
                    if (gone.get() || CONTEXT_GONE.containsMatchIn(e.errorMessage)) ScriptRun.Navigated else ScriptRun.Failed("script_error", e.errorMessage)
                })
            }

            launch {
                for (c in calls) {
                    val stop = busy.withLock {
                        if (outcome.isCompleted) return@withLock true
                        preflight()?.let { outcome.complete(it); return@withLock true }
                        val result = try {
                            bridge.call(c.op, c.args).let { r -> (r["read"] as? JsonObject)?.let { readPage(reader.get(), it) } ?: r }
                        } catch (a: ScriptAbort) {
                            outcome.complete(ScriptRun.Failed(a.error, a.message.orEmpty()))
                            return@withLock true
                        }
                        if (gone.get()) { outcome.complete(ScriptRun.Navigated); return@withLock true }
                        try {
                            session.send("Runtime.evaluate", buildJsonObject {
                                put("expression", PageScriptProtocol.settle(c.id, result)); put("contextId", contextId)
                            }, sessionId, STEP_TIMEOUT_MS)
                            false
                        } catch (e: CdpException) {
                            outcome.complete(ScriptRun.Navigated)
                            true
                        } catch (e: CdpTimeoutException) {
                            outcome.complete(ScriptRun.Failed("timeout", "the page stopped answering"))
                            true
                        }
                    }
                    if (stop) break
                }
            }

            // は、アクションが実行されていない場合にのみウォッチを実行します。
            launch {
                var left = timeoutMs
                while (left > 0) {
                    delay(TICK_MS)
                    if (!busy.isLocked) left -= TICK_MS
                }
                outcome.complete(ScriptRun.Failed("timeout", "the script did not finish within $timeoutMs ms"))
            }

            val result = outcome.await()
            busy.withLock { }
            result
        } finally {
            withContext(NonCancellable) {
                if (context.get() >= 0 && !gone.get()) {
                    val ended = try {
                        session.send("Runtime.evaluate", buildJsonObject { put("expression", PageScriptProtocol.END); put("contextId", context.get()) }, sessionId, STEP_TIMEOUT_MS)
                        true
                    } catch (e: CdpTimeoutException) {
                        false
                    } catch (e: Exception) {
                        true
                    }
                    // 最後の応答が待ちきれません。スクリプトは同期された無限ループで実行され、ページを占有しています。現時点では中断のみを行ってください (実行中に中断しないと、ページ上の次のスクリプトが誤って破損する可能性があります)
                    if (!ended) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
                }
            }
            coroutineContext.cancelChildren()
        }
    }

    private class Worlds(val frameId: String, val script: Int, val reader: Int)

    /** は、読み取りワールドで検証されたパラメーターに従って DOM を読み取り、純粋なデータのみを返します。 */
    private suspend fun readPage(ctx: Int, spec: JsonObject): JsonObject {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", PageScriptProtocol.read(spec)); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        val v = (r["result"] as? JsonObject)?.get("value") as? JsonObject
        if (r["exceptionDetails"] != null || v == null) return buildJsonObject { put("ok", false); put("error", "read_failed"); put("message", "the page could not be read") }
        if (v.toString().length > READ_RESULT_MAX_CHARS) return buildJsonObject {
            put("ok", false); put("error", "too_large"); put("message", "the result is too large; ask for fewer items (limit), fewer fields or a smaller maxChars")
        }
        return v
    }

    /**
     * は、スクリプト ワールド (送信ボックス、`mika`、実行中のモデル コード、ページ オブジェクトを取得できません) と読み取りワールド (ミカ固定リーダーのみ) の 2 つの独立した世界を構築します。
     */
    private suspend fun setUp(world: String): Worlds {
        // ランタイムがオンになっていません: オンになっている場合、ページはデバッガーを検出できます (一部の Web サイトはこのため常にリロードされます)。世界の分離や評価には必要ありません。
        // チェックすべきこと: ジャンプ リクエスト、ネットワーク リクエスト (すでに開いている場合は再度開いても問題ありません)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", world)
            // プロトコルのパラメーター名の綴りは次のとおりです。
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(ctx, PageScriptProtocol.PRELUDE)
        val readerCtx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", PageScriptProtocol.READER_WORLD_PREFIX + world.removePrefix(PageScriptProtocol.WORLD_PREFIX))
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(readerCtx, PageScriptProtocol.READER)
        return Worlds(frameId, ctx, readerCtx)
    }

    /** がインストールできない場合、モデルコードは実行されません。 */
    private suspend fun install(ctx: Int, source: String) {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", source); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, PageScriptProtocol.error(it).first, "Runtime.evaluate") }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.requestUrl() = ((this["request"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.take(200).orEmpty()

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TICK_MS = 100L
        const val READ_RESULT_MAX_CHARS = 200_000
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
