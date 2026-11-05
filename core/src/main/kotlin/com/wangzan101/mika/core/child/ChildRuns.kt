package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class ChildPhase { Running, Completed, Interrupted, Unknown }

@Serializable data class ChildRecord(val id: String, val parentId: String, val phase: ChildPhase,
    val result: String? = null, val atMs: Long)

interface ChildRunStore {
    fun save(record: ChildRecord)
    fun get(id: String): ChildRecord?
    fun all(): List<ChildRecord>
}

class MemoryChildRunStore : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()
    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()
}

data class ChildResult(val id: RunId, val phase: ChildPhase, val text: String)

fun interface ChildRunHost { suspend fun spawn(spec: RunSpec, format: String?): ChildResult }

/** 子の Run は最初に結論を保存し、それから親 Run に渡します。親の Run が消えると、Unknown レコードが保持され、自動的に再実行されません。 */
class ChildAgentRunner(
    private val create: (RunSpec) -> AgentLoop,
    private val store: ChildRunStore,
    private val ownerAlive: (RunId) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : ChildRunHost {
    private val active = HashMap<RunId, MutableSet<AgentLoop>>()

    override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
        require(spec.parent != null && spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Child)
        val parent = spec.parent
        val agent = create(spec)
        synchronized(active) { active.getOrPut(parent) { mutableSetOf() } += agent }
        store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Running, atMs = clock()))
        try {
            val end = agent.run(spec, RunInbox())
            val raw = when (end) {
                is TaskState.Succeeded -> end.answer
                is TaskState.Failed -> end.reason
                TaskState.Cancelled -> INTERRUPTED
                else -> "no result"
            }
            val phase = if (end == TaskState.Cancelled) ChildPhase.Interrupted else ChildPhase.Completed
            val checked = if (format == "json" && runCatching { Json.parseToJsonElement(raw) }.isFailure)
                "$raw\n（子タスクのJSONは検証に合格しませんでした。原文を保持しています）" else raw
            // 保存するプレビューだけを短くし、親へ返す本文は spawn_agent が要求された上限で切り詰め、元の長さを報告します。
            val preview = checked.take(MAX_RESULT_CHARS)
            store.save(ChildRecord(spec.id.value, parent.value, phase, preview, clock()))
            if (!ownerAlive(parent)) {
                store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Unknown, preview, clock()))
                return ChildResult(spec.id, ChildPhase.Unknown, checked)
            }
            return ChildResult(spec.id, phase, checked)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Interrupted, INTERRUPTED, clock())) }
            throw e
        } finally {
            synchronized(active) { active[parent]?.remove(agent); if (active[parent]?.isEmpty() == true) active.remove(parent) }
        }
    }

    fun cancelAll(parent: RunId) { synchronized(active) { active[parent]?.toList() }.orEmpty().forEach(AgentLoop::cancel) }

    companion object {
        /** 保存するプレビューの上限です。親タスクへ返す本文の上限ではありません。 */
        const val MAX_RESULT_CHARS = 4000
        private const val INTERRUPTED = "interrupted"
    }
}
