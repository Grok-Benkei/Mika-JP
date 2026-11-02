package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.tool.HandBack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** は動作中の唯一の入力入口です。ユーザーの元の単語は無期限にキューに入れられてそのまま保持され、停止信号が直接配信されます。 */
class RunInbox {
    data class UserSteer(val messageId: String, val text: String, val currentTaskOnly: Boolean = false)

    internal interface Handler {
        suspend fun answer(text: String, askId: String?): Boolean
        suspend fun confirm(choice: ConfirmChoice, askId: String?): Boolean
        suspend fun handBack(result: HandBack, askId: String?): Boolean
        fun cancel()
    }

    private val lock = Any()
    private val steers = ArrayDeque<UserSteer>()
    @Volatile private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var stopRequested = false
    private var remaining: List<UserSteer> = emptyList()
    /** 各割り込みは、待機中のモデル要求をウェイクアップします (それを削除し、この文で再度要求します)。実行ツールは影響を受けません。 */
    internal val redirects = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow(false)
    /** は中断されており、まだモデルに引き渡されていません。待機は削除後に何も失われることなく早期に終了します ([waitOrSteer] を参照)。 */
    val signal = SteerSignal(pending)

    internal fun bind(to: Handler) {
        handler = to
        if (stopRequested) to.cancel()
    }

    val wasCancelled: Boolean get() = stopRequested

    fun cancel(): InputReceipt {
        val to = synchronized(lock) {
            if (closed) return InputReceipt.Closed
            stopRequested = true
            handler
        }
        to?.cancel()
        return InputReceipt.Accepted
    }

    /** リモート追加は現在のタスクにのみ属します。 close と cancel は相互に排他的です。拒否された場合、次のタスクには引き継がれません。 */
    fun steerCurrent(messageId: String, text: String): InputReceipt = synchronized(lock) {
        if (closed || stopRequested) InputReceipt.Closed
        else enqueue(UserSteer(messageId, text, currentTaskOnly = true))
    }

    suspend fun send(input: RunInput): InputReceipt = when (input) {
        is RunInput.Steer -> steer(input.messageId, input.text)
        is RunInput.Redirect -> steer(input.messageId, input.text)
        RunInput.Cancel -> cancel()
        is RunInput.Answer -> if (closed) InputReceipt.Closed else
            if (handler?.answer(input.text, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.Confirm -> if (closed) InputReceipt.Closed else
            if (handler?.confirm(input.choice, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.HandBack -> if (closed) InputReceipt.Closed else
            if (handler?.handBack(input.result, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
    }

    fun steer(id: String, text: String): InputReceipt = synchronized(lock) {
        if (closed) InputReceipt.NextTask else enqueue(UserSteer(id, text))
    }

    private fun enqueue(steer: UserSteer): InputReceipt {
        steers += steer
        pending.value = true
        redirects.trySend(Unit)
        return InputReceipt.Accepted
    }

    /** は、まだ受信トレイにあり、携帯電話に挿入されたメッセージのみを取り消します。それをモデルに渡すことと閉じることは相互に排他的です。コンピュータによって追加されたもの ([UserSteer.currentTaskOnly]) は取り消すことができず、コンピュータによって決定されます。 */
    fun withdrawSteer(id: String): Boolean = synchronized(lock) {
        val i = steers.indexOfFirst { it.messageId == id }
        if (closed || i < 0 || steers[i].currentTaskOnly) return@synchronized false
        steers.removeAt(i)
        pending.value = steers.isNotEmpty()
        if (steers.isEmpty()) while (redirects.tryReceive().isSuccess) Unit
        true
    }

    /** モデルにまだ配信されていない中断はありますか?これらは無視されるだけです (サブステップ間ではそれに応じてバッチ実行が停止します)。 */
    fun hasSteers(): Boolean = synchronized(lock) { steers.isNotEmpty() }

    /** モデルの呼び出し前、またはツールのバッチが完了した後にのみ取得されます。不要になったリダイレクト ウェイクアップをクリアします。 */
    internal fun takeSteers(): List<UserSteer> = synchronized(lock) {
        val out = steers.toList()
        steers.clear()
        pending.value = false
        while (redirects.tryReceive().isSuccess) Unit
        out
    }

    /** 終了後、未使用のコメントは次のタスクとしてホストに渡されます。 */
    internal fun close(): List<UserSteer> = synchronized(lock) {
        if (closed) return@synchronized remaining
        closed = true
        handler = null
        redirects.close()
        pending.value = false
        remaining = steers.toList().also { steers.clear() }
        remaining
    }

    /**
     * 応答が引き渡されて終了が完了したときに呼び出されます。モデルに引き渡されていない割り込みがまだ存在しており、閉じられません (false を返し、呼び出し元はそれを受け取って続行します)。
     * が使用できない場合は、それを閉じます (true を返す)。その後の割り込みは次のタスクになります。見ているのと同じ鍵の中に閉じ込められているのでは、その間に何も言うことはできません。
     */
    internal fun closeUnlessSteered(): Boolean = synchronized(lock) {
        if (steers.isNotEmpty()) return@synchronized false
        close()
        true
    }

    fun nextTaskMessages(): List<UserSteer> = synchronized(lock) { remaining.toList() }

    /** ホストが終了するか、まだ開始されていないタスクをキャンセルすると、消費されなかったユーザーの元の単語は次のタスクに引き継がれます。 */
    fun handoffUnconsumed(): List<UserSteer> = synchronized(lock) {
        close()
        remaining.also { remaining = emptyList() }
    }
}
