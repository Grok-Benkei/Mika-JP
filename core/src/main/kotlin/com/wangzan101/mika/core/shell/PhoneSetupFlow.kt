package com.wangzan101.mika.core.shell

/** 6 つの手順で起動します。 */
enum class SetupStep { DeveloperOptions, UsbDebugging, Accessibility, WirelessDebugging, PairingCode, Connect }

/** [Skipped]: ユーザーはこのステップをスキップしました (バリアがない場合にのみスキップできます)。 */
enum class StepMark { Done, Current, Todo, Failed, Skipped }

enum class SetupPhase {
    /** は、ユーザーが特定のステップを実行するのを待ちます。 */
    Guide,
    /** はペアリング、接続、開始中: 進行状況を表示します。 */
    Working,
    Success,
    /** エラー: [SetupView.failure] は理由と修正を示し、[SetupView.suspect] は戻ってやり直す可能性が最も高い手順を示します。 */
    Failed,
    /** この環境ではアシスタントを取得する方法はありません ([PhoneSetupFlow.unavailable] を参照)。最初から説明し、機能しない手順を提示しないでください。 */
    Unavailable,
}

/** ペアリング通知チャネルは次のようになります: [Off] はオフになっており、通知は送信できません。 [Lowered] は、画面上部にポップアップ表示されないようにユーザーが調整し、通知バーを引き下げて通知バーを埋めることができます。 */
enum class PairingChannelHealth { Ready, Lowered, Off }

/** 未準備地域に通知するには、通知にペアリングコードを記入する必要があります。 */
enum class PairingNotifyIssue {
    /** ミカは通知を送信することを許可されていません（アプリ全体の許可または通知切り替え）。 */
    NotificationsOff,
    /** 「ペアリング」通知がオフになりました。 */
    ChannelOff,
    /** 「ペアリング」通知はポップアップしません。ブロックされず、通知されるだけです。 */
    NotHeadsUp,
}

/**
 * ブート ページの入力: スイッチはシステム設定、ユーザー自身の確認、およびアシスタントのステータスを読み取ります。
 * によって読み取られるスイッチは、自動的にチェックを入れてプロンプトを表示するためにのみ使用され、ブロックされません。一部の携帯電話ではスイッチを読み取れないか、正確に読み取れません。ユーザーは「開いたので次のステップ」([confirmed]) をクリックしてダウンします。
 * [wirelessDebugging] は null です。これは、携帯電話がこのスイッチを読み取ることができないことを意味します (元のバージョンに従って設定項目を書き込まないでください)。ブロックしないでください。オープンとみなされ、ダウンします。
 * [confirmed] は、ユーザーが完了したと述べた手順を指します。 [revisit] は、リスト内の前のステップをクリックし、戻ってやり直したいユーザーを指します。
 * [repair] ユーザーが設定ページで「再ペアリング」をクリックしました。すでに実行されている場合でも、新しいペアリングが開始されるまでペアリング手順に戻ります。
 * [usbDebugging] がオンの場合、Wi-Fi が切断されたり、システムによってワイヤレス デバッグがオフになったりしても、アシスタントは停止しません。
 * [accessibility] は、Mika のアクセシビリティ サービスが実行されています。ペアリング時にペアリング コードを読み取ります。ユーザーは ([accessibilitySkipped]) をスキップして、それを返して通知に記入することができます。
 * [restrictedSettings] システムにより、アクセシビリティが開かれない可能性があります (アプリ ストアからインストールされていないアプリは、まずアプリ情報で制限された設定を許可する必要があります)。
 * [notificationsAllowed]、[pairingChannel] は、通知にペアリング コードを入力できるかどうかを決定します。
 * 次の 3 つの決定により、「コンピューターの起動を使用する」かどうかが決まります ([SetupView.usbFallback] を参照)。
 * [wirelessMissing] システムにはワイヤレス デバッグはまったくありません (この設定は、オリジナル バージョンをオンにすると常に書き込まれます。読み取れない場合は、システムにこの機能がありません)。
 * [stalledMs] は、ワイヤレス デバッグ、ペアリング (ペアリング ウィンドウがまだ見つかりません)、および自動接続で長時間進行せずにスタックしています ([PhoneSetupFlow.stallPoint] を参照)。
 * [usbAsked] ユーザーが「ワイヤレス デバッグが見つかりませんか?」をクリックしたか、カードがすでに表示されています (しばらく消えないように、表示された後は取り戻されません)。
 * [restart] は、アシスタントが停止した後に再び起動するために依存するものです ([HelperRestart] を参照): USB 起動コマンドのみが使用される場合、ワイヤレス デバッグとペアリングの 2 つのステップはスキップされたものとして記録されます。
 * アシスタントが停止すると、接続ステップで停止し、停止したと表示され、コマンドが与えられ、ワイヤレス デバッグ ステップには戻りません。
 * [developerSettingsMissing] はシステムの「開発者向けオプション」ページを開けません (スキップされたインテントにはページ接続がありません): [wirelessMissing] と一緒に確立されると、
 * ユーザーは、USB デバッグもワイヤレス デバッグも有効にすることができません。[PhoneSetupFlow.unavailable] を参照してください。
 * [rootAvailable] はsuを利用できる状態です。root経由の起動には開発者向けオプションが不要なので、他の経路がない状態とはみなしません。
 */
data class SetupInputs(
    val developerOptions: Boolean,
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
    val discovery: PairingDiscovery = PairingDiscovery.Idle,
    val notificationsAllowed: Boolean = true,
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    val repair: Boolean = false,
    val usbDebugging: Boolean = true,
    val accessibility: Boolean = true,
    val accessibilitySkipped: Boolean = false,
    val restrictedSettings: Boolean = false,
    val confirmed: Set<SetupStep> = emptySet(),
    val revisit: SetupStep? = null,
    val wirelessMissing: Boolean = false,
    val stalledMs: Long = 0L,
    val usbAsked: Boolean = false,
    val restart: HelperRestart = HelperRestart.None,
    val developerSettingsMissing: Boolean = false,
    val rootAvailable: Boolean = false,
)

/** ペアリングまたは接続が失敗する場合の最も考えられる理由: [step] この読み取りステップがオフになっています。 [unreadable] はこの携帯電話では実際には読み取れません。 */
data class SetupSuspect(val step: SetupStep, val unreadable: Boolean)

data class SetupView(
    val phase: SetupPhase,
    val marks: Map<SetupStep, StepMark>,
    /** 現時点で実行するステップ (ガイドおよび失敗時)。作業中/成功の場合は null。 */
    val current: SetupStep?,
    val failure: BuiltinShellStatus.Failed? = null,
    /** ペアリング ウィンドウが見つかりました: ユーザーに通知内のコードを入力するように求めます。 */
    val portFound: Boolean = false,
    /** はペアリングの最初のステップで停止し、ここで問題を通知します。 [needsNotifications] が true の場合、通知にコードを入力する前に修復する必要があります。 */
    val notifyIssue: PairingNotifyIssue? = null,
    /** は、ペアリングの最初のステップで何の障害もなく開いています。ミカはペアリング コードを自分で読み取ります。通知にコードを記入する必要はありません。 */
    val autoRead: Boolean = false,
    /** は、アクセシビリティの 1 ステップで「アプリケーション情報に移動して制限付き設定を許可する」への入り口を提供します。 */
    val showAppInfo: Boolean = false,
    /** 失敗した場合は、前のステップに戻ってやり直す必要がある可能性が高くなります。インターフェイスには戻るボタンが用意されています。 */
    val suspect: SetupSuspect? = null,
    /** 現在のステップは、ユーザーがクリックして戻るとやり直すためのものです。インターフェイスには「元のステップに戻る」と表示されます。 */
    val revisiting: Boolean = false,
    /** 現在の手順では、「コンピューターを使用して開始する」ためのカード: ワイヤレス デバッグが利用できないか、スタックしている可能性があります。普通に歩いているときは与えられません。 */
    val usbFallback: Boolean = false,
    /** アシスタントが停止した後に再び立ち上がるために何を頼りにするか ([SetupInputs.restart] からコピー): 成功ページのリマインダーと、「代わりにコンピューターで開始する」カードの内容。見てください。 */
    val restart: HelperRestart = HelperRestart.None,
) {
    /** 通知を送信できません (許可されていないか、「ペアリング」通知がオフになっています): ペアリング コードを通知に入力し、最初に修正する必要があります。 */
    val needsNotifications: Boolean
        get() = notifyIssue == PairingNotifyIssue.NotificationsOff || notifyIssue == PairingNotifyIssue.ChannelOff
}

/**
 * ブート ページのステート マシン: すべての入力変更 (システム設定スイッチ、ユーザー確認、アシスタント ステータス、ペアリング ウィンドウの検索の進行状況) が再計算され、中間状態は保存されません。
 * 成功はアシスタントにのみ依存します。接続されると、以前に読み取られた内容に関係なく、セットアップされます。前の手順で完了したと判断された場合は、自動的にチェックされます。まだ完了していないことが読み取られ、ユーザーがそれを確認していない場合は、そのステップで停止します。
 * ただし、ユーザーはいつでも「開いた、次のステップ」をクリックして下に進むことができるため、どのステップもスタックすることはありません。
 * スイッチがオフで時間が確認されていないことを読み取ると、アシスタントが以前に何をしていても、そのステップに戻ります。
 */
object PhoneSetupFlow {
    /** 前面スイッチがオンになっていないために失敗する可能性のあるペアリングと接続にはいくつかの種類があります (ペアリング コードが正しくなく、ペアリング ウィンドウが開いていることを示すため、カウントされません)。 */
    private val SWITCH_RELATED = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.Unknown)

    /** ユーザーが「戻る」をクリックしてやり直すことができる手順がいくつかあります。自動接続手順では、ユーザーが行うことは何もありません。 */
    val REVISITABLE: Set<SetupStep> = SetupStep.entries.toSet() - SetupStep.Connect

    /** には、「電源を入れました。次のステップ」といういくつかのステップがあります。システム スイッチを読み取ります (障害物がない場合はスキップされます)。 */
    val CONFIRMABLE: Set<SetupStep> = setOf(SetupStep.DeveloperOptions, SetupStep.UsbDebugging, SetupStep.WirelessDebugging)

    /** は [stallPoint] で停止しました。あまりにも長い間進歩がなかったので、「代わりにコンピューターを使い始めましょう」としました。 */
    const val STALL_MS = 45_000L

    /** これらの種類の障害は、ワイヤレス デバッグ パスが利用できず、問題は「代わりにコンピューターの起動を使用する」ことを示します。つまり、ペアリング ウィンドウが見つからず、接続が確立できず、コンピューター起動アシスタントが停止しています。 */
    private val USB_FAILURES = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped)

    fun view(i: SetupInputs): SetupView {
        if (unavailable(i)) return SetupView(SetupPhase.Unavailable, SetupStep.entries.associateWith { StepMark.Todo }, null, restart = i.restart)
        return steps(i).let { it.copy(usbFallback = usbFallback(it, i), restart = i.restart) }
    }

    /**
     * 開発者向けオプションとワイヤレスデバッグがなく、ほかの起動経路もない場合はセットアップ不可とする。
     * ヘルパーが実行中・起動中、または過去に起動済みなら別の経路があるため対象外。
     * suが利用可能な [rootAvailable] の場合も、開発者向けオプションを使わず起動できるため対象外。
     */
    fun unavailable(
        developerSettingsMissing: Boolean, wirelessMissing: Boolean, status: BuiltinShellStatus, restart: HelperRestart,
        rootAvailable: Boolean = false,
    ): Boolean =
        developerSettingsMissing && wirelessMissing && restart == HelperRestart.None && !rootAvailable &&
            status != BuiltinShellStatus.Running && status != BuiltinShellStatus.Connecting

    fun unavailable(i: SetupInputs): Boolean =
        unavailable(i.developerSettingsMissing, i.wirelessMissing, i.status, i.restart, i.rootAvailable)

    /**
     * のスタック タイミングはどこでカウントされますか: ワイヤレス デバッグ ステップで停止、ペアリング ステップで停止したがペアリング ウィンドウが見つからず (システムにはペアリング サービスがない)、自動接続中です。
     * 最初のいくつかの手順では、ユーザーがシステム設定に移動してそれを見つける必要があります。どれだけ時間がかかっても問題ありません。場所を変更した場合は最初から計算されます。
     */
    fun stallPoint(v: SetupView): SetupStep? = when {
        v.phase == SetupPhase.Working -> SetupStep.Connect
        v.phase != SetupPhase.Guide -> null
        v.current == SetupStep.WirelessDebugging -> SetupStep.WirelessDebugging
        v.current == SetupStep.PairingCode && !v.portFound -> SetupStep.PairingCode
        else -> null
    }

    /**
     * 「パソコンでの起動に切り替える」を出すかどうか: システムにワイヤレスデバッグがない、ユーザーが見つけられないと言う、のいずれもワイヤレスデバッグのステップまで進んだ後でのみ出す（パソコン起動でも最初の2ステップは先に済ませる必要がある）;
     * ペアリングや接続の失敗が上記のいずれかの類にあるか、ユーザーが開いたと言っているのにヘルパーには切れていると読める; [stallPoint] で [STALL_MS] 以上止まっている。その他のときは出さない。
     */
    private fun usbFallback(v: SetupView, i: SetupInputs): Boolean {
        if (v.phase == SetupPhase.Success) return false
        val reachedWireless = v.phase == SetupPhase.Working || (v.current != null && v.current >= SetupStep.WirelessDebugging)
        return (reachedWireless && (i.wirelessMissing || i.usbAsked)) ||
            v.failure?.reason in USB_FAILURES ||
            (v.phase == SetupPhase.Failed && v.suspect?.step == SetupStep.WirelessDebugging) ||
            (stallPoint(v) != null && i.stalledMs >= STALL_MS)
    }

    private fun steps(i: SetupInputs): SetupView {
        val status = effectiveStatus(i.status, i.repair)
        if (status == BuiltinShellStatus.Running) return SetupView(SetupPhase.Success, marks(SetupStep.entries.size, i), null)
        val natural = natural(i, status)
        // 接続中は進捗を基準にする; より前のステップが切られたと読めたらまずそのステップへ戻る
        val back = i.revisit?.takeIf { it in REVISITABLE && status != BuiltinShellStatus.Connecting } ?: return natural
        val earlier = natural.phase == SetupPhase.Guide && natural.current != null && natural.current < back
        if (earlier) return natural
        // 他のステップは従来どおりチェックを付けるか失敗とし、元々止まっていたステップはまず未実施に戻す
        val marks = natural.marks.mapValues { (_, m) -> if (m == StepMark.Current) StepMark.Todo else m } + (back to StepMark.Current)
        return guide(back, i).copy(marks = marks, revisiting = true)
    }

    /**
     * 前に戻ってやり直すことはできません。接続 (アシスタントが交代するときや立ち上がるときなどの引き継ぎを含む) は進行状況に基づいて行われます。ワイヤレス デバッグがオフになっていると表示されたからといって、そのステップに戻らないでください。
     * コンピューター アシスタントがユーザーに対してワイヤレス デバッグを有効にできる場合、ユーザーはそれを行う必要はありません。 USB ブート コマンドによって起動されたアシスタントが停止し、ワイヤレス デバッグも起動しませんでした。
     */
    private fun natural(i: SetupInputs, status: BuiltinShellStatus): SetupView {
        if (blocks(SetupStep.DeveloperOptions, i)) return guide(SetupStep.DeveloperOptions, i)
        if (blocks(SetupStep.UsbDebugging, i)) return guide(SetupStep.UsbDebugging, i)
        if (blocks(SetupStep.Accessibility, i)) return guide(SetupStep.Accessibility, i)
        if (status == BuiltinShellStatus.Connecting) return working(i)
        if (status is BuiltinShellStatus.Failed && status.reason == BuiltinShellFailure.UsbHelperStopped) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                failure = status,
            )
        }
        // スイッチが読み取れない場合は、アシスタントの声明が優先されます。ユーザーは確認後にブロックしません。
        val wirelessConfirmed = SetupStep.WirelessDebugging in i.confirmed
        val wirelessOff = !wirelessConfirmed &&
            (i.wirelessDebugging == false || (i.wirelessDebugging == null && status == BuiltinShellStatus.WirelessDebuggingOff))
        if (wirelessOff) return guide(SetupStep.WirelessDebugging, i)
        // ユーザーはオンだと言いましたが、アシスタントは「オフ」、「接続しないでください」と読み上げました。ぐるぐる回し続けないでください。どのステップであるかを指摘し、戻るボタンを押しました。
        if (status == BuiltinShellStatus.WirelessDebuggingOff && wirelessConfirmed && i.wirelessDebugging != true) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                suspect = SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null),
            )
        }
        return when (status) {
            BuiltinShellStatus.Running -> error("handled above")
            BuiltinShellStatus.Connecting -> error("handled above")
            // はペアリングされており、ワイヤレス デバッグがオンになっていますが、ステータスが追いついていません。すぐに自動的に接続され、最初に「接続中」が表示されます。
            BuiltinShellStatus.WirelessDebuggingOff -> working(i)
            BuiltinShellStatus.NotPaired -> guide(SetupStep.PairingCode, i)
            is BuiltinShellStatus.Failed -> {
                val step = if (status.reason.duringPairing || status.fix == BuiltinShellFix.RePair) SetupStep.PairingCode else SetupStep.Connect
                SetupView(
                    SetupPhase.Failed, marks(step.ordinal, i, current = step, mark = StepMark.Failed), step, failure = status,
                    portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
                    notifyIssue = notifyIssue(step, i),
                    autoRead = step == SetupStep.PairingCode && i.accessibility,
                    suspect = if (status.reason in SWITCH_RELATED) suspect(i) else null,
                )
            }
        }
    }

    private fun working(i: SetupInputs) = SetupView(SetupPhase.Working, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect), null)

    /** は、このステップが適切に実行されていないことを読み取りましたが、ユーザーはそれを確認しませんでした (障害がない場合はスキップできます): このステップで停止します。 */
    private fun blocks(step: SetupStep, i: SetupInputs): Boolean =
        detected(step, i) == false && step !in i.confirmed && !(step == SetupStep.Accessibility && i.accessibilitySkipped)

    /** システム設定から読み取るステップが適切に行われていません。読み取れない場合、またはこのステップがスイッチではない場合は null です。 */
    fun detected(step: SetupStep, i: SetupInputs): Boolean? = when (step) {
        SetupStep.DeveloperOptions -> i.developerOptions
        SetupStep.UsbDebugging -> i.usbDebugging
        SetupStep.Accessibility -> i.accessibility
        SetupStep.WirelessDebugging -> i.wirelessDebugging
        SetupStep.PairingCode, SetupStep.Connect -> null
    }

    /** ペアリングと接続は開発者向けオプションとワイヤレス デバッグに依存します。読み取られて閉じられた (ユーザーは確認後にのみここに来る) か、読み取れなかった最初のものを順番に見つけます。 */
    private fun suspect(i: SetupInputs): SetupSuspect? = when {
        !i.developerOptions -> SetupSuspect(SetupStep.DeveloperOptions, unreadable = false)
        i.wirelessDebugging != true -> SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null)
        else -> null
    }

    private fun guide(step: SetupStep, i: SetupInputs) = SetupView(
        SetupPhase.Guide, marks(step.ordinal, i, current = step), step,
        portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
        notifyIssue = notifyIssue(step, i),
        autoRead = step == SetupStep.PairingCode && i.accessibility,
        showAppInfo = step == SetupStep.Accessibility && i.restrictedSettings,
    )

    private fun notifyIssue(step: SetupStep, i: SetupInputs): PairingNotifyIssue? = when {
        step != SetupStep.PairingCode -> null
        !i.notificationsAllowed -> PairingNotifyIssue.NotificationsOff
        i.pairingChannel == PairingChannelHealth.Off -> PairingNotifyIssue.ChannelOff
        i.pairingChannel == PairingChannelHealth.Lowered -> PairingNotifyIssue.NotHeadsUp
        else -> null
    }

    /** は USB ブート コマンドのみを使用してアシスタントを起動しますが、この 2 つの手順は実行されませんでした。 */
    private val WIRELESS_STEPS = setOf(SetupStep.WirelessDebugging, SetupStep.PairingCode)

    /** [done] より前の手順が完了しています (スキップされたアクセシビリティ、ワイヤレス デバッグ、USB 起動時に実行されないペアリングはスキップとして記録されます)。[current] この手順は [mark] としてマークされ、残りはまだ行われていません。 */
    private fun marks(done: Int, i: SetupInputs, current: SetupStep? = null, mark: StepMark = StepMark.Current): Map<SetupStep, StepMark> =
        SetupStep.entries.associateWith { s ->
            when {
                s.ordinal < done && s == SetupStep.Accessibility && !i.accessibility && s !in i.confirmed -> StepMark.Skipped
                s.ordinal < done && s in WIRELESS_STEPS && i.restart == HelperRestart.UsbCommand -> StepMark.Skipped
                s.ordinal < done -> StepMark.Done
                s == current -> mark
                else -> StepMark.Todo
            }
        }

    /** 「再ペアリング」の場合、ペアリング開始前（[BuiltinShellStatus.Connecting]）にアシスタントが起動していてもいなくても、まだペアリングしていないとみなされます。 */
    fun effectiveStatus(status: BuiltinShellStatus, repair: Boolean): BuiltinShellStatus =
        if (repair && status !is BuiltinShellStatus.Connecting) BuiltinShellStatus.NotPaired else status
}
