package com.wangzan101.mika.core.shell

import kotlinx.coroutines.delay

/** アシスタントを再起動するかどうかを決定する際に確認すべき点がいくつかあります。 */
data class RecoveryInputs(
    /** 本機にはペアリングキーが保存されています。 */
    val paired: Boolean,
    /** システムのワイヤレス デバッグ スイッチ;一部の携帯電話では読み取れないため、null になります。 */
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
)

enum class StopReason {
    NotPaired,
    /** ワイヤレス デバッグがオフになっています: 再びオンになるまで待ちます (スイッチを聞いている側によって再トリガーされます)。 */
    WirelessDebuggingOff,
    /** 失敗したカテゴリでは、ユーザーはアクション (再ペアリング、Wi-Fi への接続) を行う必要があり、再試行は無駄です。 */
    NeedsUser,
    OutOfAttempts,
}

sealed interface RecoveryDecision {
    data object Healthy : RecoveryDecision
    /** [delayMs] を待ってから、ペアリングされたキーを使用して再接続します。 */
    data class Try(val delayMs: Long) : RecoveryDecision
    /** 他の人が接続中です。[delayMs] を待って、重複して再接続せずに再試行してください。 */
    data class Wait(val delayMs: Long) : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

data class RecoveryOutcome(val recovered: Boolean, val attempts: Int, val stoppedBecause: StopReason?)

/**
 * アシスタントが利用できない場合に再起動するタイミング、再起動の頻度、試行回数。 USB を取り外して adbd を再起動すると、シェル ID を持つすべてのプロセスが削除されます。
 * したがって、「なぜそれが起こらなかったのか」に区別はありません。ペアリングがまだ存在している限り、ワイヤレス デバッグは確認されておらずオフになっており、失敗してもユーザーの操作は必要ありません。エスケープ ボタンを押して再接続するだけです。
 * ネットワークが信頼できるかどうかに関係なく、ネットワークを読み取れない場合は、常に最初に試してください。失敗は接続結果によって判断されます。
 */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** 初めてすぐに試してみて、その後は 2、4、8、16 秒...30 秒が上限です。 */
    fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BASE_DELAY_MS shl (attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)

    fun decide(inputs: RecoveryInputs, attempts: Int): RecoveryDecision {
        val s = inputs.status
        return when {
            s == BuiltinShellStatus.Running -> RecoveryDecision.Healthy
            !inputs.paired || s == BuiltinShellStatus.NotPaired -> RecoveryDecision.Stop(StopReason.NotPaired)
            inputs.wirelessDebugging == false -> RecoveryDecision.Stop(StopReason.WirelessDebuggingOff)
            s is BuiltinShellStatus.Failed && needsUser(s.reason) -> RecoveryDecision.Stop(StopReason.NeedsUser)
            attempts >= MAX_ATTEMPTS -> RecoveryDecision.Stop(StopReason.OutOfAttempts)
            s == BuiltinShellStatus.Connecting -> RecoveryDecision.Wait(backoffMs(attempts).coerceAtLeast(BASE_DELAY_MS))
            else -> RecoveryDecision.Try(backoffMs(attempts))
        }
    }

    private fun needsUser(f: BuiltinShellFailure) = f.defaultFix != BuiltinShellFix.Retry

    /**
     * [decide] を押して、回復するか停止するまで試し続けます。 [attempt] は再接続です (実際には `BuiltinShellControl.retry()`、
     * 例外はスローされず、結果はステータスに書き込まれます)。 [inputs] はラウンドごとに再度読み取られます。
     */
    suspend fun recover(
        inputs: suspend () -> RecoveryInputs,
        attempt: suspend () -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): RecoveryOutcome {
        var attempts = 0
        while (true) {
            when (val d = decide(inputs(), attempts)) {
                RecoveryDecision.Healthy -> return RecoveryOutcome(recovered = true, attempts = attempts, stoppedBecause = null)
                is RecoveryDecision.Stop -> return RecoveryOutcome(recovered = false, attempts = attempts, stoppedBecause = d.reason)
                is RecoveryDecision.Wait -> { sleep(d.delayMs); attempts++ }
                is RecoveryDecision.Try -> { sleep(d.delayMs); attempt(); attempts++ }
            }
        }
    }
}

/** 起動時に「再起動後の自動回復」を使用するかどうか: それが有効になっているかどうか (デフォルトでオンになっており、追加されています) によって決まり、ユーザーがスイッチを切り替えたかどうかには依存しません。 最後に電源を入れたときからの */
object BootRestore {
    const val MIN_SDK = 31

    enum class Gate { Act, Skip, PermissionMissing }

    fun gate(sdkInt: Int, consent: RecoveryConsent, holdsPermission: Boolean): Gate = when {
        sdkInt < MIN_SDK || !consent.isApplied(RecoveryCapability.RestoreAfterReboot) -> Gate.Skip
        !holdsPermission -> Gate.PermissionMissing
        else -> Gate.Act
    }
}

/** の自動回復の結果が、設定ページに忠実に表示されます。 */
enum class RestoreOutcome {
    Restored,
    /** この項目はオンになっていますが、システム設定を変更する権限は利用できなくなりました。 */
    PermissionMissing,
    /** ワイヤレス デバッグ スイッチの書き込みがシステムによって拒否されたか、有効になりません: この電話機は起動時の自動オープンをサポートしていません。 */
    SettingUnavailable,
    /** スイッチはオンになっていますが、接続できません (たとえば、信頼できる Wi-Fi 上にありません)。 */
    NotRecovered,
}
