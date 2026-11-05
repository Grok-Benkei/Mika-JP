package com.wangzan101.mika.core.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class TriggerDefinition(
    val id: String,
    val threadId: String,
    val goal: String,
    val nextAtMs: Long,
    /** null は 1 回限りの操作です。間隔トリガーはバックログ期間をマージし、1 回の実行のみを補います。 */
    val intervalMs: Long? = null,
    /** は、最初にサンドボックス/Web ページでチェックされます。チェックが確立された後にのみ、[goal] を実行してください。 */
    val conditionGoal: String? = null,
    val enabled: Boolean = true,
    /** ポイントに到達した後は、フォアグラウンド タスクがない場合にのみ実行されます。ビジーウォッチドッグは後で再度チェックします。 */
    val idleOnly: Boolean = false,
    /** 最後の有効なチェックは、条件が満たされているかどうかです。ステータスが変化したときにのみアクションを実行するために使用されます。 */
    val lastConditionMet: Boolean? = null,
    /** リマインダーの元のテキストを修正しました。空でない場合は、モデルを開始せずにポイントに直接配信されます。 */
    val reminderText: String? = null,
    /** 指定されたタイムゾーンのローカルクロックで繰り返します。 empty は、固定ミリ秒間隔を使用するか、1 回だけ実行することを意味します。 */
    val calendarTime: String? = null,
    val calendarZone: String? = null,
    val calendarDays: List<Int> = emptyList(),
) {
    init {
        require(id.isNotBlank() && threadId.isNotBlank() && goal.isNotBlank())
        require(nextAtMs >= 0 && (intervalMs == null || intervalMs > 0))
        require(calendarTime == null || (intervalMs == null && calendarZone != null && calendarDays.all { it in 1..7 }))
    }
}

@Serializable
enum class TriggerWindowState { Claimed, Dispatched, ActionDispatched, Delivered, Skipped, Failed, Unknown }

@Serializable
data class TriggerWindow(
    val triggerId: String,
    val dueAtMs: Long,
    val claimedAtMs: Long,
    val missedIntervals: Long = 0,
    val state: TriggerWindowState = TriggerWindowState.Claimed,
    val reason: String? = null,
) {
    val key get() = "$triggerId:$dueAtMs"
}

/** claimDue は保留中の台帳と一緒に次回を書き込む必要があり、返却後にのみ配布できます。 */
interface TriggerStore {
    fun upsert(trigger: TriggerDefinition)
    fun get(id: String): TriggerDefinition?
    fun all(): List<TriggerDefinition>
    fun windows(): List<TriggerWindow>
    fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean = { true }): List<TriggerWindow>
    /** 元の次回予定時刻を変更せずに手動で 1 回実行します。 */
    fun claimNow(id: String, nowMs: Long): TriggerWindow?
    fun mark(key: String, state: TriggerWindowState, reason: String? = null): Boolean
    /** ワンタイムタスクは、結果の配信が確認された後にのみ削除されます。 */
    fun retireDeliveredOneShots()
    fun remove(id: String)
}

@Serializable data class TriggerSnapshot(val triggers: List<TriggerDefinition>, val windows: List<TriggerWindow>)

/** は純粋なメモリ実装であり、ファイル実装は同じ状態遷移に従います。 */
class MemoryTriggerStore(initial: TriggerSnapshot = TriggerSnapshot(emptyList(), emptyList())) : TriggerStore {
    private val triggers = linkedMapOf<String, TriggerDefinition>()
    private val ledger = linkedMapOf<String, TriggerWindow>()

    init {
        initial.triggers.forEach { triggers[it.id] = it }
        initial.windows.forEach { ledger[it.key] = it }
    }

    @Synchronized fun snapshot() = TriggerSnapshot(triggers.values.toList(), ledger.values.toList())

    @Synchronized override fun upsert(trigger: TriggerDefinition) { triggers[trigger.id] = trigger }
    @Synchronized override fun remove(id: String) {
        triggers.remove(id)
        ledger.entries.removeAll { it.value.triggerId == id }
    }
    @Synchronized override fun get(id: String) = triggers[id]
    @Synchronized override fun all() = triggers.values.toList()
    @Synchronized override fun windows() = ledger.values.toList()

    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> {
        val active = ledger.values.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        val due = triggers.values.filter { it.enabled && it.id !in active && it.nextAtMs <= nowMs &&
            ledger["${it.id}:${it.nextAtMs}"] == null && eligible(it) } // 和 TriggerDefinition.slotTaken 一致
        return due.map { t ->
            val interval = t.intervalMs
            val missed = if (interval == null) 0 else (nowMs - t.nextAtMs) / interval
            val window = TriggerWindow(t.id, t.nextAtMs, nowMs, missed)
            ledger[window.key] = window
            triggers[t.id] = when {
                t.calendarTime != null -> t.copy(nextAtMs = nextCalendarAt(t.calendarTime, t.calendarZone!!,
                    t.calendarDays, nowMs))
                interval != null -> t.copy(nextAtMs = t.nextAtMs + (missed + 1) * interval)
                else -> t.copy(enabled = false)
            }
            window
        }
    }

    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? {
        if (triggers[id] == null || ledger.values.any { it.triggerId == id && it.state in setOf(
                TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched) }) return null
        val at = generateSequence(nowMs) { it + 1 }.first { "$id:$it" !in ledger }
        return TriggerWindow(id, at, nowMs).also { ledger[it.key] = it }
    }

    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean {
        val old = ledger[key] ?: return false
        if (old.state == TriggerWindowState.Delivered || old.state == TriggerWindowState.Skipped || old.state == TriggerWindowState.Failed) return false
        ledger[key] = old.copy(state = state, reason = reason)
        if (state == TriggerWindowState.Delivered || state == TriggerWindowState.Skipped ||
            state == TriggerWindowState.Failed || state == TriggerWindowState.Unknown)
            pruneCompleted(old.triggerId)
        return true
    }

    private fun pruneCompleted(id: String) {
        ledger.values.filter { it.triggerId == id && it.state in setOf(
            TriggerWindowState.Delivered, TriggerWindowState.Skipped, TriggerWindowState.Failed,
            TriggerWindowState.Unknown) }
            .sortedByDescending { it.dueAtMs }.drop(100).forEach { ledger.remove(it.key) }
    }

    @Synchronized override fun retireDeliveredOneShots() {
        val retired = triggers.values.filter { it.intervalMs == null && it.calendarTime == null && ledger.values.any { w ->
            w.triggerId == it.id && w.dueAtMs == it.nextAtMs && w.state == TriggerWindowState.Delivered } }
            .map { it.id }
        retired.forEach { id -> triggers.remove(id); ledger.entries.removeAll { it.value.triggerId == id } }
    }
}

/** 現在の実行枠が記録済みか確認します。[TriggerStore.claimDue] と同じ判定を使い、実行後に次回時刻が進まない単発計画の再実行を防ぎます。 */
fun TriggerDefinition.slotTaken(windows: List<TriggerWindow>): Boolean =
    windows.any { it.triggerId == id && it.dueAtMs == nextAtMs }

/** は現地のカレンダーに従って進み、夏時間を通じて同じ時間を維持します。システムの前方調整ルールは、存在しない時間に対して使用されます。 */
fun nextCalendarAt(time: String, zone: String, days: List<Int>, afterMs: Long): Long {
    val localTime = LocalTime.parse(time)
    val zoneId = ZoneId.of(zone)
    var date = Instant.ofEpochMilli(afterMs).atZone(zoneId).toLocalDate()
    repeat(9) {
        val candidate = date.atTime(localTime).atZone(zoneId).toInstant().toEpochMilli()
        if (candidate > afterMs && (days.isEmpty() || date.dayOfWeek.value in days)) return candidate
        date = date.plusDays(1)
    }
    error("calendar schedule has no matching day")
}

private val CALENDAR_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 次回時刻の変更をカレンダーの繰返し時刻にも反映します。保存とプレビューで同じ変換を使います。 */
fun TriggerDefinition.rescheduledTo(nextAtMs: Long): TriggerDefinition {
    val local = calendarZone?.let { zone ->
        CALENDAR_CLOCK.format(Instant.ofEpochMilli(nextAtMs).atZone(ZoneId.of(zone)))
    }
    return copy(nextAtMs = nextAtMs, enabled = true, calendarTime = if (calendarTime != null) local else null)
}

/** [afterMs] 以降の初回予定を含め、計画自身のタイムゾーンと繰返し規則で実行予定を生成します。 */
fun upcoming(def: TriggerDefinition, afterMs: Long, count: Int): List<Long> {
    if (count <= 0) return emptyList()
    val out = ArrayList<Long>(count)
    when {
        def.calendarTime != null -> {
            var at = if (def.nextAtMs >= afterMs) def.nextAtMs
            else nextCalendarAt(def.calendarTime, def.calendarZone!!, def.calendarDays, afterMs)
            repeat(count) {
                out += at
                at = nextCalendarAt(def.calendarTime, def.calendarZone!!, def.calendarDays, at)
            }
        }
        def.intervalMs != null && def.intervalMs > 0 -> {
            var t = def.nextAtMs
            if (t < afterMs) t += ((afterMs - t - 1) / def.intervalMs + 1) * def.intervalMs
            repeat(count) { out += t; t += def.intervalMs }
        }
        else -> if (def.nextAtMs >= afterMs) out += def.nextAtMs
    }
    return out
}

/** スケジューリングはホストによって実装されます。コアは最初にアカウンティングを行ってからディスパッチを行う責任があり、異常な再起動が発生した場合、要求された期間は繰り返されません。 */
class TriggerEngine(private val store: TriggerStore, private val scheduleAlarm: (Long?) -> Unit,
    private val isIdle: () -> Boolean = { true }, private val idleRetryMs: Long = 60_000L,
    private val dispatch: (TriggerDefinition, TriggerWindow) -> Unit) {
    fun rearm(nowMs: Long) {
        store.windows().filter { it.state == TriggerWindowState.Claimed }
            .forEach { store.mark(it.key, TriggerWindowState.Unknown, "process restarted before dispatch confirmation") }
        val idle = isIdle()
        val windows = store.windows()
        val active = windows.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        // 記録済みの実行枠にはアラームを予約せず、実行できない期限切れ計画の空回りを防ぎます。
        scheduleAlarm(store.all().filter { it.enabled && (it.id in active || !it.slotTaken(windows)) }.minOfOrNull {
            if (it.id in active) nowMs + idleRetryMs
            else maxOf(if (it.idleOnly && !idle) nowMs + idleRetryMs else nowMs, it.nextAtMs)
        })
    }

    /** 計画が存在しない場合や、記録済みの単発計画を再開する場合は変更せず false を返します。再実行には時刻変更か即時実行を使います。 */
    fun setEnabled(id: String, enabled: Boolean, nowMs: Long): Boolean {
        val trigger = store.get(id) ?: return false
        if (enabled && trigger.slotTaken(store.windows())) return false
        store.upsert(trigger.copy(enabled = enabled))
        rearm(nowMs)
        return true
    }

        /** タイム ゾーンが変更されると、デバイスの古いタイム ゾーンに従ったローカル タイム プランが新しいタイム ゾーンに再計算されます。明示的に固定されている他のタイムゾーンの計画は変更されません。 */
    fun rezone(nowMs: Long, fromZone: String, toZone: ZoneId) {
        if (fromZone == toZone.id) return
        store.all().filter { it.enabled && it.calendarTime != null && it.calendarZone == fromZone }
            .forEach { t ->
                store.upsert(
                    t.copy(
                        calendarZone = toZone.id,
                        nextAtMs = nextCalendarAt(t.calendarTime!!, toZone.id, t.calendarDays, nowMs),
                    )
                )
            }
        rearm(nowMs)
    }

    fun fire(nowMs: Long) {
        val idle = isIdle()
        store.claimDue(nowMs) { !it.idleOnly || idle }.forEach { window ->
            val trigger = store.get(window.triggerId) ?: return@forEach
            try {
                dispatch(trigger, window)
                store.mark(window.key, TriggerWindowState.Dispatched)
            } catch (e: Exception) {
                store.mark(window.key, TriggerWindowState.Unknown, e::class.simpleName)
            }
        }
        rearm(nowMs)
    }
}
