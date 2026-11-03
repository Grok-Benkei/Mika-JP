package com.wangzan101.mika.core.selfcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** セルフテストは次のようになります。 [results] 項目ごとに 1 つずつ、[SelfCheck] の順序に従います。 */
data class SelfCheckState(
    val phase: Phase = Phase.Idle,
    val results: List<CheckResult> = SelfCheck.entries.map { CheckResult(it) },
    val facts: DeviceFacts? = null,
    val elapsedMs: Long = 0,
) {
    enum class Phase { Idle, Running, Done, Cancelled }

    fun of(check: SelfCheck): CheckResult = results[check.ordinal]

    /** 終了またはキャンセル: レポートを発行できます。 */
    val ended: Boolean get() = phase == Phase.Done || phase == Phase.Cancelled
}

/**
 * [SelfCheck] の各項目を順番に実行します。渡されない場合は最初の項目をスキップし、どの項目が欠落しているかを示します。各アイテムには独自の制限時間があり、[budgetMs] の時間全体の合計制限時間もあります。
 * 時間がなくなると残りはスキップとして記録されます。エラーが発生した場合は失敗として記録され、以降の項目は中断されません。品切れ、エラー、キャンセルに関わらず、最終的には[SelfCheckPort.release]が調整されます。
 * [state] はいつでも進行状況を表示し、インターフェイスは図面に従います。 An instance only runs once.
 */
class SelfCheckRunner(
    private val port: SelfCheckPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = TOTAL_BUDGET_MS,
) {
    private val _state = MutableStateFlow(SelfCheckState())
    val state: StateFlow<SelfCheckState> = _state.asStateFlow()

    suspend fun run(): SelfCheckState {
        val start = now()
        _state.value = SelfCheckState(SelfCheckState.Phase.Running)
        try {
            for (c in SelfCheck.entries) step(c, start)
            finish(SelfCheckState.Phase.Done, start)
        } catch (e: CancellationException) {
            finish(SelfCheckState.Phase.Cancelled, start)
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { port.release() } }
        }
        return _state.value
    }

    private suspend fun step(c: SelfCheck, start: Long) {
        val results = _state.value.results
        c.needs.firstOrNull { !results[it.ordinal].status.passed }?.let { blocker ->
            set(CheckResult(c, CheckStatus.Skipped, "前提項目「${blocker.displayName}」が未達成です（${SelfCheckReport.label(results[blocker.ordinal].status)}）", blockedBy = blocker))
            return
        }
        val left = budgetMs - (now() - start)
        if (left <= 0) {
            set(CheckResult(c, CheckStatus.Skipped, "診断全体の制限時間に達しました", Advice.OutOfTime))
            return
        }
        set(CheckResult(c, CheckStatus.Running))
        val t0 = now()
        val limit = minOf(c.timeoutMs, left)
        val outcome = try {
            withTimeoutOrNull(limit) { if (c == SelfCheck.DEVICE) device() else port.check(c) }
                ?: CheckOutcome.fail("${seconds(limit)} 秒でタイムアウトしました", Advice.TimedOut)
        } catch (e: TimeoutCancellationException) {
            // ポート内での独自の制限時間が経過しました (このセルフテストがキャンセルされたわけではありません)
            CheckOutcome.fail("診断項目の内部処理がタイムアウトしました", Advice.TimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckOutcome.fail("エラー: ${e.javaClass.simpleName}", Advice.Error)
        }
        set(CheckResult(c, outcome.status, outcome.detail, outcome.advice, durationMs = now() - t0))
        _state.value = _state.value.copy(elapsedMs = now() - start)
    }

    private suspend fun device(): CheckOutcome {
        val f = port.facts()
        _state.value = _state.value.copy(facts = f)
        return judgeDevice(f)
    }

    private fun set(r: CheckResult) {
        val s = _state.value
        _state.value = s.copy(results = s.results.map { if (it.check == r.check) r else it })
    }

    /** 終了：まだ終了していないもの（キャンセル時に実行中の内容とその後の内容）はスキップされたものとして記録されます。 */
    private fun finish(phase: SelfCheckState.Phase, start: Long) {
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            elapsedMs = now() - start,
            results = s.results.map { if (it.status.final) it else CheckResult(it.check, CheckStatus.Skipped, "キャンセルされました", Advice.Cancelled) },
        )
    }

    companion object {
        /** The total time limit of the entire self-test: the total time limit of each item is longer than this, which is far from being used up under normal circumstances. */
        const val TOTAL_BUDGET_MS = 90_000L

        private fun seconds(ms: Long) = (ms + 999) / 1000

        /**
         * 「デバイス」項目: Android 12 未満の携帯電話の操作には使用できません。この環境でアシスタントをアクティブにする方法がない場合は使用できません。
         * にはワイヤレス デバッグがなく、一部利用可能なコンピューターでのみ起動できます。
         */
        fun judgeDevice(f: DeviceFacts): CheckOutcome {
            val detail = "Android ${f.androidRelease}（SDK ${f.sdk}）, ${f.abis.firstOrNull() ?: "ABI不明"}" +
                "、ワイヤレスデバッグ: ${when (f.wirelessDebugging) { true -> "あり"; false -> "なし"; null -> "不明" }}"
            return when {
                f.sdk < DeviceFacts.MIN_PHONE_SDK -> CheckOutcome.fail("$detail。スマートフォン操作にはAndroid 12以降が必要です", Advice.PhoneTooOld)
                f.phoneControlUnavailable -> CheckOutcome.fail(
                    "$detail。開発者向けオプションがないため、この環境ではスマートフォンを操作できません", Advice.PhoneControlUnavailable,
                )
                f.wirelessDebugging == false -> CheckOutcome.partial(detail, Advice.NoWirelessDebugging)
                else -> CheckOutcome.pass(detail)
            }
        }
    }
}
