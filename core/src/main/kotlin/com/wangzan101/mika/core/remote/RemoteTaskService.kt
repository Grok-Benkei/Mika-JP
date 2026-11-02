package com.wangzan101.mika.core.remote

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * リモート ポータルに共通のビジネス エラー。 HTTP ステータスと MCP isError は、それぞれのアダプターによって決定されます。
 * 入り口に応じてメッセージを変更する必要があるいくつかの状況はそれぞれサブクラスです。アダプターはタイプに応じて変更され、メッセージのテキストは比較されません。
 */
sealed class RemoteTaskError(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_ARGUMENT, NOT_FOUND, GONE, CONFLICT, UNAVAILABLE }

    /** その他のエラー: メッセージは呼び出し元にそのまま送信されます。 */
    class General(code: Code, message: String) : RemoteTaskError(code, message)
    class NoSuchTask(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task with id $id")
    class BadConversation : RemoteTaskError(Code.INVALID_ARGUMENT,
        "conversation must be one returned by an earlier remote task (looks like mcp-<uuid>)")
    /** この手順では、ユーザーが携帯電話で処理する必要があり、リモートで応答することはできません。 私がそれを見つけたとき、 */
    class NeedsPhone : RemoteTaskError(Code.CONFLICT, RemoteStatus.ON_PHONE_NOTE)
    /** はまだ質問していましたが、回答を送信した時点で質問は消えていました。 */
    class QuestionClosed : RemoteTaskError(Code.CONFLICT, "The question is no longer open; check the task's status.")
}

data class RemoteWait(val task: RemoteTask, val changed: Boolean)
data class RemoteCancellation(val taskId: String, val accepted: Boolean, val status: String? = null)
data class RemoteSteerReceipt(val taskId: String, val messageId: String)

/**
 * CLI API と MCP で共有されるタスクのユースケース。 HTTP、JSON-RPC、ツール名、コマンドライン引数はありません。
 * は、[RemoteTasks] を介して携帯電話上の元のタスク サービスにのみアクセスし、別のエグゼキュータまたはタスク状態を作成しません。
 */
class RemoteTaskService(private val tasks: RemoteTasks, private val pollMs: Long = 250) {
    init { require(pollMs > 0) }

    fun start(goal: String, conversation: String? = null, requestId: String? = null): RemoteTask {
        val clean = required(goal, "goal", MAX_GOAL)
        val thread = conversation?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != null && !isConversation(thread)) throw RemoteTaskError.BadConversation()
        if (requestId != null && !isUuid(requestId)) invalid("request_id must be a canonical lowercase UUID")
        return when (val r = tasks.start(clean, thread, requestId)) {
            is StartOutcome.Started -> r.task
            is StartOutcome.Refused -> throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, r.message)
        }
    }

    fun get(id: String): RemoteTask {
        val clean = id.trim()
        if (clean.isEmpty()) invalid("task_id is required and must be a string")
        return tasks.get(clean) ?: throw RemoteTaskError.NoSuchTask(clean)
    }

    fun list(limit: Int = DEFAULT_LIST): List<RemoteTask> {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        return tasks.list(limit)
    }

    /** ステータスが変更されるか終了すると、制限付き待機が返されます。待機時間がタイムアウトしても、携帯電話のタスクはキャンセルされません。待機中のタスクが見つからない場合は、最後に確認された状態に戻ります。 */
    suspend fun wait(id: String, timeoutS: Int = DEFAULT_WAIT_S): RemoteWait {
        val first = get(id)
        if (timeoutS !in 1..MAX_WAIT_S) invalid("timeout_s must be between 1 and $MAX_WAIT_S")
        val from = RemoteStatus.of(first.state)
        var now = first
        var waited = 0L
        while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeoutS * 1000L) {
            val interval = minOf(pollMs, timeoutS * 1000L - waited)
            delay(interval)
            waited += interval
            now = tasks.get(first.id) ?: break
        }
        return RemoteWait(now, RemoteStatus.of(now.state) != from)
    }

    /** はフォローアップの質問にのみ回答でき、支払い確認や携帯電話の引き継ぎを通常の回答として扱うことはできません。 */
    fun answer(id: String, text: String): String {
        val task = get(id)
        val clean = required(text, "text", MAX_ANSWER)
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_FOR_ANSWER -> Unit
            RemoteStatus.WAITING_ON_PHONE -> throw RemoteTaskError.NeedsPhone()
            else -> conflict("Task ${task.id} is not waiting for an answer (status ${RemoteStatus.of(task.state)}).")
        }
        if (!tasks.answer(task.id, clean)) throw RemoteTaskError.QuestionClosed()
        return task.id
    }

    /** は実行中のタスクにのみ追加されます。返信、承認、引き継ぎ、回復にはそれぞれ独自の入り口があります。ホストはステータスと受信箱を再度確認します。 */
    fun steer(id: String, text: String): RemoteSteerReceipt {
        val task = get(id)
        val clean = required(text, "text", MAX_STEER)
        val status = RemoteStatus.of(task.state)
        if (status == RemoteStatus.WAITING_ON_PHONE) throw RemoteTaskError.NeedsPhone()
        if (status != RemoteStatus.RUNNING) conflict("Task ${task.id} is not running (status $status). " +
            "Answer an open question or wait for it to run; steering does not start or resume a task.")
        val messageId = tasks.steer(task.id, clean)
            ?: conflict("The task no longer accepts instructions; check its status. No new task was started.")
        return RemoteSteerReceipt(task.id, messageId)
    }

    /** が受け入れられたということは、タスク サービスに引き渡されたことを意味するだけです。キャンセルされた最終状態に達するかどうかは、引き続きステータスのクエリの対象となります。 */
    fun cancel(id: String): RemoteCancellation {
        val task = get(id)
        val status = RemoteStatus.of(task.state)
        return if (RemoteStatus.finished(status)) RemoteCancellation(task.id, false, status)
        else RemoteCancellation(task.id, tasks.cancel(task.id))
    }

    private fun required(value: String, name: String, max: Int): String {
        val clean = value.trim()
        if (clean.isEmpty()) invalid("$name is required and must be a non-empty string")
        if (clean.length > max) invalid("$name is too long (at most $max characters)")
        return clean
    }

    private fun invalid(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, message)
    private fun conflict(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, message)

    companion object {
        const val DEFAULT_WAIT_S = 30
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        const val MAX_STEER = 4_000
        // は発行されたセッション番号を保持するため、新しい CLI と元の MCP が同じリモート セッションを継続できるようになります。
        private const val CONVERSATION_PREFIX = "mcp-"
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()
        private fun isUuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) && isUuid(id.removePrefix(CONVERSATION_PREFIX))
    }
}
