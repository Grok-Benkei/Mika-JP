package com.wangzan101.mika.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError

/**
 * を受信します。コンピューターによって開始されたタスクのみが保存され、進行状況は実行中の TaskRun から読み取られます。
 * ディスク上の最終状態と割り込み宛先は、[RemoteTaskRecord] の形式で保存され、フィールド タイプは直接シリアル化されません。
 * [KEEP_MS] / [KEEP] を押して、エンディングおよび削除された会話によって残されたスペースをクリアします (受信時と開くときにそれぞれ 1 回クリアします)。エンディングは明確ではありません。
 * がクリアされると、同じ request_id が新しいリクエストとして扱われるため、重複排除はこのウィンドウでのみ確立されます。
 */
internal class RemoteTaskReceipts(
    context: Context, name: String = "remote-tasks.db",
    private val clock: () -> Long = System::currentTimeMillis, private val keep: Int = KEEP,
) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            id TEXT PRIMARY KEY NOT NULL, conversation TEXT, goal TEXT, started INTEGER NOT NULL,
            new_chat INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, result TEXT, steers TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unknown remote task receipt schema $oldVersion -> $newVersion")

    // MikaApp owns one hub per process. A previous process cannot still be dispatching these tasks.
    init {
        val interrupted: TaskState.Terminal = TaskState.Failed("The phone process stopped before this task finished.", FailKind.Interrupted)
        writableDatabase.execSQL("UPDATE receipts SET result=? WHERE result IS NULL AND goal IS NOT NULL",
            arrayOf(RemoteTaskRecord.encode(interrupted)))
        prune(clock())
    }

    /**
     * は、終了した (結果が空ではない) および削除された会話によって残されたプレースホルダー (目標が空である) のみをクリアします。 [KEEP_MS] を超えるもの、および受信順に最新の [keep] 受信以外のもの。
     * 終了していないものはまだ実行されている可能性があるため、保存しておいてください。プロセスが再構築されると、それらは中断としてマークされます。
     */
    private fun prune(now: Long) {
        writableDatabase.execSQL("""DELETE FROM receipts WHERE (result IS NOT NULL OR goal IS NULL) AND
            (started < ? OR rowid <= (SELECT rowid FROM receipts ORDER BY rowid DESC LIMIT 1 OFFSET ?))""",
            arrayOf<Any>(now - KEEP_MS, keep))
    }

    private fun Cursor.task(): RemoteTask {
        if (isNull(2)) throw RemoteTaskError.General(RemoteTaskError.Code.GONE, "This task's conversation was deleted.")
        return RemoteTask(getString(0), getString(1), getString(2), getLong(3),
            getString(4)?.let(RemoteTaskRecord::decode),
            getString(5)?.let(RemoteTaskRecord::decodeSteers).orEmpty())
    }

    private val columns = "id, conversation, goal, started, result, steers, new_chat"

    fun find(id: String, goal: String? = null, requestedChat: String? = null): RemoteTask? =
        readableDatabase.rawQuery("SELECT $columns FROM receipts WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val task = cursor.task()
            val originalChat = if (cursor.getInt(6) != 0) null else task.conversation
            if (goal != null && (goal != task.goal || requestedChat != originalChat)) {
                throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, "This request_id was already used for a different task.")
            }
            task
        }

    fun recent(limit: Int): List<RemoteTask> = readableDatabase.rawQuery(
        "SELECT $columns FROM receipts WHERE goal IS NOT NULL ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.task()) } }

    fun insert(task: RemoteTask, newChat: Boolean) {
        writableDatabase.insertOrThrow("receipts", null, ContentValues().apply {
            put("id", task.id); put("conversation", task.conversation); put("goal", task.goal)
            put("started", task.createdAtMs); put("new_chat", newChat)
        })
        prune(clock())
    }

    /** Android は、プロセスが再構築された後にのみキューに入れられたインテントを配信することができ、中断または受信されたインテントは実行できません。 */
    fun claimStart(id: String): Boolean = writableDatabase.update("receipts", ContentValues().apply { put("claimed", 1) },
        "id=? AND goal IS NOT NULL AND result IS NULL AND claimed=0", arrayOf(id)) == 1

    fun finish(id: String, state: TaskState.Terminal, steers: List<RemoteSteer>, onlyUnclaimed: Boolean = false): Boolean {
        val condition = "id=? AND goal IS NOT NULL AND result IS NULL" + if (onlyUnclaimed) " AND claimed=0" else ""
        return writableDatabase.update("receipts", ContentValues().apply {
            // リモート インターフェイスは、回答の証拠台帳を公開しません。
            val end = if (state is TaskState.Succeeded) state.copy(sources = emptyList()) else state
            put("result", RemoteTaskRecord.encode(end))
            if (steers.isNotEmpty()) put("steers", RemoteTaskRecord.encodeSteers(steers))
        }, condition, arrayOf(id)) == 1
    }

    fun forget(conversation: String) {
        // は占有された番号を残し、遅れて再試行しても削除されたタスクを再実行できません。受信時刻は保持され、プレースホルダーと終了レコードは時間に従ってクリアされます。
        writableDatabase.execSQL("UPDATE receipts SET conversation=NULL, goal=NULL, result=NULL, steers=NULL, claimed=0, new_chat=0 WHERE conversation=?",
            arrayOf(conversation))
    }

    companion object {
        /** レコードとプレースホルダーは、取引終了後 30 日間保持されます。コンピューターは、レシートを紛失した場合でもこの番号を認識し、翌日に確認または再試行します。 */
        const val KEEP_MS = 30L * 24 * 60 * 60_000
        /** が残すクローズ済みアイテムまたはプレースホルダー アイテム (受信順に最も近いものを取得します) は、多くても非常に多く、コンピューターが一度に再チェックできるタスクの数よりもはるかに多くなります。 */
        const val KEEP = 1_000
    }
}
