package com.wangzan101.mika.memory

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.ui.LearningActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 結果カードのメモリとスキル プロンプト ボタンは、プロセス内で同じメモリと個人スキル ([memory]) に作用します。読み取りと書き込みの両方がバックグラウンド スレッドで行われます。 */
class AppLearningActions(private val context: Context, private val memory: MemoryRuntime) : LearningActions {
    private val store get() = memory.store()
    private val skills get() = memory.skills()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun undo(token: String) = io { store.undo(token) }
    override suspend fun approve(pendingId: String) = io { store.approve(pendingId) }
    override suspend fun forget(memoryId: String) = io { store.forget(memoryId).isSuccess }
    override fun viewSkill(draftId: String) = memory.requestOpen(MemoryOpen(draftId))
    // 次回何か問題が発生したときにすぐに非アクティブ化されないように、復元時に古い結果をクリアします。
    override suspend fun restoreSkill(name: String) {
        io { skills.update(name) {
            it.copy(status = it.restoredStatus(), outcomes = emptyList())
        } }
    }
    override suspend fun deleteSkill(name: String) = io { skills.remove(name) }
    override suspend fun save(notes: RunNotes) {
        memory.publish(notes)
        io {
            runCatching {
                ConversationStore.shared(context).updateTurn(notes.conversationId, notes.runId) {
                    it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes)
                }
            }
        }
    }
}
