package com.wangzan101.mika.core.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryPolicyTest {
    private fun inputs(
        status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed),
        paired: Boolean = true,
        wireless: Boolean? = true,
    ) = RecoveryInputs(paired = paired, wirelessDebugging = wireless, status = status)

    @Test
    fun backoffDoublesUpToACap() {
        assertEquals(
            listOf(0L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(RecoveryPolicy::backoffMs),
        )
    }

    @Test
    fun runningNeedsNothing() {
        assertEquals(RecoveryDecision.Healthy, RecoveryPolicy.decide(inputs(BuiltinShellStatus.Running), 0))
    }

    @Test
    fun helperVanishedIsRetriedWithBackoff() {
        // USB/adbd を取り外して再起動すると、すべてのシェル ID プロセスが削除されます。前回報告された内容に関係なく、ユーザーがアクションを実行する必要がある障害でない限り、もう一度試してください。
        listOf(
            BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed),
            BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed),
            BuiltinShellStatus.Failed(BuiltinShellFailure.Unknown),
            BuiltinShellStatus.WirelessDebuggingOff,
        ).forEach { s ->
            assertEquals("$s", RecoveryDecision.Try(0), RecoveryPolicy.decide(inputs(s), 0))
            assertEquals("$s", RecoveryDecision.Try(4_000), RecoveryPolicy.decide(inputs(s), 2))
        }
    }

    @Test
    fun unknownWirelessStateStillTries() {
        // 一部の携帯電話では、ワイヤレス デバッグ スイッチを読み取ることができません。ネットワークを信頼するかどうかに関係なく、ネットワークを読み取ることはできません。 「試してみる」を押して処理してください。
        assertEquals(RecoveryDecision.Try(0), RecoveryPolicy.decide(inputs(wireless = null), 0))
    }

    @Test
    fun preconditionsThatStopTrying() {
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(paired = false), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(BuiltinShellStatus.NotPaired), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.WirelessDebuggingOff), RecoveryPolicy.decide(inputs(wireless = false), 0))
    }

    @Test
    fun failuresThatNeedTheUserStopImmediately() {
        listOf(
            BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailure.WrongCode,
            BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.NoWifi,
        ).forEach { f ->
            assertEquals("$f", RecoveryDecision.Stop(StopReason.NeedsUser), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Failed(f)), 1))
        }
    }

    @Test
    fun connectingWaitsInsteadOfPilingOn() {
        assertEquals(RecoveryDecision.Wait(2_000), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Connecting), 1))
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        assertEquals(RecoveryDecision.Stop(StopReason.OutOfAttempts), RecoveryPolicy.decide(inputs(), RecoveryPolicy.MAX_ATTEMPTS))
        assertEquals(RecoveryDecision.Stop(StopReason.OutOfAttempts), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Connecting), RecoveryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun loopRecoversAfterAFewFailures() = runTest {
        var status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
        var tries = 0
        val slept = mutableListOf<Long>()
        val outcome = RecoveryPolicy.recover(
            inputs = { inputs(status) },
            attempt = { tries++; if (tries == 3) status = BuiltinShellStatus.Running },
            sleep = { slept += it },
        )
        assertEquals(RecoveryOutcome(recovered = true, attempts = 3, stoppedBecause = null), outcome)
        assertEquals(listOf(0L, 2_000L, 4_000L), slept)
    }

    @Test
    fun loopStopsWhenUserMustAct() = runTest {
        var status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)
        val outcome = RecoveryPolicy.recover(
            inputs = { inputs(status) },
            attempt = { status = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked) },
            sleep = {},
        )
        assertEquals(RecoveryOutcome(recovered = false, attempts = 1, stoppedBecause = StopReason.NeedsUser), outcome)
    }

    @Test
    fun loopIsBounded() = runTest {
        var tries = 0
        val outcome = RecoveryPolicy.recover(inputs = { inputs() }, attempt = { tries++ }, sleep = {})
        assertEquals(RecoveryPolicy.MAX_ATTEMPTS, tries)
        assertEquals(StopReason.OutOfAttempts, outcome.stoppedBecause)
    }

    @Test
    fun alreadyRunningDoesNotTouchAnything() = runTest {
        var tries = 0
        val outcome = RecoveryPolicy.recover(inputs = { inputs(BuiltinShellStatus.Running) }, attempt = { tries++ }, sleep = {})
        assertEquals(0, tries)
        assertEquals(true, outcome.recovered)
        assertNull(outcome.stoppedBecause)
    }

    @Test
    fun bootRestoreGate() {
        // はデフォルトでオンになっており、接続が利用可能になったときに追加されます。スイッチは切り替えられておらず、同意されていても有効になります。
        val on = RecoveryConsent(applied = setOf(RecoveryCapability.RestoreAfterReboot))
        for (sdk in 31..33) assertEquals(BootRestore.Gate.Act, BootRestore.gate(sdkInt = sdk, consent = on, holdsPermission = true))
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 30, consent = on, holdsPermission = true))
        // はデフォルトで必須ですが、まだ有効になっていない (ペアリングされていない) か、ユーザーが自分でオフにしました。何もしないでください。
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 34, consent = RecoveryConsent(), holdsPermission = true))
        val declined = RecoveryConsent(declined = setOf(RecoveryCapability.RestoreAfterReboot))
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 34, consent = declined, holdsPermission = true))
        // は開かれましたが、認証は失われています (システムまたはユーザーによって取り消されました)。正直に報告し、ハードライトしないでください。
        assertEquals(BootRestore.Gate.PermissionMissing, BootRestore.gate(sdkInt = 34, consent = on, holdsPermission = false))
    }
}
