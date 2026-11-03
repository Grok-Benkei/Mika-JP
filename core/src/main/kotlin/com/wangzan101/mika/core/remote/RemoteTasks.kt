package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** コンピューターから引き渡されたタスク: [state] 空の場合は、タスク サービスに引き渡され、まだ登録されていないことを意味します (すぐに利用可能になります)。 */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
    val steers: List<RemoteSteer> = emptyList(),
)

/** は、コンピューターによって追加されたメッセージのみを報告します。参加とは、実行が成功したことではなく、現在のタスク コンテキストに参加することを意味します。 */
data class RemoteSteer(val messageId: String, val state: String)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/**
 * アプリ側のコンピューターに接続されたタスク ポータル: タスクは引き続きタスク サービスを通じて開始され、会話にレコードが残され、ステータスはタスク自身のものを読み取り、ここには別のコピーはありません。
 * は、コンピューターから送信されたタスクのみを認識します ([RemoteTask.id] を押します)。携帯電話で開始されたタスクは表示または移動できません。
 */
interface RemoteTasks {
    fun start(goal: String, conversation: String?, requestId: String? = null): StartOutcome
    fun get(id: String): RemoteTask?
    /** 新しい順。 */
    fun list(limit: Int): List<RemoteTask>
    /** は、タスクが質問への回答を待機しているときにのみ呼び出されます。タスクに渡されると true を返します。 */
    fun answer(id: String, text: String): Boolean
    /** アトミック確認メッセージが現在のタスクの受信箱に入りました。新しいタスクに変換できません。メッセージ番号を返します。null として拒否されます。 */
    fun steer(id: String, text: String): String?
    fun cancel(id: String): Boolean
}

/** タスクのステータスがコンピューターにとって何を意味するか。タスク自体のステータスのみが読み取られ、他の情報は記録されません。 */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val QUEUED = "queued"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered remotely. " +
        "Mika has shown its usual notification there; keep waiting or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> when {
            state.reason.needsYou -> WAITING_ON_PHONE
            state.reason.queued -> QUEUED
            else -> PAUSED
        }
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** 現時点でのタスクの外観: ステータス、最近の進行状況の説明、待機内容、および結果。 */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "Mika is asking a question. Answer this question remotely or on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        put("answerable", s is TaskState.WaitingInput)
        if (t.steers.isNotEmpty()) putJsonArray("steers") {
            t.steers.forEach { add(buildJsonObject { put("message_id", it.messageId); put("state", it.state) }) }
        }
    }
}
