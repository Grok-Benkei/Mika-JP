package com.wangzan101.mika.core.shell

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebuggingAutoOnTest {
    /** 偽のシステム スイッチ: [revert] が true の場合、システムによってオンになり、その後オフに戻ります (Wi-Fi は許可されません)。 */
    private inner class FakeSwitch {
        var on: Boolean? = false
        var revert = false
        var refuse = false
        var throws = false
        var writes = 0
        private var readsSinceWrite = 0

        fun read(): Boolean? {
            // 設定変更を受信した後、元のバージョンがダイヤルバックされました。書き込み後、最初に読み取られて開かれ、次に読み取られて閉じられました。
            if (revert && on == true && ++readsSinceWrite > 1) on = false
            return on
        }

        fun write(): Boolean {
            writes++
            if (throws) throw SecurityException("denied")
            if (refuse) return false
            on = true
            readsSinceWrite = 0
            return true
        }
    }

    private val switch = FakeSwitch()
    private var wants = true
    private var canWrite = true
    private var clock = 0L

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { canWrite }, read = switch::read, write = switch::write,
        now = { clock + testScheduler.currentTime },
    )

    @Test
    fun decideNeedsConsentPermissionAndWifi() {
        fun d(wants: Boolean = true, canWrite: Boolean = true, wifi: Boolean = true, blocked: AutoOnBlock? = null, writes: Int = 0, since: Long = Long.MAX_VALUE) =
            WirelessDebuggingAutoOn.decide(wants, canWrite, wifi, blocked, writes, since)

        assertEquals(AutoOnGate.Act, d())
        // ユーザーはこの項目をオフにしました: 他に何があっても書き込まないでください
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Declined), d(wants = false, blocked = AutoOnBlock.NotAllowedHere, wifi = false))
        assertEquals(AutoOnGate.Skip(AutoOnSkip.NoPermission), d(canWrite = false))
        assertEquals(AutoOnGate.Skip(AutoOnSkip.NoWifi), d(wifi = false))
        AutoOnBlock.entries.forEach { assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), d(blocked = it)) }
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), d(writes = WirelessDebuggingAutoOn.MAX_WRITES))
    }

    @Test
    fun writesAreSpacedOutWithBackoff() {
        assertEquals(
            listOf(0L, 10_000L, 20_000L, 40_000L),
            (0..3).map(WirelessDebuggingAutoOn::gapAfter),
        )
        assertEquals(WirelessDebuggingAutoOn.MAX_GAP_MS, WirelessDebuggingAutoOn.gapAfter(30))
        assertEquals(AutoOnGate.Wait(6_000), WirelessDebuggingAutoOn.decide(true, true, true, null, 1, 4_000))
        assertEquals(AutoOnGate.Act, WirelessDebuggingAutoOn.decide(true, true, true, null, 1, 10_000))
    }

    @Test
    fun turnsOnAndStaysOnOnAnAllowedNetwork() = runTest {
        val a = autoOn()
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
        assertTrue(a.turnOn())
        assertEquals(true, switch.on)
        assertNull(a.blocked.value)
        assertFalse(a.writing)
        // 次に、もう一度書き込みます: オフに戻るなどを押します。
        assertTrue(a.gate(wifi = true) is AutoOnGate.Wait)
    }

    @Test
    fun revertedBySystemStopsUntilReset() = runTest {
        switch.revert = true
        val a = autoOn()
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.NotAllowedHere, a.blocked.value)
        assertEquals(false, switch.on)
        // 書き込みを停止してから停止するまでどのくらい時間がかかりますか (書き込みのたびにシステムがボックスをポップアップ表示します)
        clock += 3_600_000
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), a.gate(wifi = true))
        assertEquals(1, switch.writes)

        // が Wi-Fi を変更しました。ユーザーが手動で開いたか、[再試行: 最初から計算] をクリックしました。
        a.reset()
        assertNull(a.blocked.value)
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
    }

    @Test
    fun refusedWriteStops() = runTest {
        switch.refuse = true
        val a = autoOn()
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.Refused, a.blocked.value)

        a.reset()
        switch.refuse = false
        switch.throws = true
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.Refused, a.blocked.value)
        assertFalse(a.writing)
    }

    @Test
    fun givesUpAfterTooManyWritesWithoutTheHelper() = runTest {
        val a = autoOn()
        repeat(WirelessDebuggingAutoOn.MAX_WRITES) {
            clock += 3_600_000
            assertEquals(AutoOnGate.Act, a.gate(wifi = true))
            assertTrue(a.turnOn())
            // システム (またはその他の理由) で再び電源がオフになり、アシスタントが起動しませんでした。
            switch.on = false
        }
        clock += 3_600_000
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), a.gate(wifi = true))
        assertEquals(AutoOnBlock.GaveUp, a.blocked.value)
        // アシスタントが起動中 (エンジン リセット): 再カウント
        a.reset()
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
    }

    @Test
    fun consentOffNeverWrites() = runTest {
        wants = false
        val a = autoOn()
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Declined), a.gate(wifi = true))
        assertEquals(0, switch.writes)
    }
}
