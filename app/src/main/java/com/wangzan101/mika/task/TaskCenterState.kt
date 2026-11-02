package com.wangzan101.mika.task

import android.graphics.Bitmap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ユーザーは、タスクの進行中に同じ会話に単語を挿入しました。[Pending] が送信されたばかりで、タスクはまだ送信されていません。 [Received] タスクの受信箱が受け入れられました (次のステップで処理されます)。
 * [Joined] モデルは現在のタスク [Next] に引き継がれました。 次のタスクとしてタスクが終了しました。 [Cancelled] 停止または現在のタスクの終了時に使用されなかったリモート割り込み [Withdrawn] ユーザーが退会しました。
 */
enum class SteerState { Pending, Received, Joined, Next, Cancelled, Withdrawn }

/** まだモデルに引き継がれていない介入（タスクが完了すると次のものとみなされ、停止すると無効になります）。 */
val SteerState.unconsumed: Boolean get() = this == SteerState.Pending || this == SteerState.Received

/**
 * [interrupted] は、中断が削除された返信でユーザーがすでに見た単語です。 [finished] は、割り込みがモデルに引き渡される前に完了し、表示された応答です。
 * 両方とも差し込み図の上に表示されます。そうでない場合は null。
 */
data class SteerNote(
    val id: String, val text: String, val state: SteerState, val interrupted: String? = null, val finished: String? = null,
    val remote: Boolean = false,
)

/** コンピューターは、携帯電話に挿入された単語やテキストを表示せず、独自に追加されたメッセージの宛先のみを認識します。リモート割り込みは元に戻すことができません (`RunInbox.withdrawSteer`)。[SteerState.Withdrawn] は単なる疲労のため、役に立たないものとして報告します。 */
internal fun remoteSteers(notes: List<SteerNote>, finished: Boolean = false): List<RemoteSteer> = notes.filter { it.remote }.map {
    RemoteSteer(it.id, when (it.state) {
        SteerState.Pending, SteerState.Received -> if (finished) "not_applied" else "received"
        SteerState.Joined -> "joined"
        SteerState.Next, SteerState.Cancelled, SteerState.Withdrawn -> "not_applied"
    })
}

/**
 * 実行イベントでは、モデルへの中断と配信の順序: `reply_interrupted` ([interrupted]) または `reply_finished_before_steer` ([finished]) が最初になります。
 * の後にはこの一連の中断 `steer_received` が続き、その段落は最初の文 ([joined]) にのみリンクされています。
 */
class SteerJoin {
    @Volatile private var cut: String? = null
    @Volatile private var done: String? = null

    fun interrupted(text: String) { cut = text }

    fun finished(text: String) { done = text }

    /** [id] この文はモデルに与えられました。[SteerState.Joined] とマークし、メモしたばかりの単語を切ります (使用すると消えます)。 */
    fun joined(notes: List<SteerNote>, id: String): List<SteerNote> {
        val c = cut.also { cut = null }
        val d = done.also { done = null }
        return notes.map {
            if (it.id == id) it.copy(state = SteerState.Joined, interrupted = c ?: it.interrupted, finished = d ?: it.finished) else it
        }
    }
}

/** プロセス内の TaskCenter の表示ステータスは App コンテナーによって保持され、サービスはそのホストにのみ書き込みます。 */
class TaskCenterState {
    val shownLock = Any()
    @Volatile var shownRun: TaskRun? = null

    val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()
    val _activeConversations = MutableStateFlow<List<String>>(emptyList())
    val activeConversations: StateFlow<List<String>> = _activeConversations.asStateFlow()
    val _shownTask = MutableStateFlow<Pair<String, TaskState>?>(null)
    val shownTask: StateFlow<Pair<String, TaskState>?> = _shownTask.asStateFlow()
    fun run(conversationId: String): TaskRun? = _runs.value.firstOrNull { it.conversationId == conversationId }
    fun show(conversationId: String) { run(conversationId)?.show() }

    @Volatile var secondDisplayFailed = false
    val _parallelLimit = MutableStateFlow(ParallelLimit(1, null))
    val parallelLimit: StateFlow<ParallelLimit> = _parallelLimit.asStateFlow()
    fun markSecondDisplayFailed() {
        secondDisplayFailed = true
        _parallelLimit.value = ParallelLimit(1, ParallelCapability.SECOND_DISPLAY_FAILED)
    }

    val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val _takeoverStage = MutableStateFlow(Takeover.Stage.None)
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.asStateFlow()
    val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()
    val _runId = MutableStateFlow<String?>(null)
    val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()
    val _endedRunId = MutableStateFlow<String?>(null)
    val endedRunId: StateFlow<String?> = _endedRunId.asStateFlow()
    val _endedRunIds = MutableStateFlow<Map<String, String?>>(emptyMap())
    val endedRunIds: StateFlow<Map<String, String?>> = _endedRunIds.asStateFlow()
    /** は、現時点までのタスクのアクティビティ (実行中のイベントから実際に完了) を表示します。 */
    val _activities = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.asStateFlow()
    /** タスクによって受信された割り込みと、その割り込みがどこに送信されたかを表示します。 */
    val _steers = MutableStateFlow<List<SteerNote>>(emptyList())
    val steers: StateFlow<List<SteerNote>> = _steers.asStateFlow()
    /** 停止後に命令が書き込まれているいくつかの実行 (実行番号)。書き込んだ後にダイアログに記録するか、書き込めない場合は削除してください。 */
    val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()
    val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
}
