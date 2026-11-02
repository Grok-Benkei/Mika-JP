package com.wangzan101.mika.selfcheck

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.SelfCheckReport
import com.wangzan101.mika.core.selfcheck.SelfCheckRunner
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import com.wangzan101.mika.settings.SystemStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機: 互換性セルフテストを完全に実行します。設定で [セルフテストの開始] をクリックしたのと同じ [AndroidSelfCheck] です。
 * では、内蔵アシスタントが実行されており、Mika のアクセシビリティがオンになっている必要があります。レポートはlogcat (タグMikaSelfCheck)にあります。デフォルトでは、最後まで実行し、各項目に結論が必要なだけです。
 * に `-e selfcheck.strict true` が付属する場合は、いずれの項目も故障していないことが必要です。 他の実デバイス テストと同様に、
 */
@RunWith(AndroidJUnit4::class)
class SelfCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val strict = InstrumentationRegistry.getArguments().getString("selfcheck.strict") == "true"

    @Test
    fun wholeSelfCheckCompletes() = runBlocking<Unit> {
        // は最初に共有アシスタント接続を占有し、問題のない再バインドを促します (テストの開始時にシステムはアプリを強制的に停止します)。
        DeviceSupport.shell()
        DeviceSupport.service()
        val monitor = SystemStatusMonitor(context, PhoneServices.of(context).shellControl)
        val runner = SelfCheckRunner(AndroidSelfCheck(context) { monitor.refresh(); monitor.status.value })
        val s = withContext(Dispatchers.Default) { runner.run() }
        val report = SelfCheckReport.render(s, Diagnostics::sanitize)
        report.lines().forEach { Log.i(TAG, it) }

        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertTrue(report, s.results.all { it.status.final })
        assertTrue(report, s.elapsedMs < SelfCheckRunner.TOTAL_BUDGET_MS + 30_000)
        if (strict) assertTrue(report, s.results.none { it.status == CheckStatus.Fail })
    }

    private companion object {
        const val TAG = "MikaSelfCheck"
    }
}
