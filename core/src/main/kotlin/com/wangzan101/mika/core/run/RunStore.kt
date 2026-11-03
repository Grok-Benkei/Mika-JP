package com.wangzan101.mika.core.run

/** 実行ステータスの最小永続レコード。操作が中断された後は、通知されて再実行が許可されるだけであり、画面から実行を再開することはできません。 */
data class StoredRun(
    val id: RunId,
    val threadId: String,
    val goal: String,
    val phase: RunPhase,
    val reply: String? = null,
    val deliveryId: String? = null,
    val roundInProgress: Boolean = false,
    val triggerWindowKey: String? = null,
    val runSucceeded: Boolean? = null,
)

interface RunStore {
    fun save(record: StoredRun)
    fun get(id: RunId): StoredRun?
    fun all(): List<StoredRun>

    /** は再起動中に中断を 1 つずつマークするため、サイレントにツールを呼び出して再度実行することはできません。 [live] には、現時点でまだ実行中のものが含まれているため、移動しないでください。 */
    fun interruptUnfinished(live: Set<RunId> = emptySet()): List<StoredRun> {
        val interrupted = all().filter { it.phase != RunPhase.Ended && it.phase != RunPhase.Interrupted && it.id !in live }
        interrupted.forEach { save(it.copy(phase = RunPhase.Interrupted)) }
        return interrupted
    }
}

class MemoryRunStore : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()
    @Synchronized override fun save(record: StoredRun) { records[record.id] = record }
    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()
}
