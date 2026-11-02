package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 実機テスト用のスクリプト モデル: 完了するたびに最初にレポート (回数、受信履歴) が報告され、次にテストが次のツール呼び出しにフィードされるまで待機します。 */
class ScriptedModel : ChatModel {
    class Call(val number: Int, val messages: List<Message>)

    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val replies = Channel<List<Part>>(Channel.UNLIMITED)
    private val n = AtomicInteger()
    /** 受信した完了前検証リクエストの数。 */
    val verifierRequests = AtomicInteger()
    /** 停止後のアカウント書き込みリクエストの数。 [stopSummary] を返します (デフォルトは空白、アカウントはレコードに従って直接綴られます)。これはスクリプトを考慮せず、カウントされません。 */
    val summaryRequests = AtomicInteger()
    @Volatile var stopSummary: String = ""
    /** 検証の回答を修正しました。デフォルトは合格しました。 */
    @Volatile var verdict: String = PASS
    /**
     * インターフェイスから開始されたタスクが最初のツール呼び出しを提案すると、別のモデルに「ユーザーが議論しているのか、読みたいのか、操作したいのか」を尋ねます。軽量モデルが無い場合に問われるモデルです。
     * 設定されている場合、この質問は次のように回答されるように修正されます (ACT / READ / DISCUSS)。これはスクリプトを占有せず、カウントされません。 null の場合、この質問は他の呼び出しと同様にテストする必要があります。
     */
    @Volatile var intent: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        // 答えを渡す前の検証: 修正された答え、スクリプトは考慮されておらず、カウントされません
        if (system == Verifier.PROMPT) {
            verifierRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(verdict)), StopReason.EndTurn, null)
        }
        if (system == SUMMARY_PROMPT) {
            summaryRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(stopSummary)), StopReason.EndTurn, null)
        }
        // 新しい会話のタイトル: 固定回答、スクリプトを占有せず、カウントされません
        if (system == TITLE_PROMPT) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
        intent?.let { if (system == INTENT_PROMPT) return ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }
        calls.send(Call(n.incrementAndGet(), messages))
        val parts = replies.receive()
        return ModelReply(parts, if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, null)
    }

    /** [say] は、このステップでモデルが偶然言ったことです (ステップの下のインターフェイスに表示されます)。 */
    fun feed(name: String, args: String = "{}", say: String? = null) =
        replies.trySend(listOfNotNull(say?.let { Part.Text(it) }, Part.ToolCall("c${n.get()}", name, args)))

    /** 電話なしで返信：最終的な回答をユーザーに渡し、タスクを終了します（チェックが一致しない場合は一度返却されます）。 */
    fun answer(text: String) = replies.trySend(listOf(Part.Text(text)))

    companion object {
        private val INTENT_PROMPT by lazy { Prompts.role("intent_gate") }
        private val SUMMARY_PROMPT by lazy { Prompts.role("stop_summary") }
        private val TITLE_PROMPT by lazy { Prompts.role("conversation_title") }
        const val PASS = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "met", "quote": null}], "verdict": "pass", "reason": "脚本"}"""
        const val CANNOT_VERIFY = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "unknown", "quote": null}], "verdict": "cannot_verify", "reason": "脚本:看不到结果"}"""
    }

    fun awaitCall(): Call = runBlocking {
        withTimeoutOrNull(30_000) { calls.receive() } ?: error("model not called; task state ${DeviceTaskState.state.value}")
    }
}
