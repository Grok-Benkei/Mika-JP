package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.uistate.AppWait

sealed interface StartResult<out R> {
    data class Started<R>(val run: R) : StartResult<R>
    /** は並列制限を超えており、キュー内で [position] にランクされています。 */
    data class Queued<R>(val run: R, val position: Int) : StartResult<R>
    /** この会話にはすでにタスク (実行中またはキューイング) があります。M1 ルールに従って受け入れられず、会話自体がキューに入れられます。 */
    data object AlreadyRunningInConversation : StartResult<Nothing>
}

/**
 * 会話インデックス、同時実行の上限、およびタスク全体のグローバル ロックによるタスクの実行。上限が1(スイッチOFF)の場合はM1ずつとなります。
 * [create] 登録中にタスク オブジェクトを作成します。 R は製品では [TaskRun] ですが、テストでは通常のオブジェクトに置き換えられます。
 */
class TaskCenter<R : Any>(
    private val limit: () -> ParallelLimit,
    private val conversationOf: (R) -> String,
    /** タスクの一意の番号 (通知ボタンに表示されます)。同じ会話の前後で 2 つのタスクの数が異なります。 */
    private val keyOf: (R) -> String,
    val store: RunStore = MemoryRunStore(),
    val resources: ResourceScheduler = ResourceScheduler(),
    private val create: (conversationId: String, goal: String) -> R,
) {
    private val running = mutableListOf<R>()
    private val queued = mutableListOf<R>()
    private val deferred = ArrayDeque<Pair<String, String>>()
    /** 移行の呼び出し元は引き続きここからリソースを取得し、実際の所有権は [resources] に属します。 */
    val clipLock get() = resources.clipboard
    val takeoverQueue get() = resources.takeoverQueue

    @Synchronized
    fun tryStart(conversationId: String, goal: String, queueInConversation: Boolean = false): StartResult<R> {
        val sameConversation = byConversation(conversationId) != null
        if (sameConversation && !queueInConversation) return StartResult.AlreadyRunningInConversation
        val run = create(conversationId, goal)
        return if (!sameConversation && running.size < limit().max) {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Preparing))
            running += run
            StartResult.Started(run)
        } else {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Queued))
            queued += run
            StartResult.Queued(run, queued.size)
        }
    }

    @Synchronized fun runs(): List<R> = running + queued
    @Synchronized fun running(): List<R> = running.toList()
    @Synchronized fun queued(): List<R> = queued.toList()
    @Synchronized fun isQueued(run: R): Boolean = queued.any { it === run }
    @Synchronized fun byConversation(id: String): R? = runs().firstOrNull { conversationOf(it) == id }
    @Synchronized fun byKey(key: String): R? = runs().firstOrNull { keyOf(it) == key }

    /**
     * タスク [owner] は独自の画面で開く必要があります [pkg]: 誰も占有していないか、単独で占有されているか、占有されているタスクがもうそれを使用していません ([stillUsed] は false、
     * (たとえば、アプリが単独で閉じられた場合) は占有され、true を返します。それ以外の場合、別のタスクが使用中の場合は false を返します。小切手と会計は同じロック内にあります。同じアプリを同時に開くことができるのは、そのうちの 1 つだけです。
     */
    @Synchronized
    fun reserve(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean {
        return resources.reserveApp(pkg, owner, { byKey(it) != null }, stillUsed)
    }

    /** タスク [owner] 現時点で [pkg] を占有できますか (チェックのみで占有はできません。ルールは [reserve] と同じです)。 */
    @Synchronized
    fun appFree(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean =
        resources.appFree(pkg, owner, { byKey(it) != null }, stillUsed)

    /**
     * タスク [owner] と他のタスクは、[pkg] から [timeoutMs] まで解放されます (「[ResourceScheduler.awaitApp]: 互いに等しくない」を参照)。
     * ハングして待機しますが、ここではロックを取得しません。 [実行] をクリックすると、その時点でタスク リストの待機が開始されます。
     */
    suspend fun awaitApp(pkg: String, owner: String, timeoutMs: Long, free: suspend () -> Boolean): AppWait {
        val active = synchronized(this) { runs().map(keyOf).toSet() }
        return resources.awaitApp(pkg, owner, timeoutMs, { it in active }, free)
    }

    /** がまだキュー内にある場合は、キューから削除され、true が返されます (呼び出し元はキャンセル済みとして記録します)。実行が開始されている場合は false が返され、タスクはキャンセルします。 */
    @Synchronized
    fun cancelIfQueued(run: R): Boolean {
        if (queued.none { it === run }) return false
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended)) }
        return queued.removeAll { it === run }
    }

    /** タスクは終了します (またはキュー中にキャンセルされます)。キューから解放され、実行を開始する必要があるタスクを返します。 */
    @Synchronized
    fun onEnded(run: R): List<R> {
        if (queued.removeAll { it === run }) return emptyList()
        running.removeAll { it === run }
        resources.releaseOwner(keyOf(run))
        val started = mutableListOf<R>()
        while (queued.isNotEmpty() && running.size < limit().max) {
            val index = queued.indexOfFirst { candidate ->
                running.none { conversationOf(it) == conversationOf(candidate) } &&
                    queued.takeWhile { it !== candidate }.none { conversationOf(it) == conversationOf(candidate) }
            }
            if (index < 0) break
            val next = queued.removeAt(index)
            running += next
            store.get(RunId(keyOf(next)))?.let { store.save(it.copy(phase = RunPhase.Preparing)) }
            started += next
        }
        return started
    }

    @Synchronized fun markRunning(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Running, roundInProgress = true)) }
    }

    @Synchronized fun markResult(run: R, reply: String?, deliveryId: String? = null, successful: Boolean? = null) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Aftercare, reply = reply,
            deliveryId = deliveryId, runSucceeded = successful)) }
    }

    @Synchronized fun markDelivered(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
    }

    /**
     * 終了境界が受信され、ユーザーの元の単語を元の実行に挿入できなくなります。現時点でタスクがない場合 (呼び出し元がその場で開始する)、このダイアログは true を返します。
     * は残されて false を返し、完了後に [takeReady] に引き渡されます。チェックして同じロックに留まると、[onEnded] はずらされなくなります。
     */
    @Synchronized fun deferOrStart(threadId: String, text: String): Boolean {
        if (byConversation(threadId) == null) return true
        deferred += threadId to text
        return false
    }

    /** 会話の中でタスクがなくなった単語を削除します (クリックした順序で)。 */
    @Synchronized fun takeReady(): List<Pair<String, String>> {
        val ready = deferred.filter { (threadId, _) -> byConversation(threadId) == null }
        deferred.removeAll { (threadId, _) -> byConversation(threadId) == null }
        return ready
    }
}

/** 移行中の古い名前、既存の呼び出し元およびテストは引き続き TaskCenter に段階的に移動できます。 */
typealias TaskRegistry<R> = TaskCenter<R>
