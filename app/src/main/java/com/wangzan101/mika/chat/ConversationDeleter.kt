package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** 会話を削除するとどうなりますか: 実行中のタスクまたはキューに入れられたタスク (停止されます) はありますか、それに関連付けられているスケジュールされたタスクの数 (一緒に削除されます)。 */
data class DeleteImpact(val runningTask: Boolean, val schedules: Int)

/**
 * 会話を削除します。まず、会話に関連付けられているスケジュールされたタスクを削除し (新しいタスクが開始されないようにする)、次に実行中のタスクとキューに入れられたタスクを停止して、会話が終了するのを待ちます。
 * (最後に保存されたラウンドも削除の範囲内) を削除し、そのワークスペース (添付ファイル、ユーザーによって送信された画面、タスクによって書き込まれたファイル) と会話記録を削除します。
 * 実行レコード (実行統計用) はグローバルであり、削除されません。
 */
class ConversationDeleter(
    private val store: ConversationStore,
    /** 実行中またはキューに入れられたタスクとのダイアログ。 */
    private val active: StateFlow<List<String>>,
    /** この会話の実行中のタスクとキューに入れられたタスクをすべて停止します。 */
    private val cancelTasks: (conversationId: String) -> Unit,
    /** この会話に関連付けられたスケジュールされたタスクの番号。 */
    private val schedulesOf: (conversationId: String) -> List<String>,
    private val removeSchedule: (id: String) -> Unit,
    /** この会話のワークスペースを削除します。 */
    private val deleteWorkspace: suspend (conversationId: String) -> Unit,
    /** タスクを停止した後、タスクが完了するまで最大でこのくらいの時間待つことができます。その後削除してください。 */
    private val stopWaitMs: Long = 15_000,
    /** スケジュールされたタスクと会話の記録を読み書きする場所。 */
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetRemoteTasks: (String) -> Unit = {},
) {
    fun impact(conversationId: String): DeleteImpact =
        DeleteImpact(conversationId in active.value, runCatching { schedulesOf(conversationId).size }.getOrDefault(0))

    suspend fun delete(conversationId: String) {
        withContext(io) {
            runCatching { schedulesOf(conversationId) }.getOrDefault(emptyList()).forEach { runCatching { removeSchedule(it) } }
        }
        if (conversationId in active.value) {
            cancelTasks(conversationId)
            withTimeoutOrNull(stopWaitMs) { active.first { conversationId !in it } }
        }
        // ワークスペースを削除できない場合（動作環境がサポートしていない場合など）、会話レコードは削除されます。
        try { deleteWorkspace(conversationId) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        withContext(io) {
            forgetRemoteTasks(conversationId)
            store.delete(conversationId)
        }
    }
}
