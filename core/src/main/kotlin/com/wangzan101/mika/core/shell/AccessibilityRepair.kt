package com.wangzan101.mika.core.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** アプリで表示されるミカ アクセシビリティ サービス: システム リストにあるかどうか ([enabled])、サービス インスタンスが接続されているかどうか ([connected])。 */
data class A11yObservation(val enabled: Boolean, val connected: Boolean) {
    /**
     * サービスが実行されていないため、修復する必要があります: システム内で開いているがバインドされていない (プロセスが強制終了されるとシステムがクラッシュする) かどうか。
     * はまだシステム リストから削除されました (システムは強制停止時にこれを実行します)。ユーザーがこれをオフにしたい場合は、「アクセシビリティをオンにする」をオフにします。
     */
    val stuck: Boolean get() = !connected
}

/** ユーザー補助サービスの自動再起動はどの段階で行われますか: サービスを使用したい当事者は、もう少し待つか、すぐに中止するかを決定します。 */
enum class RepairPhase {
    /** 修復するものは何もありません (サービスは接続されています。ユーザーがミカでサービスをオフにして私に代わってオンにしました。アシスタントは存在しません。2 回試しましたが、諦めました)。 */
    Idle,
    /** がスタックしています。システムが自動的に接続するかどうかを確認するまで、しばらくお待ちください。 */
    Pending,
    /** は削除されて再インストールされ、サービスの接続を待機しています。 */
    Repairing,
}

/** アクセシビリティ サービスの最後の自動再起動の結果 (メモリにのみ記録されます。設定ページに何かを言ってください)。 */
data class RepairOutcome(val restarted: Boolean, val atMillis: Long, val attempts: Int)

/**
 * Mika's accessibility service is not automatically turned on at runtime: use the built-in assistant to write Mika's service back to the system list at runtime.
 * ([RecoveryPermissions.restartAccessibility]: If it is in the list, remove it and add it back to let the system rebind it. If it is not in the list, add it directly).
 * App When you have the authorization to modify the system settings ([direct]), write directly without waiting for the assistant (wireless debugging can also be repaired if it is turned off).
 * は、サービス ([A11yObservation])、組み込みアシスタント、ユーザーがロック解除されているかどうかの 3 つのステータスのみを確認します。変更はポーリングなしで再判断されます。
 *
 * - 実行されていない限り修正します。ユーザーがミカで「アクセシビリティをオンにする」をオフにしている場合のみ。
 * - When the user has not unlocked the computer once (the encrypted credential storage has not yet been unlocked), services that do not support direct startup cannot be bound: it will not be repaired at this time.
 *   waits for the user to unlock it (unlocked of [watch] becomes true) and then make a judgment again, and the repair will still be done.
 * - スタックした場合は、まず待ちます。 [GRACE_MS]: プロセスが開始されたばかりの場合、通常、システムはすぐに接続するため、何もする必要はありません。
 * - スタックするたびに最大 [MAX_ATTEMPTS] 回試行し、間隔は徐々に長くなります。それでも接続できない場合は停止し、インターフェイス上に手動処理を求めるプロンプトが表示されます。
 *   時間はサービス接続時のみ経過し、切断後は再度カウントされます。 True when the
 */
class AccessibilityRepair(
    private val restart: suspend (gapMs: Long) -> PermissionResult,
    private val consent: () -> RecoveryConsent,
    /** App can write system settings by itself (obtains authorization to modify system settings): there is no need to wait for the built-in assistant. */
    private val direct: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _last = MutableStateFlow<RepairOutcome?>(null)
    val last: StateFlow<RepairOutcome?> = _last.asStateFlow()

    private val _phase = MutableStateFlow(RepairPhase.Idle)
    val phase: StateFlow<RepairPhase> = _phase.asStateFlow()

    private enum class Result { Restored, GaveUp, Interrupted }

    /**
     * キャンセルされるまで視聴しました。修復作業中の状態変化（お客様ご自身での削除、再追加を含む）については、最新バージョンに基づいて再度判断させていただきます。
     * [unlocked] ユーザーが起動後にロックを解除したかどうかです。ロックが解除されていない場合は修理できません。ロック解除時の変更はここで再度判断されます。
     */
    suspend fun watch(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean> = MutableStateFlow(true),
    ) {
        var exhausted = false
        combine(service, helper, unlocked, ::Triple).conflate().collect { (s, h, u) ->
            if (s.connected) {
                exhausted = false
                return@collect
            }
            if (exhausted || !eligible(s, h, u)) return@collect
            try {
                _phase.value = RepairPhase.Pending
                delay(GRACE_MS)
                if (!eligible(service.value, helper.value, unlocked.value)) return@collect
                _phase.value = RepairPhase.Repairing
                exhausted = repair(service, helper, unlocked) == Result.GaveUp
            } finally {
                _phase.value = RepairPhase.Idle
            }
        }
    }

    private fun eligible(s: A11yObservation, h: BuiltinShellStatus, unlocked: Boolean) =
        s.stuck && unlocked && (direct() || h == BuiltinShellStatus.Running) && consent().wants(RecoveryCapability.Accessibility)

    private suspend fun repair(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean>,
    ): Result {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(BACKOFF_MS * (attempt - 1))
                val s = service.value
                if (s.connected) return Result.Restored.also { record(true, attempt - 1) }
                if (!eligible(s, helper.value, unlocked.value)) return Result.Interrupted
            }
            if (restart(GAP_MS * attempt) == PermissionResult.Done &&
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.first { it.connected } } != null
            ) {
                return Result.Restored.also { record(true, attempt) }
            }
        }
        record(false, MAX_ATTEMPTS)
        return Result.GaveUp
    }

    private fun record(restarted: Boolean, attempts: Int) {
        _last.value = RepairOutcome(restarted, now(), attempts)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_ATTEMPTS = 2
        /** 削除と追加戻しの間の一時停止が連続して長くなります (1 秒、2 秒)。2 つの書き込みが近すぎるため、システムは最後のリストしか参照できない可能性があります。 */
        const val GAP_MS = 1_000L
        const val BACKOFF_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** How long does it take at the longest time from getting stuck to giving up: wait for a while, remove it twice and then return it, wait for each connection, retreat once in the middle, and leave some margin for running commands. */
        const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * CONNECT_TIMEOUT_MS + GAP_MS * 3 + BACKOFF_MS + 2_000L

        /**
         * アクセシビリティ サービスを使用したい場合は待ちます。最初は通常どおり待ちます [baseMs]。まだ接続されていませんが、修復中または修復中 ([phase] はアイドル状態ではありません)、
         * その後、接続または修復された側が停止するまで待ちます (修復後、サービスはすでに接続されており、放棄された場合は null を返します)、合計が [capMs] を超えないようにします。
         * に修理するものが何もない場合は、以前と同じになります。 [baseMs] が到着すると null が返されます。
         */
        suspend fun <T : Any> awaitService(
            service: StateFlow<T?>,
            phase: StateFlow<RepairPhase>,
            baseMs: Long,
            capMs: Long = WORST_CASE_MS,
        ): T? {
            withTimeoutOrNull(baseMs) { service.filterNotNull().first() }?.let { return it }
            if (phase.value == RepairPhase.Idle) return service.value
            return withTimeoutOrNull((capMs - baseMs).coerceAtLeast(0)) {
                combine(service, phase, ::Pair).first { (s, p) -> s != null || p == RepairPhase.Idle }.first
            }
        }
    }
}
