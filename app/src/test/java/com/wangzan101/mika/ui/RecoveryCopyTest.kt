package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun pendingNoteOnlyForDefaultOnesNotYetApplied() {
        val a11y = RecoveryCapability.Accessibility
        val fresh = RecoveryConsent()
        // は携帯電話操作用に設定されていません。スイッチ ディスプレイがオンになっており、最初に設定を完了する必要があると次のように表示されます。
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = false, autoFailed = emptySet()))
        // は構成されており、補足中です。詳細はありません。補足できませんでした: 本当のことを言います
        assertNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = emptySet()))
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = setOf(a11y)))
        // が有効になっているか、ユーザーがオフにしています。何も言いません。
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(applied = setOf(a11y)), paired = false, autoFailed = setOf(a11y)))
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(declined = setOf(a11y)), paired = false, autoFailed = emptySet()))
    }

    /** アシスタントは、USB コマンドを使用してのみ起動されています。ワイヤレス デバッグを通じてアシスタントを再起動するための 2 つの項目は明らかに利用できません。他の項目は同じままです。 */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyNeedPairingWhenStartedOverUsb() {
        val usb = HelperRestart.UsbCommand
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb)
            assertNotNull(c.name, note)
            // ユーザーはそれをオフにしましたが、言うことはあまりありません。ペアリングした人はそのまま残ります。
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = HelperRestart.Wireless))
        }
        assertNull(RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = true, autoFailed = emptySet(), restart = usb))
    }

    /** root 起動では無線復帰用の2項目が不要と説明し、再起動後は su で復帰することを示す。不要な設定へ誘導せず、適用済みなら説明を省く。 */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyAreNotNeededWithRoot() {
        val root = HelperRestart.Root
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(), paired = false, autoFailed = emptySet(), restart = root)
            assertNotNull(c.name, note)
            assertNotEquals(c.name, RecoveryCopy.NEED_SETUP, note)
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = root))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = false, autoFailed = emptySet(), restart = root))
        }
        assertEquals(
            RecoveryCopy.NEED_SETUP,
            RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = false, autoFailed = emptySet(), restart = root),
        )
    }

    @Test
    fun autoOnNoteOnlyWhenStopped() {
        assertNull(RecoveryCopy.autoOnBlocked(null))
        assertNotNull(RecoveryCopy.autoOnBlocked(AutoOnBlock.NotAllowedHere))
        assertEquals(AutoOnBlock.entries.size, AutoOnBlock.entries.mapNotNull(RecoveryCopy::autoOnBlocked).toSet().size)
    }
}
