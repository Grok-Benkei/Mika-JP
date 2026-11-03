package com.wangzan101.mika.ui

import com.wangzan101.mika.core.shell.HelperRestart
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ブート ページと「詳細」での数行の携帯電話操作 (偽のデータのみを使用し、システム設定には触れません)。 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(inputs: SetupInputs, headsUpLowered: Boolean = false, diagnostics: Boolean = false, scanning: Boolean = false) = compose.setContent {
        PhoneSetupContent(
            view = PhoneSetupFlow.view(inputs),
            onStepAction = { calls += "step $it" },
            onFailureFix = { calls += "fix $it" },
            onAllowNotifications = { calls += "notify" },
            onConfirmStep = { calls += "confirm $it" },
            onSubmit = { c, p -> calls += "submit $c $p" },
            onClose = { calls += "close" },
            headsUpLowered = headsUpLowered,
            onOpenHeadsUp = { calls += "headsUp" },
            onAppInfo = { calls += "appInfo" },
            onSkipAccessibility = { calls += "skipA11y" },
            onRevisit = { calls += "revisit $it" },
            onOpenPairingChannel = { calls += "pairingChannel" },
            onDiagnostics = if (diagnostics) ({ calls += "diagnostics" }) else null,
            scanning = scanning,
            onConnectPort = { calls += "port $it" },
        )
    }

    /** 接続できません: 「後で話します」が表示されたままです。 「ワイヤレスデバッグ」ページに従ってポートを入力できます。エラーを入力すると、その場で入力を求められ接続されません。 */
    @Test
    fun failedConnectOffersLaterAndManualPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").performScrollTo().performTextInput("70000")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithText(PhoneSetupCopy.PORT_INVALID).assertExists()
        compose.onNodeWithTag("setup-connect-port").performTextClearance()
        compose.onNodeWithTag("setup-connect-port").performTextInput("41235")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("port 41235", "close"), calls)
    }

    /** この環境には開発者向けオプションとワイヤレス デバッグがありません。最初に説明し、手順をリストせず、「理解」して終了します。 */
    @Test
    fun unavailableEnvironmentExplainsUpFrontWithoutSteps() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired, wirelessMissing = true, developerSettingsMissing = true))
        compose.onNodeWithTag("setup-unavailable").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertDoesNotExist()
        compose.onNodeWithTag("setup-later").assertDoesNotExist()
        compose.onNodeWithTag("setup-got-it").performClick()
        assertEquals(listOf("close"), calls)
    }

    /** 接続: 「後で話す」こともできます。このマシンのポートを探すときは真実を伝えてください。 */
    @Test
    fun workingOffersLaterAndShowsPortSearch() {
        show(SetupInputs(true, true, BuiltinShellStatus.Connecting), scanning = true)
        compose.onNodeWithText(PhoneSetupCopy.SCANNING_PORTS).assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
        compose.onNodeWithTag("setup-later").assertIsDisplayed().performClick()
        assertEquals(listOf("close"), calls)
    }

    /** はペアリング手順で失敗し (コードが間違っています)、接続ポートが入力されず、「後で説明します」がまだ残っています。 */
    @Test
    fun pairingFailureHasLaterButNoConnectPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
    }

    /** ペアリングへの 1 ステップ、障害なしでスキップします。ペアリング コードは通知にのみ入力できます。 */
    private val pairingWithoutA11y = SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, accessibilitySkipped = true)

    /** 「診断情報のコピー」は常に一番下にあります。失敗したときにエラーが発生したステップにも 1 つあります。 */
    @Test
    fun diagnosticsButtonAtBottomAndInFailedStep() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics-failed").performScrollTo().performClick()
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed().performClick()
        assertEquals(listOf("diagnostics", "diagnostics"), calls)
    }

    @Test
    fun diagnosticsButtonInGuidePhase() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("setup-diagnostics-failed").assertDoesNotExist()
    }

    @Test
    fun firstStepOpensAboutPhone() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("step DeveloperOptions", "close"), calls)
    }

    @Test
    fun accessibilityStepOpensSettingsOffersAppInfoAndCanBeSkipped() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, restrictedSettings = true))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.Accessibility).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.ACCESSIBILITY_SKIP).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-app-info").performClick()
        compose.onNodeWithTag("setup-skip-a11y").performClick()
        assertEquals(listOf("step Accessibility", "appInfo", "skipA11y"), calls)
        // 障害物はありません。飛び越えて降りるだけで、追加の「オープン」は与えられません
        compose.onNodeWithTag("setup-confirm").assertDoesNotExist()
    }

    @Test
    fun everySwitchStepHasAManualNext() {
        // すべてがクローズ済みとして読み取られます。最初のステップは、「オープンしました、次のステップ」をクリックすることです。
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false))
        compose.onNodeWithText(PhoneSetupCopy.TURNED_ON_NEXT).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performClick()
        assertEquals(listOf("confirm DeveloperOptions"), calls)
    }

    @Test
    fun wirelessStepHasAManualNext() {
        show(SetupInputs(true, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        assertEquals(listOf("confirm WirelessDebugging"), calls)
    }

    @Test
    fun doneStepsCanBeTappedToRedo() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-step-UsbDebugging").performClick()
        // 後から実行していないものはクリックできません。
        compose.onNodeWithTag("setup-step-Connect").performClick()
        compose.onNodeWithText(PhoneSetupCopy.REVISIT_NOTE).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("revisit UsbDebugging"), calls)
    }

    @Test
    fun revisitedStepOffersNextAndBack() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, revisit = SetupStep.UsbDebugging))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.UsbDebugging).body).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("setup-revisit-back").performScrollTo().performClick()
        assertEquals(listOf("confirm UsbDebugging", "revisit null"), calls)
    }

    @Test
    fun connectionFailureSaysWhichStepToCheckAndGoesBack() {
        show(SetupInputs(true, null, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithText(PhoneSetupCopy.suspect(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-suspect-back").performScrollTo().performClick()
        assertEquals(listOf("revisit WirelessDebugging"), calls)
    }

    @Test
    fun pairingWithoutAccessibilityShowsTheThreeStepGuide() {
        show(pairingWithoutA11y)
        compose.onNodeWithTag("setup-pairing-guide").performScrollTo().assertIsDisplayed()
        PhoneSetupCopy.NOTIFY_GUIDE.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("setup-pairing-hint").assertDoesNotExist()
        // 通知は問題ありません。メイン ボタンは依然としてワイヤレス デバッグ用であり、通知を修復するボタンはありません。
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-action").performScrollTo().performClick()
        assertEquals(listOf("step PairingCode"), calls)
    }

    @Test
    fun pairingWithAccessibilityShowsTheAutoReadHint() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.AUTO_READ_HINT).assertIsDisplayed()
        compose.onNodeWithTag("setup-pairing-guide").assertDoesNotExist()
    }

    @Test
    fun pairingAsksToAllowNotificationsFirstWhenTheyAreOff() {
        show(pairingWithoutA11y.copy(notificationsAllowed = false))
        compose.onNodeWithText(PhoneSetupCopy.NEED_NOTIFICATIONS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("notify"), calls)
    }

    @Test
    fun pairingOpensTheChannelSettingsWhenPairingNotificationsAreOff() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Off))
        compose.onNodeWithText(PhoneSetupCopy.PAIRING_CHANNEL_OFF).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.OPEN_PAIRING_CHANNEL).performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingWarnsWhenPairingNotificationsDoNotPopUp() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Lowered))
        compose.onNodeWithTag("setup-pairing-lowered").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-pairing-lowered-open").performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingStepWarnsWhenHeadsUpIsLowered() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching, notificationsAllowed = true), headsUpLowered = true)
        compose.onNodeWithTag("setup-heads-up").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.HEADS_UP_LOWERED).assertIsDisplayed()
        compose.onNodeWithTag("setup-heads-up-open").performClick()
        assertEquals(listOf("headsUp"), calls)
    }

    @Test
    fun pairingStepTakesAManualCode() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        compose.onNodeWithTag("setup-manual").performClick()
        compose.onNodeWithTag("setup-code").performTextInput("123 456")
        compose.onNodeWithTag("setup-port").performTextInput("37001")
        // 実機でキーボードがポップアップすると、表示されている領域の下にあるボタンが押されます。まずスクロールしてクリックします。
        compose.onNodeWithTag("setup-submit").performScrollTo().performClick()
        assertEquals(listOf("submit 123456 37001"), calls)
    }

    @Test
    fun failureShowsReasonAndOneTapFix() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        compose.onNodeWithText(PhoneSetupCopy.failure(BuiltinShellFailure.HelperStartFailed).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-fix").performClick()
        assertEquals(listOf("fix ${BuiltinShellFix.Retry}"), calls)
    }

    @Test
    fun successKeepsChecklistAndWaitsForTheDoneButton() {
        show(SetupInputs(true, true, BuiltinShellStatus.Running))
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_LINE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.successNote(HelperRestart.None)).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_DONE).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("setup-done").performClick()
        assertEquals(listOf("close"), calls)
    }

    /** 「開始するにはコンピューターに切り替えてください」は通常は表示されません。ワイヤレス デバッグ ステップには「ワイヤレス デバッグが見つかりませんか?」という 1 行しかなく、それをクリックした後でのみこのステップにコマンドとコピーが表示されます。 */
    @Test
    fun usbCardAppearsOnlyWhenAskedOnTheWirelessStep() {
        val wirelessStep = SetupInputs(true, false, BuiltinShellStatus.NotPaired)
        compose.setContent {
            var asked by remember { mutableStateOf(false) }
            PhoneSetupContent(
                view = PhoneSetupFlow.view(wirelessStep.copy(usbAsked = asked)),
                onStepAction = {}, onFailureFix = {}, onAllowNotifications = {}, onConfirmStep = {}, onSubmit = { _, _ -> }, onClose = {},
                usbCommand = "adb shell sh /sdcard/usb-start.sh",
                onAskUsb = { asked = true },
                onCopyUsbCommand = { calls += "copy" },
            )
        }
        compose.onNodeWithTag("setup-usb").assertDoesNotExist()
        compose.onNodeWithTag("setup-usb-ask").performScrollTo().performClick()
        compose.onNodeWithTag("setup-usb-ask").assertDoesNotExist()
        compose.onNodeWithText(PhoneSetupCopy.USB_TITLE).assertExists()
        compose.onNodeWithTag("setup-usb-copy").performScrollTo().performClick()
        assertEquals(listOf("copy"), calls)
    }

    /** は通常どおりペアリング手順に進みます。「ワイヤレス デバッグが見つかりません?」というメッセージは表示されません。そしてカードはありません。 */
    @Test
    fun normalPairingStepHasNoUsbOption() {
        compose.setContent {
            PhoneSetupContent(
                view = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.NotPaired)),
                onStepAction = {}, onFailureFix = {}, onAllowNotifications = {}, onConfirmStep = {}, onSubmit = { _, _ -> }, onClose = {},
                usbCommand = "adb shell sh /sdcard/usb-start.sh",
            )
        }
        compose.onNodeWithTag("setup-usb").assertDoesNotExist()
        compose.onNodeWithTag("setup-usb-ask").assertDoesNotExist()
    }
}
