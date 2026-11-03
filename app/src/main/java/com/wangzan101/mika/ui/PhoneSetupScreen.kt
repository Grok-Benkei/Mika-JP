package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.setupInputs
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.PairingCode
import com.wangzan101.mika.core.shell.PairingNotifyIssue
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.SetupPhase
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupView
import com.wangzan101.mika.core.shell.StepMark
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ユーザーは、画面を回転してバックグラウンドに入った後もステップが残っていることを確認しました。 */
private val StepSetSaver = Saver<Set<SetupStep>, String>(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet() },
)

/**
 * 「携帯電話の操作を設定する」ガイド ページ: リアルタイム ステータスに接続します。各ステップでシステム スイッチを押すと、ボックスが自動的にチェックされます。読めない場合、または時間通りに読めない場合、ユーザーは「すでに開いています。次のステップ」をクリックします。
 * リスト内の前のステップをクリックして戻ってやり直します。ペアリングのステップで停止したら、ペアリング ウィンドウを探し始めます。アクセシビリティがオンになっている場合、ミカは自分でペアリング コードを読み取ります。そうでない場合は、入力ボックスを含む通知が送信されます。 設定ページの「再ペアリング」から
 * [repair]が入ります。
 */
@Composable
fun PhoneSetupScreen(status: SystemStatus, repair: Boolean, onFix: (Fix) -> Unit, onboarding: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val pairing = phone.pairing
    val discovery by phone.shellControl.pairingDiscovery.collectAsState()
    val scanning by phone.shellManager.scanningPorts.collectAsState()
    val shell = status.builtinShell
    var repairStarted by rememberSaveable { mutableStateOf(false) }
    if (repair && !repairStarted) { repairStarted = true; pairing.startRepair() }
    val repairing by pairing.repairing.collectAsState()
    var confirmed by rememberSaveable(stateSaver = StepSetSaver) { mutableStateOf(emptySet<SetupStep>()) }
    var revisit by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var accessibilitySkipped by rememberSaveable { mutableStateOf(false) }

    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var usbAsked by rememberSaveable { mutableStateOf(false) }
    var stalledMs by remember { mutableLongStateOf(0L) }

    // su を検出した端末では root 起動も選べる。この場合は開発者向け設定やワイヤレスデバッグがなくても設定可能とする。
    val root = phone.shellManager.root
    LaunchedEffect(Unit) { root.refresh() }
    val rootState by root.state.collectAsState()
    val inputs = status.setupInputs(discovery, repairing, accessibilitySkipped, confirmed, revisit, stalledMs, usbAsked, rootState.suPresent)
    val view = PhoneSetupFlow.view(inputs)
    // 同じ場所でタイマーが止まり、何も進んでいない場合は、場所を変更すると最初からカウントされます。制限時間に達した場合、再度カウントする必要はありません。
    val stallPoint = PhoneSetupFlow.stallPoint(view)
    LaunchedEffect(stallPoint) {
        stalledMs = 0L
        if (stallPoint == null) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (stalledMs < PhoneSetupFlow.STALL_MS) {
            delay(1_000L)
            stalledMs = SystemClock.elapsedRealtime() - start
        }
    }
    // 「代わりにコンピューターの使用を開始します」が表示された場合は、接続が再び開始されるため、そのまま保持し、元に戻さないでください。
    LaunchedEffect(view.usbFallback) { if (view.usbFallback) usbAsked = true }
    // 戻ってそのステップをやり直します。オフからオンに変更されると、自動的に元のステップに戻ります。再度クリックする必要はありません。
    val revisitDetected = revisit?.let { PhoneSetupFlow.detected(it, inputs) }
    var revisitSawOff by rememberSaveable(revisit) { mutableStateOf(false) }
    LaunchedEffect(revisit, revisitDetected) {
        if (revisitDetected == false) revisitSawOff = true
        else if (revisitDetected == true && revisitSawOff) revisit = null
    }
    // 接続が開始されたか、成功しました。アシスタントに従い、振り返るステップで停止しないでください。
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Working || view.phase == SetupPhase.Success) revisit = null }
    // はペアリング ステップに達しました。ペアリング ウィンドウを探し始めます。通知で許可されていない場合は、この時点でリクエストしてください (通知にはペアリング コードを入力する必要があります)。
    // 「ペアリング」通知がオフになっています。システム設定でオンにする必要があります。自動的にジャンプしない場合は、ページ上のボタンを押してください。
    LaunchedEffect(view.current) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    // は、ペアリング手順で停止するとブート ページを離れます。おそらく、システム設定に移動してペアリング ウィンドウを開きます ([ワイヤレス デバッグに移動] をクリックする必要はありません)。この時点からさらに 10 分間様子を見てください。
    // ブートページが長時間開いたままになるのを防ぐため、ペアリングコードの読み取りとコードの入力に関する通知を停止しました。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    val notificationsOff = view.notifyIssue == PairingNotifyIssue.NotificationsOff
    LaunchedEffect(notificationsOff) {
        if (notificationsOff && !askedNotifications) {
            askedNotifications = true
            onFix(Fix.AllowNotifications)
        }
    }
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Success) phone.setupPrompt.clear() }
    // ブート ページを開くときに「コンピューターで開始」スクリプトを準備します (アシスタントが使用できない場合は、新しいパスワードを入力します)。 診断情報は、
    LaunchedEffect(Unit) { phone.shellManager.usbScript.refresh() }
    val usbCommand = remember { phone.shellManager.usbScript.command() }
    val copyUsbCommand = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(PhoneSetupCopy.USB_TITLE, usbCommand))
        Toast.makeText(context, PhoneSetupCopy.USB_COPIED, Toast.LENGTH_SHORT).show()
    }
    val close = { pairing.cancelRepair(); onClose() }
    BackHandler(onBack = close)
    PhoneSetupContent(
        view = view,
        onStepAction = { step ->
            when (step) {
                SetupStep.DeveloperOptions -> onFix(Fix.OpenAboutPhone)
                SetupStep.UsbDebugging -> onFix(Fix.OpenUsbDebugging)
                SetupStep.Accessibility -> onFix(Fix.OpenAccessibility)
                SetupStep.WirelessDebugging -> onFix(Fix.OpenDeveloperOptions)
                SetupStep.PairingCode -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                SetupStep.Connect -> Unit
            }
        },
        onFailureFix = { fix ->
            when (fix) {
                BuiltinShellFix.Retry -> onFix(Fix.RetryPhoneHelper)
                BuiltinShellFix.RePair -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                BuiltinShellFix.OpenWifi -> onFix(Fix.OpenWifi)
                BuiltinShellFix.OpenWirelessDebugging -> onFix(Fix.OpenWirelessDebugging)
                BuiltinShellFix.RunUsbCommand -> copyUsbCommand()
            }
        },
        onAllowNotifications = { onFix(Fix.AllowNotifications) },
        onOpenPairingChannel = { onFix(Fix.OpenPairingChannel) },
        headsUpLowered = status.notificationsAllowed && (status.attentionLowered || status.resultLowered),
        onOpenHeadsUp = { onFix(if (status.attentionLowered) Fix.OpenAttentionChannel else Fix.OpenResultChannel) },
        onConfirmStep = { step -> confirmed = confirmed + step; revisit = null },
        onSubmit = { code, port -> pairing.submit(code, port) },
        onClose = close,
        onAppInfo = { onFix(Fix.OpenAppInfo) },
        onSkipAccessibility = { accessibilitySkipped = true; revisit = null },
        onRevisit = { step -> revisit = step },
        onboarding = onboarding,
        onDiagnostics = { diagnosticsOpen = true },
        scanning = scanning,
        onConnectPort = { port ->
            phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellManager.connectWithPort(port)
            }
        },
        usbCommand = usbCommand,
        onAskUsb = { usbAsked = true },
        onCopyUsbCommand = copyUsbCommand,
        rootLine = rootState.takeIf { it.suPresent }?.let { PhoneSetupCopy.rootOptionLine(it.startGrant) },
        onRootStart = { if (rootState.start) root.retryStart() else root.setStart(true) },
    )
    // カードのどのステップでも作成者に送信できます。ガイド ページの時点での入力が使用され、ユーザーが確認したステップも含まれます。
    if (diagnosticsOpen) {
        DiagnosticsSheet(load = { Diagnostics.render(DiagnosticsCollector.collect(context, status, inputs)) }, onDismiss = { diagnosticsOpen = false })
    }
}

/**
 * テストを容易にするためのステータスのないブート ページ。ステップは番号付きのリストです。完了したステップにはチェックマークが付き、現在のステップはボタンで点灯し、その後のステップはグレー表示になります。
 * には、スイッチを読み取るいくつかのステップのための [onConfirmStep] (「開いた、次のステップ」) があります。完了したステップ [onRevisit] をクリックすると、戻ってやり直すことができます。null を渡すと、元のステップに戻ることになります。
 * [onboarding] が true の場合、これは初めて開始するための最後のステップであり、下部に追加のページング ポイントの行が表示されます。 [onSubmit] 受信したコードは 6 桁の数字であることが確認されました。
 * ワンステップペアリング通知を送信できない場合 ([SetupView.needsNotifications])、最初の通知はメインボタン: [onAllowNotifications] または [onOpenPairingChannel] です。
 * [onDiagnostics] 空でない場合は常に下部に「診断情報のコピー」があり、失敗した場合はエラー ステップにもあります。
 * 成功に加えて、各ステージの下部には「後で話します」というメッセージが表示され、戻ってきたらいつでも終了できます。ユーザーはこのページに閉じ込められることはありません。
 * [scanning] が true の場合、接続ステップはマシンがポートを探していることを示します。接続できない場合、[onConnectPort] はユーザーが入力したワイヤレス デバッグ ポートを受け取ります (検証済み 1–65535)。
 * [usbCommand] は、空でない場合は「代わりにコンピューターの使用を開始します」が表示されます。通常は表示されません。ワイヤレス デバッグのステップでのみ「ワイヤレス デバッグが見つかりませんか?」という行が表示されます。 ([onAskUsb]);
 * [SetupView.usbFallback] が true の場合、現在のステップでカードが指定されます。これは、説明、コマンド、およびコピー ボタン ([onCopyUsbCommand]) です。
 * [SetupPhase.Unavailable] この環境は携帯電話の操作用にセットアップできません。説明カードと「わかりました」のみがあり、手順は記載されていません。
 * [rootLine] がある場合は、su を検出済みの端末向けに root での起動ボタン [onRootStart] と許可状態を表示する。
 */
@Composable
fun PhoneSetupContent(
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (code: String, port: Int?) -> Unit,
    onClose: () -> Unit,
    headsUpLowered: Boolean = false,
    onOpenHeadsUp: () -> Unit = {},
    onAppInfo: () -> Unit = {},
    onSkipAccessibility: () -> Unit = {},
    onRevisit: (SetupStep?) -> Unit = {},
    onboarding: Boolean = false,
    onOpenPairingChannel: () -> Unit = {},
    onDiagnostics: (() -> Unit)? = null,
    scanning: Boolean = false,
    onConnectPort: ((Int) -> Unit)? = null,
    usbCommand: String? = null,
    onAskUsb: () -> Unit = {},
    onCopyUsbCommand: () -> Unit = {},
    rootLine: String? = null,
    onRootStart: () -> Unit = {},
) {
    val canRevisit = view.phase == SetupPhase.Guide || view.phase == SetupPhase.Failed
    fun revisitable(step: SetupStep) = canRevisit && step != view.current && step in PhoneSetupFlow.REVISITABLE &&
        view.marks.getValue(step) in setOf(StepMark.Done, StepMark.Skipped, StepMark.Failed)
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(PhoneSetupCopy.BAR_TITLE, MikaIcons.Back, onClose, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (view.phase == SetupPhase.Unavailable) {
                    // この環境ではアシスタントになる方法はありません。説明のみで、機能しない手順はありません。
                    GroupCard {
                        Column(Modifier.padding(16.dp).testTag("setup-unavailable")) {
                            Text(PhoneSetupCopy.UNAVAILABLE, style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                            Text(
                                PhoneSetupCopy.UNAVAILABLE_REASON, Modifier.padding(top = 8.dp),
                                fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                            )
                        }
                    }
                } else {
                    if (view.phase == SetupPhase.Success) {
                        Text(
                            PhoneSetupCopy.SUCCESS_LINE, Modifier.padding(top = 4.dp, bottom = 16.dp).testTag("setup-success"),
                            style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ok,
                        )
                    } else {
                        Text(
                            PhoneSetupCopy.INTRO, Modifier.padding(top = 4.dp, bottom = 16.dp),
                            fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                        )
                    }
                    GroupCard {
                        SetupStep.entries.forEach { step ->
                            StepRow(
                                step, view.marks.getValue(step), current = step == view.current, first = step.ordinal == 0,
                                onClick = if (revisitable(step)) ({ onRevisit(step) }) else null,
                            ) {
                                if (step == view.current) CurrentStepBody(
                                    step, view, onStepAction, onFailureFix, onAllowNotifications, onOpenPairingChannel, headsUpLowered, onOpenHeadsUp,
                                    onConfirmStep, onSubmit, onAppInfo, onSkipAccessibility, onRevisit, onDiagnostics, onConnectPort,
                                )
                                val working = step == SetupStep.Connect && view.phase == SetupPhase.Working
                                if (working) WorkingLine(scanning)
                                if (usbCommand != null && (step == view.current || working)) {
                                    if (view.usbFallback) UsbStartCard(view.restart, usbCommand, onCopyUsbCommand)
                                    else if (step == SetupStep.WirelessDebugging) LinkText(PhoneSetupCopy.USB_ASK, "setup-usb-ask", onAskUsb)
                                }
                            }
                        }
                    }
                    if (rootLine != null && view.phase != SetupPhase.Success) RootStartOption(rootLine, onRootStart)
                    if (SetupStep.entries.any(::revisitable)) FooterNote(PhoneSetupCopy.REVISIT_NOTE)
                    if (view.phase != SetupPhase.Success) FooterNote(PhoneSetupCopy.SKIP_NOTE)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                if (view.phase == SetupPhase.Success) {
                    Text(
                        PhoneSetupCopy.successNote(view.restart), Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
                if (onboarding) ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Phone), Modifier.padding(bottom = 8.dp))
                when (view.phase) {
                    SetupPhase.Success -> MikaButton(
                        if (onboarding) PhoneSetupCopy.START_USING else PhoneSetupCopy.SUCCESS_DONE, onClose,
                        Modifier.fillMaxWidth().testTag("setup-done"),
                    )
                    SetupPhase.Unavailable -> MikaButton(stringResource(R.string.ok_got_it), onClose, Modifier.fillMaxWidth().testTag("setup-got-it"))
                    // は接続中ですが接続できません。最初に終了してから設定を続行することができます。
                    SetupPhase.Guide, SetupPhase.Working, SetupPhase.Failed -> MikaButton(
                        PhoneSetupCopy.LATER, onClose, Modifier.fillMaxWidth().testTag("setup-later"),
                        kind = ButtonKind.Text, size = ButtonSize.Medium,
                    )
                }
                onDiagnostics?.let {
                    MikaButton(
                        PhoneSetupCopy.DIAGNOSTICS, it, Modifier.fillMaxWidth().testTag("setup-diagnostics"),
                        kind = ButtonKind.Text, size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** root 化済み端末向けの起動ボタン。明示的なタップで一度 su に要求し、説明または応答を添える。 */
@Composable
private fun RootStartOption(line: String, onStart: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        MikaButton(PhoneSetupCopy.ROOT_OPTION, onStart, Modifier.testTag("setup-root"), kind = ButtonKind.Text, size = ButtonSize.Small)
        Text(line, Modifier.padding(start = 12.dp, end = 12.dp).testTag("setup-root-line"), style = MikaType.Caption, color = c.ink2)
    }
}

/**
 * 別の方法: コンピューターを使用し、USB データ ケーブル経由でアシスタントを起動します。現在のステップに配置され、このステップの他のプロンプトと同じ明るい色の背景 (タイトル、説明、コマンド、コピー ボタン) が表示されます。
 * アシスタントが最初にこのように開始されたとき ([HelperRestart.UsbCommand])、タイトルには「もう一度実行する」と表示されていました。
 */
@Composable
private fun UsbStartCard(restart: HelperRestart, command: String, onCopy: () -> Unit) {
    val c = MikaTheme.colors
    val (title, body) = PhoneSetupCopy.usbCard(restart)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(12.dp)).padding(12.dp).testTag("setup-usb")) {
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        Text(body, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)
        Text(
            command, Modifier.padding(top = 8.dp).fillMaxWidth().background(c.fill, RoundedCornerShape(10.dp)).padding(10.dp).testTag("setup-usb-command"),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
        )
        PillButton(PhoneSetupCopy.USB_COPY, primary = false, tag = "setup-usb-copy", onClick = onCopy)
    }
}

@Composable
private fun StepRow(step: SetupStep, mark: StepMark, current: Boolean, first: Boolean, onClick: (() -> Unit)?, body: @Composable () -> Unit) {
    val copy = PhoneSetupCopy.step(step)
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).testTag("setup-step-${step.name}")) {
        if (!first) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepBadge(step.ordinal + 1, mark)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        copy.title, Modifier.weight(1f), style = MikaType.Body,
                        fontWeight = if (mark == StepMark.Current || mark == StepMark.Failed) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.ink,
                    )
                    if (mark == StepMark.Done) RowValue(copy.done, ValueTone.Ok)
                    if (mark == StepMark.Skipped) RowValue(PhoneSetupCopy.ACCESSIBILITY_SKIPPED)
                }
                if (!current) Text(copy.brief, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = if (mark == StepMark.Todo) c.ink3 else c.ink2)
                body()
            }
        }
    }
}

@Composable
private fun StepBadge(n: Int, mark: StepMark) {
    val c = MikaTheme.colors
    val bg = when (mark) {
        StepMark.Done -> c.ok
        StepMark.Current -> c.accent
        StepMark.Failed -> c.bad
        StepMark.Todo, StepMark.Skipped -> c.greySoft
    }
    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Done -> Icon(MikaIcons.Check, null, Modifier.size(15.dp), tint = c.onAccent)
            StepMark.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(15.dp), tint = c.onAccent)
            else -> Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.onAccent)
        }
    }
}

@Composable
private fun CurrentStepBody(
    step: SetupStep,
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenPairingChannel: () -> Unit,
    headsUpLowered: Boolean,
    onOpenHeadsUp: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (String, Int?) -> Unit,
    onAppInfo: () -> Unit,
    onSkipAccessibility: () -> Unit,
    onRevisit: (SetupStep?) -> Unit,
    onDiagnostics: (() -> Unit)?,
    onConnectPort: ((Int) -> Unit)?,
) {
    val copy = PhoneSetupCopy.step(step)
    Text(copy.body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    view.failure?.let { f ->
        val fc = PhoneSetupCopy.failure(f.reason)
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.badSoft, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(fc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.bad)
            Text(fc.body, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink2)
        }
        // USB コマンドを再度実行する場合は、下のカードにコマンド ボタンとコピー ボタンがあるため、ボタンを繰り返す必要はありません。
        if (f.fix != BuiltinShellFix.RunUsbCommand || !view.usbFallback) {
            PillButton(PhoneSetupCopy.fixLabel(f.fix), primary = true, tag = "setup-fix") { onFailureFix(f.fix) }
        }
        // は自動的にポートを見つけることができません。「ワイヤレス デバッグ」ページに従って情報を入力するようにユーザーに依頼します。
        if (step == SetupStep.Connect && f.reason == BuiltinShellFailure.ConnectFailed) onConnectPort?.let { ConnectPort(it) }
        if (view.phase == SetupPhase.Failed) onDiagnostics?.let { LinkText(PhoneSetupCopy.DIAGNOSTICS, "setup-diagnostics-failed", it) }
    }
    // 失敗の原因として最も考えられるステップはどれですか: それを明確にして、「戻る」ボタンを押してください
    view.suspect?.let { s ->
        Text(
            PhoneSetupCopy.suspect(s),
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                .testTag("setup-suspect"),
            fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink,
        )
        PillButton(PhoneSetupCopy.goBackTo(s.step), primary = view.failure == null, tag = "setup-suspect-back") { onRevisit(s.step) }
    }
    // バリアがない場合、ペアリング コードは通知にのみ入力できます。通知を送信できない場合は、まずそれを修正してください。これがこのステップのメインボタンです。
    val fixNotificationsFirst = step == SetupStep.PairingCode && view.needsNotifications && !view.autoRead
    if (step == SetupStep.PairingCode) {
        when (view.notifyIssue) {
            PairingNotifyIssue.NotificationsOff -> {
                Text(PhoneSetupCopy.NEED_NOTIFICATIONS, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.ALLOW_NOTIFICATIONS, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onAllowNotifications() }
            }
            PairingNotifyIssue.ChannelOff -> {
                Text(PhoneSetupCopy.PAIRING_CHANNEL_OFF, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.OPEN_PAIRING_CHANNEL, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onOpenPairingChannel() }
            }
            // ポップアップを表示せずに、プルダウンしてテキストを入力することもできます。メイン ボタンをつかまずに、リマインダーのみを表示します。バリアがオンになったときに通知は必要なく、言及もされません。
            PairingNotifyIssue.NotHeadsUp -> if (!view.autoRead) {
                Text(
                    PhoneSetupCopy.PAIRING_LOWERED,
                    Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                        .testTag("setup-pairing-lowered"),
                    fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink,
                )
                PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-pairing-lowered-open") { onOpenPairingChannel() }
            }
            null -> Unit
        }
        if (headsUpLowered && !view.needsNotifications) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                    .testTag("setup-heads-up"),
            ) {
                Text(PhoneSetupCopy.HEADS_UP_LOWERED, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.warn)
            }
            PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-heads-up-open") { onOpenHeadsUp() }
        }
        if (view.autoRead) AutoReadHint() else NotifyGuide()
        if (view.portFound && !view.autoRead) Text(PhoneSetupCopy.PORT_FOUND, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MikaTheme.colors.ok)
    }
    // バリアフリーのための推奨スキップステップ: ペアリング後、ミカは自分で開くことができ、「制限付き設定」によってブロックされません。
    val a11y = step == SetupStep.Accessibility
    if (a11y) PillButton(PhoneSetupCopy.ACCESSIBILITY_SKIP, primary = true, tag = "setup-skip-a11y", onClick = onSkipAccessibility)
    if (view.failure == null && copy.action != null) {
        PillButton(copy.action, primary = !a11y && !fixNotificationsFirst, tag = "setup-action") { onStepAction(step) }
    }
    if (a11y && view.showAppInfo) PillButton(PhoneSetupCopy.APP_INFO, primary = false, tag = "setup-app-info", onClick = onAppInfo)
    // で読んだスイッチは参考用です。ユーザーは、一度オンにすると下がり、どのステップでも動かなくなると述べています。
    if (step in PhoneSetupFlow.CONFIRMABLE) PillButton(PhoneSetupCopy.TURNED_ON_NEXT, primary = false, tag = "setup-confirm") { onConfirmStep(step) }
    if (step == SetupStep.PairingCode) ManualCode(askPort = !view.portFound, onSubmit = onSubmit)
    if (view.revisiting) LinkText(PhoneSetupCopy.REVISIT_BACK, "setup-revisit-back") { onRevisit(null) }
}

@Composable
private fun LinkText(text: String, tag: String?, onClick: () -> Unit) {
    Text(
        text, Modifier.padding(top = 6.dp).clickable(onClick = onClick).padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        fontSize = 13.sp, color = MikaTheme.colors.accent,
    )
}

/** は問題なくオンになります。システム設定に進む前に、Mika が自動的にペアリング コードを読み取ります。 */
@Composable
private fun AutoReadHint() {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.okSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-hint"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Check, null, Modifier.size(20.dp), tint = c.ok)
        Text(PhoneSetupCopy.AUTO_READ_HINT, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
    }
}

/** アクセシビリティがオンになっていません: 小さな 3 行の番号付きアイコンを使用して、ペアリング ウィンドウを離れることなく通知にペアリング コードを入力する方法を説明します。 */
@Composable
private fun NotifyGuide() {
    val c = MikaTheme.colors
    val icons = listOf(MikaIcons.Phone, MikaIcons.Bell, MikaIcons.Key)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-guide"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneSetupCopy.NOTIFY_GUIDE.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccent)
                }
                Icon(icons[i], null, Modifier.size(18.dp), tint = c.accent)
                Text(line, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = c.ink)
            }
        }
    }
}

/** 通知がポップアップしない場合の対処法: アプリにコードを直接入力します (ペアリング ウィンドウが見つからない場合はポートを入力します)。 */
@Composable
private fun ManualCode(askPort: Boolean, onSubmit: (String, Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        PhoneSetupCopy.MANUAL_TOGGLE, Modifier.padding(top = 8.dp).clickable { open = !open }.padding(vertical = 4.dp).testTag("setup-manual"),
        fontSize = 13.sp, color = MikaTheme.colors.accent,
    )
    if (!open) return
    Text(PhoneSetupCopy.MANUAL_HINT, fontSize = 12.5.sp, lineHeight = 17.sp, color = MikaTheme.colors.ink3)
    OutlinedTextField(
        code, { code = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-code"),
        label = { Text(PhoneSetupCopy.CODE_LABEL) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    if (askPort) {
        OutlinedTextField(
            port, { port = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-port"),
            label = { Text(PhoneSetupCopy.PORT_LABEL) }, supportingText = { Text(PhoneSetupCopy.PORT_HINT) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    error?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = MikaTheme.colors.bad) }
    PillButton(PhoneSetupCopy.SUBMIT, primary = true, tag = "setup-submit") {
        val c = PairingCode.normalize(code)
        val p = if (askPort && port.isNotBlank()) PairingCode.port(port) else null
        error = when {
            c == null -> PhoneSetupCopy.CODE_INVALID
            askPort && port.isNotBlank() && p == null -> PhoneSetupCopy.PORT_INVALID
            else -> null
        }
        if (error == null && c != null) onSubmit(c, p)
    }
}

/** ユーザーが入力したワイヤレス デバッグ ポート: 「IP アドレスとポート」は、スペースまたは全角数字を使用してセクション全体 (最後のコロンの後に取得) に貼り付けることができます。 1 ～ 65535 でない場合は、null になります。 */
internal object ConnectPortInput {
    fun parse(text: String): Int? {
        val tail = text.substringAfterLast(':').substringAfterLast('：')
        val digits = tail.filterNot { it.isWhitespace() }.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        return PairingCode.port(digits)
    }
}

/** が接続できない場合の注意事項: [ワイヤレス デバッグ] ページでポートを入力し、[接続] をクリックします。 */
@Composable
private fun ConnectPort(onConnect: (Int) -> Unit) {
    var port by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        port, { port = it; invalid = false }, Modifier.fillMaxWidth().padding(top = 10.dp).keepsKeyboard().testTag("setup-connect-port"),
        label = { Text(PhoneSetupCopy.CONNECT_PORT_LABEL) },
        supportingText = { Text(if (invalid) PhoneSetupCopy.PORT_INVALID else PhoneSetupCopy.CONNECT_PORT_HINT, color = if (invalid) MikaTheme.colors.bad else MikaTheme.colors.ink3) },
        singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    PillButton(PhoneSetupCopy.CONNECT_PORT_SUBMIT, primary = false, tag = "setup-connect-port-submit") {
        val p = ConnectPortInput.parse(port)
        invalid = p == null
        if (p != null) onConnect(p)
    }
}

@Composable
private fun WorkingLine(scanning: Boolean) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MikaTheme.colors.accent, strokeWidth = 2.dp)
        Text(
            if (scanning) PhoneSetupCopy.SCANNING_PORTS else PhoneSetupCopy.WORKING, Modifier.testTag("setup-working"),
            fontSize = 13.5.sp, color = MikaTheme.colors.ink2,
        )
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.padding(top = 10.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small,
    )
}

// ---------- 設定ページ「詳細設定」 ----------

/** 「詳細」グループには、ステータス、再ペアリング、ペアリング解除 (要確認) の 3 行の組み込みアシスタントがあります。ペアリングされていない場合はキャンセルできません。 */
@Composable
fun PhoneHelperSection(status: BuiltinShellStatus, onRepair: () -> Unit, onUnpair: () -> Unit, usbDebugging: Boolean = true) {
    var confirming by remember { mutableStateOf(false) }
    GroupCard {
        val (value, tone) = PhoneSetupCopy.statusValue(status)
        ListRow(MikaIcons.Phone, PhoneSetupCopy.ROW_TITLE, PhoneSetupCopy.statusLine(status, usbDebugging), first = true) {
            RowValue(value, tone)
        }
        ListRow(MikaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, onClick = onRepair) {
            RowChevron()
        }
        val paired = status != BuiltinShellStatus.NotPaired
        ListRow(
            MikaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
            titleColor = if (paired) MikaTheme.colors.bad else MikaTheme.colors.ink3, tone = if (paired) TileTone.Bad else TileTone.Neutral, onClick = if (paired) ({ confirming = true }) else null,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = { TextButton({ confirming = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- ダイアログページ ----------

/** タスクに携帯電話の使用が必要で、携帯電話の操作の準備ができていない場合、タスク カードの下の行。 */
@Composable
fun PhoneSetupLine(onOpen: () -> Unit) {
    val shape = MikaShapes.Card
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).cardSurface(MikaTheme.colors, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(PhoneSetupCopy.DEGRADED_LINE, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink2)
        Text(
            PhoneSetupCopy.DEGRADED_ACTION, Modifier.clickable(onClick = onOpen).padding(4.dp).testTag("phone-setup-open"),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.accent,
        )
    }
}
