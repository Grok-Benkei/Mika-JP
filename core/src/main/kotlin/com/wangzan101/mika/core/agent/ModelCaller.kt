package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * モデル呼び出し: 履歴は追加されるだけです。トリガー ポイントを超えると圧縮されると推定されます ([ContextCompressor]、完全な履歴は移動しません)。メイン モデルが一時的に失敗した場合は、[retry] を押して再試行します。コンテキストが長すぎる場合は、強く押して再送信します。
 * lightSteps lightSteps を開くときに [router] を押して、大きなモデルと小さなモデルに作業を分割します (ライト モデルのエラーは再試行されず、このステップはメイン モデルに直接変更されます)。各タスクは [reset] で始まります。
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
) {
    /** 各モデルには、圧縮ステータスのコピー、つまり独自のコンテキスト、それぞれの履歴があります。軽量モデルの圧縮は、メイン モデルのリクエスト プレフィックスには影響しません。 */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** このタスクの再試行に経過した時間 ([RetryPolicy.taskWaitBudgetMs] の反対)。 */
    private var taskRetryWaitMs = 0L

    /** このタスクのシステム プロンプト ワード: 作業開始時にモデルに送信された同じツール リスト ([SystemPrompt.assemble]) を押すと、タスクは変更されません。 */
    var systemPrompt: String = ""

    /** そのリクエストの前に、最新の呼び出しが成功するまでにかかった時間 (リクエストの失敗、バックオフ)。 */
    var lastRetryWaitMs = 0L
        private set

    /** このタスクでは、軽量モデルは履歴に入力された応答を返します。機種変更時にクリックすると、他の機種のプライベートデータが削除されます。 */
    private val lightReplies: MutableSet<Message> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** 実際の使用法 (メッセージ ID による) とそれを与えるモデル (リクエストのサイズのアンカー ポイント) を含む応答 ([UsageMeter])。圧縮とプレフィックスの書き換え後にクリアされます。 */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** 各モデルの最新リクエストの固定コスト見積もり。返信が履歴に入力されると、使用状況とともにアンカーポイントに記録されます。 */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        compressors.values.forEach { it.forget() }
        lightReplies.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** は履歴に返信しました。軽量モデルの場合は記録します。実際に使用されている場合は、コンテキスト サイズのアンカー ポイントとして記録します。 */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (router?.isLight(used) == true) lightReplies += m
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** から [using] に送信されるリクエスト全体の大きさ: 実際の使用状況を示す最新の応答がアンカー ポイントとして使用されます。そうでない場合は、全額が見積もられます ([UsageMeter] を参照)。 */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** このタスクでは分業が機能していますか。スイッチがオンになっており、軽量モデルは携帯電話に接続でき、回復不能なエラーによって非アクティブ化されていません。 */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** 分業を有効にすると、各通話のモデルと役割が実行記録に記録されます (簡単に比較できるように、非アクティブ化した後も記録されます)。 */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /**
     * [router] を押してモデルを選択します。軽量モデルにエラーがある、ツールが調整されていない（電話なしの返答が最終回答であり、メインモデルのみが提供する）、またはあきらめ、確認、引き継ぎ、質問、または取り返しのつかないことを行う必要があります。
     * の応答は実行されません ([ROLE_LIGHT_REJECTED] として使用されることに注意してください)。このステップでは、メインモデルが変更されます。質問を変更する前に、[restarted] を使用して新しい質問時間を報告してください。
     * は応答と使用される実際のモデルを返します。
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return ask(history, specs, step, onText = onText) to model
        val picked = r.pick(track.context(step, history.any { m -> m.parts.any { it is Part.Image } }))
        if (!r.isLight(picked)) return ask(history, specs, step, onText = onText) to model
        // 軽量モデルのコンテキストは小さい可能性があります。長すぎると報告されており、その時点でどれだけ折りたたまれているかはメイン モデルには影響しません (圧縮状態はモデルごとに個別に記録されます)。
        val asked = now()
        val light = try {
            complete(viewFor(history, picked), specs, picked, RetryPolicy.NONE, step, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // キー、クォータ、モデル名、リクエスト自体が間違っているため、再試行しても無駄です。このタスクの残りの手順ではメイン モデルを使用します。
            if (e is ModelError.Auth || e is ModelError.QuotaExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed ||
                (e is ModelError.Server && e.status in 400..499)) r.disable()
            null
        }
        val first = light?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        if (first != null && !needsMain(first)) return light to picked
        r.noteTrouble()
        val text = light?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        val display = if (text.isEmpty()) "" else "別のモデルで処理を続けています。"
        emit(RunEvent.ModelReply(
            now(), step, display, first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            light?.usage?.inputTokens, light?.usage?.outputTokens, light?.usage?.cachedInputTokens, now() - asked,
            picked.name, ROLE_LIGHT_REJECTED,
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) "light_error" else "no_tool"))
        onText?.invoke("")
        restarted(now())
        return ask(history, specs, step, onText = onText) to model
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /** は [using] (デフォルトのメイン モデル) に質問しました。送信されるのは、それに表示される履歴 ([viewFor]) の圧縮コピーです。 */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply =
        complete(viewFor(history, using), specs, using, retry, step, onText)

    /**
     * が [to] に送信された履歴: 他のモデルからの応答により、通話上の思考内容とプライベート データが削除されます (これらは、それを生成したモデルにそのまま返すことしかできません)。
     * は、サービスプロバイダーに関係なく、フィールドが存在するかどうかのみをチェックします。
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        val toLight = router?.isLight(to) == true
        if (!toLight && lightReplies.isEmpty()) return history
        return history.map { m ->
            val foreign = m.role == Role.Assistant && (m in lightReplies) != toLight
            if (!foreign || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    p is Part.Thinking -> null
                    p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    else -> p
                }
            })
        }
    }

    private suspend fun complete(history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null): ModelReply {
        var retries = 0
        lastRetryWaitMs = 0
        val began = now()
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            compaction = next
            compactions[using] = compaction
            try {
                // 別のベースが切断または折り畳まれている場合、次の書き込み操作はフルでなければなりません
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(sent.messages))
                // この時間より前に費やされたもの (失敗したリクエスト、バックオフ) はすべて、再試行待機としてカウントされます。
                lastRetryWaitMs = now() - began
                suspend fun send(): ModelReply = if (onText == null) using.complete(systemPrompt, sent.messages, specs)
                    else if (using is StreamingChatModel) using.completeStreaming(systemPrompt, sent.messages, specs, onText)
                    else using.complete(systemPrompt, sent.messages, specs).also { result ->
                        result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                            .takeIf(String::isNotEmpty)?.let(onText)
                    }
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, sent.messages) { send() }
                }
                return reply.also { r ->
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                val wait = policy.delayFor(e, retries, taskRetryWaitMs) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // 待機中のキャンセル作業が終了しました。
                delay(wait)
            }
        }
    }

    companion object {
        /** [RunEvent.ModelReply.role] の値。分業が機能しない場合は無効です。 */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** 軽量モデルの返信が実行されませんでした。このステップにより、メインモデルが変更されました。これはまだモデル呼び出しであり、使用法が考慮されています。 */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
    }
}

/** によって発行された履歴内の完全な表示読み取り値 (番号による): 要素リストによる読み取り結果と、画面オブジェクトによるアクション結果。 */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf { it["elements"] is JsonArray } ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
