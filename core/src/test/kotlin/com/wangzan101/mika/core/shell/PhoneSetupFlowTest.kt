package com.wangzan101.mika.core.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupFlowTest {
    private val fresh = SetupInputs(developerOptions = false, wirelessDebugging = false, status = BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false)
    private val switchesOn = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)

    private fun SetupView.markList() = SetupStep.entries.map { marks.getValue(it) }

    @Test
    fun walksTheStepsInOrderAsSettingsChange() {
        val v1 = PhoneSetupFlow.view(fresh)
        assertEquals(SetupPhase.Guide, v1.phase)
        assertEquals(SetupStep.DeveloperOptions, v1.current)
        assertEquals(listOf(StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v1.markList())

        val v2 = PhoneSetupFlow.view(fresh.copy(developerOptions = true))
        assertEquals(SetupStep.UsbDebugging, v2.current)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2.markList())

        val v2a = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true))
        assertEquals(SetupStep.Accessibility, v2a.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2a.markList())

        val v2b = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true))
        assertEquals(SetupStep.WirelessDebugging, v2b.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo), v2b.markList())

        val v3 = PhoneSetupFlow.view(switchesOn)
        assertEquals(SetupStep.PairingCode, v3.current)
        assertTrue(v3.autoRead)
        assertFalse(v3.portFound)
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(discovery = PairingDiscovery.Found(37000))).portFound)

        val v4 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v4.phase)
        assertNull(v4.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current), v4.markList())

        val v5 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v5.phase)
        assertTrue(v5.markList().all { it == StepMark.Done })
    }

    @Test
    fun everyStepCanBePassedByHandWhenDetectionReadsOff() {
        // この携帯電話のすべてが「オフ」と表示されます。ユーザーは「オンにしました、次のステップ」をクリックして段階的にクリックしても、ペアリングに進みます。
        var i = fresh
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.DeveloperOptions)
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.UsbDebugging)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(i).current)
        i = i.copy(accessibilitySkipped = true)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.WirelessDebugging)
        val pairing = PhoneSetupFlow.view(i)
        assertEquals(SetupStep.PairingCode, pairing.current)
        assertEquals(SetupPhase.Guide, pairing.phase)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Skipped, StepMark.Done, StepMark.Current, StepMark.Todo), pairing.markList())
        // 接続が成功した場合は、前に読んだ内容に関係なく、
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Running)).phase)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Connecting)).phase)
    }

    @Test
    fun confirmedStepStaysPassedAndDetectionStillTicksTheRest() {
        // は開発者向けオプションのみを確認しました。USB デバッグは、オンにすると自動的にチェックされ、再度クリックする必要はありません。
        val v = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions)))
        assertEquals(SetupStep.PairingCode, v.current)
        assertEquals(StepMark.Done, v.marks[SetupStep.DeveloperOptions])
        assertEquals(StepMark.Done, v.marks[SetupStep.UsbDebugging])
        // 未確認ステップを読み込んでOFFにするので通常通りに戻ります。
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, confirmed = setOf(SetupStep.DeveloperOptions))).current)
        assertEquals(true, PhoneSetupFlow.detected(SetupStep.UsbDebugging, switchesOn))
        assertNull(PhoneSetupFlow.detected(SetupStep.WirelessDebugging, switchesOn.copy(wirelessDebugging = null)))
        assertNull(PhoneSetupFlow.detected(SetupStep.PairingCode, switchesOn))
    }

    @Test
    fun confirmedWirelessButHelperReadsItOffPointsBackInsteadOfSpinning() {
        val i = SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff, confirmed = setOf(SetupStep.WirelessDebugging))
        val v = PhoneSetupFlow.view(i)
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(StepMark.Failed, v.marks[SetupStep.Connect])
        assertNull(v.failure)
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), v.suspect)
        // はオープンしていることを読み取りますが、ステータスが追いついていません。まだ接続中です。
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(wirelessDebugging = true)).phase)
        // 読み取れない携帯電話も正直に「読み取れません」と言う
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(i.copy(wirelessDebugging = null)).suspect)
    }

    @Test
    fun connectionFailurePointsAtTheMostLikelyEarlierStep() {
        val connect = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)
        // 開発者向けオプションが読み取られてオフになります (ユーザーは確認後にのみここに来ます)
        val dev = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions), status = connect))
        assertEquals(SetupPhase.Failed, dev.phase)
        assertEquals(SetupSuspect(SetupStep.DeveloperOptions, unreadable = false), dev.suspect)
        // ワイヤレス デバッグの読み取りが完了しました
        val off = PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = false, confirmed = setOf(SetupStep.WirelessDebugging), status = connect))
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), off.suspect)
        // ワイヤレス デバッグで読み取れません
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = connect)).suspect)
        // ペアリング ウィンドウが閉じています。スイッチが原因である可能性があります。
        val closed = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = closed)).suspect?.step)
        // がオープンとして読み取られるか、ペアリング コードが正しくないため、アシスタントは起動できません。ランダムにポイントしないでください。
        assertNull(PhoneSetupFlow.view(switchesOn.copy(status = connect)).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed))).suspect)
    }

    @Test
    fun anyEarlierStepCanBeRevisited() {
        val atPairing = switchesOn
        val back = PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.UsbDebugging))
        assertEquals(SetupPhase.Guide, back.phase)
        assertEquals(SetupStep.UsbDebugging, back.current)
        assertTrue(back.revisiting)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Done, StepMark.Done, StepMark.Todo, StepMark.Todo), back.markList())
        assertFalse(PhoneSetupFlow.view(atPairing).revisiting)
        // 失敗しても元に戻せます。失敗したステップはまだマークされています
        val failed = PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.WirelessDebugging))
        assertEquals(SetupStep.WirelessDebugging, failed.current)
        assertNull(failed.failure)
        assertEquals(StepMark.Failed, failed.marks[SetupStep.Connect])
        // は、戻って再度ペアリングすることもできます。
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.PairingCode)).current)
        // 自動接続のステップをクリックできません。接続が進行中で成功したら、アシスタントを参照してください。
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.Connect)).current)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Connecting, revisit = SetupStep.DeveloperOptions)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Running, revisit = SetupStep.DeveloperOptions)).phase)
        // 前のステップを読むとオフになります。最初にそのステップに戻ってください。
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(atPairing.copy(developerOptions = false, revisit = SetupStep.WirelessDebugging)).current)
    }

    @Test
    fun accessibilityCanBeSkippedAndPairingFallsBackToTheNotification() {
        val off = switchesOn.copy(accessibility = false)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(off).current)
        val skipped = PhoneSetupFlow.view(off.copy(accessibilitySkipped = true))
        assertEquals(SetupStep.PairingCode, skipped.current)
        assertFalse(skipped.autoRead)
        assertEquals(StepMark.Skipped, skipped.marks[SetupStep.Accessibility])
        // はスキップされた後、システムで開かれました。まだ自動的に読み取られていました。
        val laterOn = PhoneSetupFlow.view(switchesOn.copy(accessibilitySkipped = true))
        assertTrue(laterOn.autoRead)
        assertEquals(StepMark.Done, laterOn.marks[SetupStep.Accessibility])
        // コードが間違っており、補充時に問題が依然として発生します
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).autoRead)
    }

    @Test
    fun restrictedSettingsHintOnlyAtTheAccessibilityStep() {
        val off = switchesOn.copy(accessibility = false, restrictedSettings = true)
        assertTrue(PhoneSetupFlow.view(off).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(restrictedSettings = false)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(accessibility = true)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(developerOptions = false)).showAppInfo)
    }

    @Test
    fun usbDebuggingOffBlocksSetupButNotARunningHelper() {
        // USB デバッグがオフです: ペアリングしてワイヤレス デバッグをオンにした後でも、このステップで停止します
        val v = PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting))
        assertEquals(SetupStep.UsbDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        // は、すでに実行されている場合でも、すでにセットアップされています (設定ページに他のプロンプトがあります)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun turningASwitchOffGoesBackToThatStep() {
        // はペアリングされており、ワイヤレス デバッグはオフになっています。そのステップに戻って、再ペアリングする必要はありません。
        val v = PhoneSetupFlow.view(SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        // 開発者向けオプションがオフになっています: 以前に失敗したことがある場合でも、最初の手順に戻ります。
        val f = PhoneSetupFlow.view(SetupInputs(false, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        assertEquals(SetupStep.DeveloperOptions, f.current)
        assertEquals(SetupPhase.Guide, f.phase)
    }

    @Test
    fun pairedAndSwitchOnMeansConnectingEvenIfStatusLags() {
        val v = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupPhase.Working, v.phase)
    }

    @Test
    fun unreadableWirelessSwitchDoesNotBlock() {
        // スイッチを読み取れない携帯電話: ワイヤレス デバッグ手順でスタックせず、アシスタントのステータスに従ってください。
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.NotPaired)).current)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.WirelessDebuggingOff)).current)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun failuresLandOnTheStepTheUserMustRedo() {
        val wrong = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        assertEquals(SetupPhase.Failed, wrong.phase)
        assertEquals(SetupStep.PairingCode, wrong.current)
        assertEquals(StepMark.Failed, wrong.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.RePair, wrong.failure?.fix)

        val revoked = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)))
        assertEquals(SetupStep.PairingCode, revoked.current)

        val helper = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        assertEquals(SetupStep.Connect, helper.current)
        assertEquals(StepMark.Done, helper.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.Retry, helper.failure?.fix)

        assertEquals(BuiltinShellFix.OpenWifi, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi).fix)
    }

    @Test
    fun repairWhileRunningStaysOnPairingStepAndFinishesAfterNewPairing() {
        val running = SetupInputs(true, true, BuiltinShellStatus.Running, repair = true, discovery = PairingDiscovery.Found(4000))
        val waiting = PhoneSetupFlow.view(running)
        assertEquals(SetupPhase.Guide, waiting.phase)
        assertEquals(SetupStep.PairingCode, waiting.current)
        assertTrue(waiting.portFound)
        assertTrue(waiting.autoRead)
        assertEquals(BuiltinShellStatus.NotPaired, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = true))
        assertEquals(BuiltinShellStatus.Connecting, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Connecting, repair = true))
        // 新しいペアリングが開始された後、再ペアリングは終了し、アシスタントは引き続き正常に実行されます。
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(running.copy(status = BuiltinShellStatus.Connecting)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(running.copy(repair = false)).phase)
        assertEquals(BuiltinShellStatus.Running, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = false))
    }

    @Test
    fun asksForNotificationsOnlyAtThePairingStep() {
        val off = fresh.copy(notificationsAllowed = false)
        assertFalse(PhoneSetupFlow.view(off).needsNotifications)
        assertTrue(PhoneSetupFlow.view(off.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)).needsNotifications)
    }

    @Test
    fun pairingStepSaysWhatIsWrongWithTheNotification() {
        val pairing = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = false, accessibilitySkipped = true, wirelessDebugging = true)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(pairing).current)
        assertNull(PhoneSetupFlow.view(pairing).notifyIssue)
        // 最も重要なことは、通知が許可されていないことです。次に、「ペアリング」通知がオフになります。ポップアップしないように調整されており、通知するだけであり、送信できないわけではありません。
        val off = PhoneSetupFlow.view(pairing.copy(notificationsAllowed = false, pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.NotificationsOff, off.notifyIssue)
        val channelOff = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, channelOff.notifyIssue)
        assertTrue(channelOff.needsNotifications)
        val lowered = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Lowered))
        assertEquals(PairingNotifyIssue.NotHeadsUp, lowered.notifyIssue)
        assertFalse(lowered.needsNotifications)
        // 他のステップの通知を無視します
        assertNull(PhoneSetupFlow.view(fresh.copy(pairingChannel = PairingChannelHealth.Off)).notifyIssue)
        // ペアリングが失敗し、ペアリング手順が停止すると通知が表示されます。
        val failed = PhoneSetupFlow.view(pairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, failed.notifyIssue)
    }

    @Test
    fun pairingCodeNormalization() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize(" 123 456 "))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize("１２３４５６"))
        // 入力方法における全角スペース、全角横線、長短横線
        assertEquals("123456", PairingCode.normalize("１２３\u3000４５６"))
        assertEquals("123456", PairingCode.normalize("123－456"))
        assertEquals("123456", PairingCode.normalize("12–34—56"))
        assertNull(PairingCode.normalize(""))
        assertNull(PairingCode.normalize("123 45"))
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12a456"))
        assertEquals(37123, PairingCode.port(" 37123 "))
        assertNull(PairingCode.port("0"))
        assertNull(PairingCode.port("70000"))
    }

    @Test
    fun fakeControlDrivesStatus() = runTest {
        val fake = FakeBuiltinShellControl(pairingResult = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))
        fake.startDiscovery()
        assertEquals(PairingDiscovery.Searching, fake.pairingDiscovery.value)
        fake.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), fake.status.value)
        fake.retry()
        assertEquals(BuiltinShellStatus.Running, fake.status.value)
        fake.unpair()
        assertEquals(BuiltinShellStatus.NotPaired, fake.status.value)
        assertEquals(listOf("discover", "pair 123456 40000", "retry", "unpair"), fake.calls)
    }

    // ---------- 「コンピューターに切り替えて開始」は必要な場合にのみ表示 ----------

    private val atWireless = switchesOn.copy(wirelessDebugging = false)

    @Test
    fun usbFallbackIsHiddenInTheNormalFlow() {
        // は通常、最後まで進みます: すべてのステップ、接続、成功、応答なし
        listOf(
            fresh, fresh.copy(developerOptions = true), atWireless, switchesOn,
            switchesOn.copy(discovery = PairingDiscovery.Found(37000)),
            switchesOn.copy(status = BuiltinShellStatus.Connecting),
            switchesOn.copy(status = BuiltinShellStatus.Running),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi)),
        ).forEach { assertFalse(it.toString(), PhoneSetupFlow.view(it).usbFallback) }
        // は少し止まったが、制限時間まで与えなかった。
        assertFalse(PhoneSetupFlow.view(atWireless.copy(stalledMs = PhoneSetupFlow.STALL_MS - 1)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheSystemHasNoWirelessDebugging() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(wirelessMissing = true)).usbFallback)
        // 最初の 2 つの手順 (開発者向けオプション、USB デバッグ) もコンピューターの起動時に必要であり、完了するまで実行されません。 成功した場合、
        assertFalse(PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).usbFallback)
        // は与えられません
        assertFalse(PhoneSetupFlow.view(switchesOn.copy(wirelessMissing = true, status = BuiltinShellStatus.Running)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheUserCannotFindIt() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(usbAsked = true)).usbFallback)
        // は一度表示され、そのまま保持されました。接続を開始した後もまだ存在しています。
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(usbAsked = true, status = BuiltinShellStatus.Connecting)).usbFallback)
        // クリックして最初のステップに戻ってやり直しても指定されません
        assertFalse(PhoneSetupFlow.view(atWireless.copy(usbAsked = true, revisit = SetupStep.DeveloperOptions)).usbFallback)
    }

    @Test
    fun usbFallbackAfterStallingWhereTheSystemShouldAnswer() {
        val stalled = PhoneSetupFlow.STALL_MS
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(atWireless)))
        assertTrue(PhoneSetupFlow.view(atWireless.copy(stalledMs = stalled)).usbFallback)
        // ペアリングの最初のステップでペアリング ウィンドウが見つかりません (システムにはペアリング サービスがありません)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(switchesOn)))
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(stalledMs = stalled)).usbFallback)
        // は、ペアリング ウィンドウを見つけた後、ユーザーがコードを入力するのを待っています。スタックしているとはみなされません。
        val found = switchesOn.copy(discovery = PairingDiscovery.Found(37000))
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(found)))
        assertFalse(PhoneSetupFlow.view(found.copy(stalledMs = stalled)).usbFallback)
        // は自動的に接続されています
        val connecting = switchesOn.copy(status = BuiltinShellStatus.Connecting)
        assertEquals(SetupStep.Connect, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(connecting)))
        assertTrue(PhoneSetupFlow.view(connecting.copy(stalledMs = stalled)).usbFallback)
        // ユーザーは最初のいくつかのステップを自分で見つける必要がありますが、どれだけ時間がかかっても問題ありません。
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(fresh)))
        assertFalse(PhoneSetupFlow.view(fresh.copy(stalledMs = stalled * 10)).usbFallback)
    }

    @Test
    fun usbFallbackAfterFailuresThatMeanWirelessDoesNotWork() {
        listOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped).forEach { f ->
            val v = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(f)))
            assertEquals(SetupPhase.Failed, v.phase)
            assertTrue(f.name, v.usbFallback)
        }
        // ユーザーはワイヤレス デバッグがオンになっていると言いましたが、アシスタントはそれがオフになっていると読み上げ、接続しませんでした。
        val claimed = atWireless.copy(confirmed = setOf(SetupStep.WirelessDebugging), wirelessDebugging = null, status = BuiltinShellStatus.WirelessDebuggingOff)
        val v = PhoneSetupFlow.view(claimed)
        assertEquals(SetupStep.WirelessDebugging, v.suspect?.step)
        assertTrue(v.usbFallback)
    }

    /** はアシスタントの起動に USB コマンドのみを使用し (この携帯電話ではワイヤレス デバッグがまったく利用できない可能性があります)、ワイヤレス デバッグはオフになりました。 */
    private val usbOnly = switchesOn.copy(wirelessDebugging = false, restart = HelperRestart.UsbCommand)

    /** アシスタントが実行中: 正常に、ワイヤレス デバッグとペアリングの 2 つのステップがスキップされたものとして記録されますが、ワイヤレス デバッグについてはどこにも記載されていません。 */
    @Test
    fun usbStartedHelperRunningIsSuccessWithWirelessStepsSkipped() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v.phase)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        assertEquals(StepMark.Done, v.marks[SetupStep.Connect])
        assertEquals(HelperRestart.UsbCommand, v.restart)
        assertFalse(v.usbFallback)
    }

    /** が引き渡されています (アシスタントが交代し、アプリは起動して到着を待つだけです): 接続中であることが示されますが、ワイヤレス デバッグがオフになっているため、そのステップには戻りません。 */
    @Test
    fun handoverDoesNotFlipBackToWirelessStep() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v.phase)
        assertNull(v.current)
        // ペアリングされた携帯電話はユーザーに対してワイヤレス デバッグをオンにしており、接続時も同様です。
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.Connecting)).phase)
        // フロントスイッチが本当にオフになっても、そのステップに戻ります
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(usbOnly.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting)).current)
    }

    /** アシスタントが停止しました: 接続ステップで停止し、停止したと表示され、USB コマンドが与えられますが、ワイヤレス デバッグにはつながりません。ペアリングしたい人は、クリックしてペアリング手順に戻ることができます。 */
    @Test
    fun usbHelperStoppedShowsTheCommandNotWirelessDebugging() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        val v = PhoneSetupFlow.view(usbOnly.copy(status = stopped))
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(stopped, v.failure)
        assertEquals(BuiltinShellFix.RunUsbCommand, v.failure?.fix)
        assertTrue(v.usbFallback)
        assertNull(v.suspect)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        // システムにワイヤレス デバッグ項目がない場合も同様です。
        assertEquals(SetupStep.Connect, PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = null, wirelessMissing = true, status = stopped)).current)
        // クリックしてペアリング手順に戻ります: 通常どおりペアリングします ワイヤレス デバッグに使用できる
        val back = PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = true, status = stopped, revisit = SetupStep.PairingCode))
        assertEquals(SetupStep.PairingCode, back.current)
        assertTrue(back.revisiting)
    }

    /** ペアリングされた携帯電話は影響を受けません。ワイヤレス デバッグがオフになっていてアシスタントが利用できない場合でも、そのステップに戻ることができます (オンにすると自動的に接続されます)。 */
    @Test
    fun pairedPhoneStillGoesBackToWirelessStep() {
        val v = PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.WirelessDebuggingOff, restart = HelperRestart.Wireless))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        val ok = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running, restart = HelperRestart.Wireless))
        assertTrue(ok.markList().all { it == StepMark.Done })
    }

    private val noWay = fresh.copy(developerSettingsMissing = true, wirelessMissing = true)

    @Test
    fun environmentWithoutDeveloperOptionsOrWirelessDebuggingSaysSoUpFront() {
        val v = PhoneSetupFlow.view(noWay)
        assertEquals(SetupPhase.Unavailable, v.phase)
        assertNull(v.current)
        assertFalse(v.usbFallback)
        assertTrue(v.markList().all { it == StepMark.Todo })
        // 「再ペアリング」をクリックしてもペアリングに失敗しました
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed))).phase)
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(repair = true)).phase)
    }

    @Test
    fun onlyOneOfTheTwoMissingStillGuides() {
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(developerSettingsMissing = true)).phase)
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).phase)
    }

    @Test
    fun aHelperThatRunsOrRanBeforeMeansThereIsAWay() {
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Running)).phase)
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(status = BuiltinShellStatus.Connecting)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.UsbCommand)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.Wireless)))
        assertTrue(PhoneSetupFlow.unavailable(true, true, BuiltinShellStatus.NotPaired, HelperRestart.None))
    }
}
