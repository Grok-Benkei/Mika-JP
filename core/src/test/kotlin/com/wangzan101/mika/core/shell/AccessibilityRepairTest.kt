package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRepairTest {
    private val stuck = A11yObservation(enabled = true, connected = false)
    private val on = A11yObservation(enabled = true, connected = true)
    private val off = A11yObservation(enabled = false, connected = false)

    /** 偽環境: [comesBack] が true の場合、再起動後に [connectAfterMs] サービスが接続されます。 */
    private inner class Rig(
        initial: A11yObservation = stuck,
        helperStatus: BuiltinShellStatus = BuiltinShellStatus.Running,
        var consent: RecoveryConsent = RecoveryConsent(),
        var comesBack: Boolean = true,
        val connectAfterMs: Long = 500,
        val result: PermissionResult = PermissionResult.Done,
        var direct: Boolean = false,
        unlocked: Boolean = true,
    ) {
        val userUnlocked = MutableStateFlow(unlocked)
        val service = MutableStateFlow(initial)
        val helper = MutableStateFlow(helperStatus)
        val restarts = mutableListOf<Long>()
        lateinit var scope: TestScope

        val repair = AccessibilityRepair(
            restart = { gap ->
                restarts += scope.currentTime
                if (comesBack && result == PermissionResult.Done) {
                    scope.launch { kotlinx.coroutines.delay(gap + connectAfterMs); service.value = on }
                }
                result
            },
            consent = { consent },
            direct = { direct },
            now = { scope.currentTime },
        )

        fun start(s: TestScope) {
            scope = s
            s.backgroundScope.launch { repair.watch(service, helper, userUnlocked) }
            s.runCurrent()
        }
    }

    @Test
    fun waitsForTheFirstUnlockThenRepairs() = runTest {
        // は起動後にロックが解除されていません。サービスを元のサービスにバインドできず、システム内のアクセシビリティ リストを移動できません。
        val rig = Rig(unlocked = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS * 2)
        assertEquals(emptyList<Long>(), rig.restarts)
        assertNull(rig.repair.last.value)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        // はロックが解除されていますが、まだ接続されていません。通常どおりに修復する前に、しばらく待ってください。
        val unlockedAt = currentTime
        rig.userUnlocked.value = true
        advanceTimeBy(10_000)
        assertEquals(listOf(unlockedAt + AccessibilityRepair.GRACE_MS), rig.restarts)
        assertEquals(on, rig.service.value)
        assertEquals(true, rig.repair.last.value?.restarted)
    }

    @Test
    fun repairsStuckServiceWhileHelperRuns() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS - 1)
        assertEquals(emptyList<Long>(), rig.restarts)
        advanceTimeBy(10_000)
        assertEquals(listOf(AccessibilityRepair.GRACE_MS), rig.restarts)
        val last = rig.repair.last.value!!
        assertTrue(last.restarted)
        assertEquals(1, last.attempts)
    }

    @Test
    fun noRepairWhenItConnectsDuringGrace() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(1_000)
        rig.service.value = on
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        assertNull(rig.repair.last.value)
    }

    @Test
    fun turnsItBackOnWhenRemovedFromTheSystemList() = runTest {
        // 強制的に停止すると、システムはアクセシビリティ リストからミカを削除します。同様に自動的に開きます。
        val rig = Rig(initial = off).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun noRepairWhenDeclinedInMika() = runTest {
        val rig = Rig(consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
    }

    @Test
    fun repairsWithoutHelperWhenAppCanWriteSettingsItself() = runTest {
        // ワイヤレス デバッグがオフになっており、アシスタントは利用できませんが、アプリ自体にはシステム設定を変更する権限があります。それでも修復されます。
        val rig = Rig(initial = off, helperStatus = BuiltinShellStatus.NotPaired, direct = true).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun waitsForHelperThenRepairs() = runTest {
        val rig = Rig(helperStatus = BuiltinShellStatus.Connecting).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun givesUpAfterLimitWithBackoff() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        // 接続がタイムアウトになるのを待ってから初めてバックオフした後の 2 回目
        val gap = rig.restarts[1] - rig.restarts[0]
        assertTrue("gap $gap", gap >= AccessibilityRepair.CONNECT_TIMEOUT_MS + AccessibilityRepair.BACKOFF_MS)
        assertFalse(rig.repair.last.value!!.restarted)
        // が使い果たされると、ステータスが再び変更され (アシスタントはオフラインになり、復帰します)、再試行は行われません。
        rig.helper.value = BuiltinShellStatus.Connecting
        runCurrent()
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun newIncidentAfterItCameBackCountsAgain() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(2, rig.restarts.size)
        // ユーザーが手動で電源をオフにしてからオンにしてから接続しました。それからまた行き詰まってしまいました。もう一度数えてください。
        rig.service.value = on
        runCurrent()
        rig.comesBack = true
        rig.service.value = stuck
        advanceTimeBy(60_000)
        assertEquals(3, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun failedCommandCountsAsAttempt() = runTest {
        val rig = Rig(result = PermissionResult.Failed("boom")).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        assertFalse(rig.repair.last.value!!.restarted)
    }

    @Test
    fun stopsRetryingWhenDeclinedInMikaBetweenAttempts() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
        rig.consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        advanceTimeBy(120_000)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun slowConnectWithinTimeoutIsSuccess() = runTest {
        val rig = Rig(connectAfterMs = AccessibilityRepair.CONNECT_TIMEOUT_MS - 2_000).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun phaseFollowsTheRepair() = runTest {
        val rig = Rig(connectAfterMs = 2_000).also { it.start(this) }
        assertEquals(RepairPhase.Pending, rig.repair.phase.value)
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(RepairPhase.Repairing, rig.repair.phase.value)
        advanceTimeBy(10_000)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        // は取り外した場合でも修理が必要です。それが常にアイドル状態になるのは、Mika ではオフになっていて、私ではオンになっている場合に限られます。
        assertEquals(RepairPhase.Pending, Rig(initial = off).also { it.start(this) }.repair.phase.value)
        val declined = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        assertEquals(RepairPhase.Idle, Rig(initial = off, consent = declined).also { it.start(this) }.repair.phase.value)
    }

    @Test
    fun phaseIdleAfterGivingUp() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun awaitKeepsTheShortWaitWhenNothingIsBeingRepaired() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Idle)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(5_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
        // 最初から接続されています: すぐに取得します
        assertEquals("svc", AccessibilityRepair.awaitService(MutableStateFlow<String?>("svc"), phase, baseMs = 5_000))
    }

    @Test
    fun awaitWaitsThroughARepairThatSucceeds() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(12_000)
        assertFalse(wait.isCompleted)
        service.value = "svc"
        phase.value = RepairPhase.Idle
        runCurrent()
        assertEquals("svc", wait.await())
    }

    @Test
    fun awaitStopsWhenTheRepairGivesUp() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Pending)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(9_000)
        assertFalse(wait.isCompleted)
        phase.value = RepairPhase.Idle
        runCurrent()
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitIsCapped() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000, capMs = 25_000) }
        advanceTimeBy(25_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitWithTheRealRepairEndToEnd() = runTest {
        val rig = Rig(connectAfterMs = 3_000).also { it.start(this) }
        // サービス インスタンス: 接続は null
        val instance = MutableStateFlow<String?>(null)
        backgroundScope.launch { rig.service.collect { instance.value = if (it.connected) "svc" else null } }
        val wait = async { AccessibilityRepair.awaitService(instance, rig.repair.phase, baseMs = 5_000) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals("svc", wait.await())
    }
}
