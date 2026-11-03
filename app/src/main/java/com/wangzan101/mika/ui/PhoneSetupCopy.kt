package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import com.wangzan101.mika.ui.components.ValueTone

/**
 * 「モバイル操作」ガイド、設定ページ、通知のテキスト (純粋な機能、テストが簡単)。
 * は現地語を使用します。携帯電話の設定に既に存在する単語 (開発者向けオプション、USB デバッグ、ワイヤレス デバッグ、ペアリング コード) のみが表示され、adb、ポート、シェルなどの用語については言及されていません。
 * 例外は接続が失敗した場合で、ユーザーは「ワイヤレス デバッグ」ページに従ってポートを入力するように求められます。
 */
object PhoneSetupCopy {
    val TITLE: String get() = tr(R.string.phone_title)
    val INTRO: String get() = tr(R.string.phone_intro)
    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val LATER: String get() = tr(R.string.phone_later)
    /** ブート ページの上部バーの短いタイトル。 */
    val BAR_TITLE: String get() = tr(R.string.phone_bar_title)
    val SKIP_NOTE: String get() = tr(R.string.phone_skip_note)
    /** この環境は携帯電話の操作用にセットアップできません ([com.wangzan101.mika.core.shell.PhoneSetupFlow.unavailable] を参照): 結論と理由。 */
    val UNAVAILABLE: String get() = tr(R.string.phone_unavailable)
    val UNAVAILABLE_REASON: String get() = tr(R.string.phone_unavailable_reason)

    /** [brief] は、順番ではない場合、または完了した場合のリスト内の行の短い説明です。順番が来ると、完全な [body] が表示されます。 */
    data class StepCopy(val title: String, val body: String, val action: String?, val done: String, val brief: String)

    fun step(s: SetupStep): StepCopy = when (s) {
        SetupStep.DeveloperOptions -> StepCopy(
            tr(R.string.phone_step_dev),
            tr(R.string.phone_step_dev_body),
            tr(R.string.phone_step_dev_action), tr(R.string.phone_done_on), tr(R.string.phone_step_dev_brief),
        )
        SetupStep.UsbDebugging -> StepCopy(
            tr(R.string.phone_step_usb),
            tr(R.string.phone_step_usb_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_usb_brief),
        )
        SetupStep.Accessibility -> StepCopy(
            tr(R.string.phone_step_a11y),
            tr(R.string.phone_step_a11y_body),
            tr(R.string.phone_step_a11y_action), tr(R.string.phone_done_on), tr(R.string.phone_step_a11y_brief),
        )
        SetupStep.WirelessDebugging -> StepCopy(
            tr(R.string.phone_step_wireless),
            tr(R.string.phone_step_wireless_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_wireless_brief),
        )
        SetupStep.PairingCode -> StepCopy(
            tr(R.string.phone_step_pair),
            tr(R.string.phone_step_pair_body),
            tr(R.string.phone_step_pair_action), tr(R.string.phone_paired), tr(R.string.phone_step_pair_brief),
        )
        SetupStep.Connect -> StepCopy(
            tr(R.string.phone_step_connect),
            tr(R.string.phone_step_connect_body),
            null, tr(R.string.phone_connected), tr(R.string.phone_step_connect_brief),
        )
    }

    val ACCESSIBILITY_SKIP: String get() = tr(R.string.phone_a11y_skip)
    val ACCESSIBILITY_SKIPPED: String get() = tr(R.string.phone_a11y_skipped)
    /** システムは、アクセシビリティを開く方法をブロックします (アプリ ストアからインストールされていないアプリに共通)。 */
    val APP_INFO: String get() = tr(R.string.phone_app_info)

    /** ワンステップペアリング：バリアフリー時はミカが自分でコードを読み取ります。 */
    val AUTO_READ_HINT: String get() = tr(R.string.phone_auto_read)
    /** が無効になっている場合の 3 ステップの図: システムのペアリング ボタンをクリックし、上部にポップアップ表示される通知を確認し、通知にコードを入力します。 */
    val NOTIFY_GUIDE: List<String> get() = listOf(
        tr(R.string.phone_guide_1), tr(R.string.phone_guide_2), tr(R.string.phone_guide_3, NOTIFY_ACTION),
    )

    /** スイッチを読み取る手順の簡単な説明: 一部の携帯電話ではスイッチを読み取れないか、正確に読み取れません。ユーザーは電源がオンになっていることを確認し、ダウンします。 */
    val TURNED_ON_NEXT: String get() = tr(R.string.phone_turned_on_next)
    /** クリックして前のステップに戻ってやり直す場合、変更しない場合は戻ってください。 */
    val REVISIT_BACK: String get() = tr(R.string.phone_revisit_back)
    val REVISIT_NOTE: String get() = tr(R.string.phone_revisit_note)

    /** ペアリングと接続が失敗した場合、戻ってやり直す可能性が最も高い手順は何ですか? */
    fun suspect(s: SetupSuspect): String = when {
        s.step == SetupStep.DeveloperOptions -> tr(R.string.phone_suspect_dev)
        s.unreadable -> tr(R.string.phone_suspect_wireless_unreadable)
        else -> tr(R.string.phone_suspect_wireless_off)
    }

    fun goBackTo(s: SetupStep): String = tr(R.string.phone_suspect_go_back, step(s).title)
    val PORT_FOUND: String get() = tr(R.string.phone_port_found)
    val NEED_NOTIFICATIONS: String get() = tr(R.string.phone_need_notif)
    val ALLOW_NOTIFICATIONS: String get() = tr(R.string.explain_notif)
    /** 「ペアリング」通知がオフになっています: ペアリング コードを入力できません。最初に開いてください。 */
    val PAIRING_CHANNEL_OFF: String get() = tr(R.string.phone_pairing_channel_off, CHANNEL_NAME)
    val OPEN_PAIRING_CHANNEL: String get() = tr(R.string.phone_open_pairing_channel, CHANNEL_NAME)
    /** 「ペアリング」通知は、ポップアップしないようにユーザーによって調整されました。プルダウンして入力することもでき、リマインダーとエントリのみが表示されます。 */
    val PAIRING_LOWERED: String get() = tr(R.string.phone_pairing_lowered, CHANNEL_NAME)
    /** 通知は許可されますが、「処理する必要があります」と「タスクの結果」はポップアップしないように調整されています。後続の手順をブロックせず、明確にします。 */
    val HEADS_UP_LOWERED: String get() = tr(R.string.phone_heads_up_lowered)
    val OPEN_HEADS_UP: String get() = tr(R.string.heads_up_action)
    val MANUAL_TOGGLE: String get() = tr(R.string.phone_manual_toggle)
    val MANUAL_HINT: String get() = tr(R.string.phone_manual_hint)
    val CODE_LABEL: String get() = tr(R.string.phone_code_label)
    val PORT_LABEL: String get() = tr(R.string.phone_port_label)
    val PORT_HINT: String get() = tr(R.string.phone_port_hint)
    val SUBMIT: String get() = tr(R.string.phone_submit)
    val CODE_INVALID: String get() = tr(R.string.phone_code_invalid)
    val PORT_INVALID: String get() = tr(R.string.phone_port_invalid)

    val WORKING: String get() = tr(R.string.phone_working)
    /** 前の方法ではポートが見つからず、マシンはスキャン中です。 */
    val SCANNING_PORTS: String get() = tr(R.string.phone_scanning_ports)
    /** を使用すると、接続が失敗したときにユーザーが [ワイヤレス デバッグ] ページでポートを入力できます。 */
    val CONNECT_PORT_LABEL: String get() = tr(R.string.phone_connect_port_label)
    val CONNECT_PORT_HINT: String get() = tr(R.string.phone_connect_port_hint)
    val CONNECT_PORT_SUBMIT: String get() = tr(R.string.phone_connect_port_submit)
    val SUCCESS_LINE: String get() = tr(R.string.phone_success)
    /** 成功ページの下部にあるリマインダー: 電話機の再起動後にアシスタントを戻す方法。前回の起動方法を確認してください。 */
    fun successNote(restart: HelperRestart): String =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_success_note_usb) else tr(R.string.phone_success_note)
    /** 初めて開始する最後の成功したステップの後の一番下のボタン。設定から入るのは [SUCCESS_DONE] です。 */
    val START_USING: String get() = tr(R.string.phone_start_using)
    val SUCCESS_DONE: String get() = tr(R.string.done)

    /** 別の方法: コンピューターを使用して、USB データ ケーブル経由でアシスタントを起動します (コマンド自体は変換されません)。必要な場合にのみ表示されます。 [USB_ASK] は、ユーザーがワイヤレス デバッグ ステップで呼び出すことができる行です。 */
    val USB_ASK: String get() = tr(R.string.phone_usb_ask)
    val USB_TITLE: String get() = tr(R.string.phone_usb_title)
    val USB_BODY: String get() = tr(R.string.phone_usb_body)
    val USB_COPY: String get() = tr(R.string.phone_usb_copy)
    val USB_COPIED: String get() = tr(R.string.phone_usb_copied)

    /** 「代わりにコンピューターを使用して開始」カードのタイトルと説明: アシスタントは元々このコマンドで起動されましたが、「ワイヤレス デバッグを開けません」ではなく、再度実行するように指示されただけです。 */
    fun usbCard(restart: HelperRestart): Pair<String, String> =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usb_again_title) to tr(R.string.phone_usb_again_body)
        else USB_TITLE to USB_BODY

    data class FailureCopy(val title: String, val body: String)

    fun failure(f: BuiltinShellFailure): FailureCopy = when (f) {
        BuiltinShellFailure.WrongCode -> FailureCopy(
            tr(R.string.fail_wrong_code),
            tr(R.string.fail_wrong_code_body),
        )
        BuiltinShellFailure.PairingWindowClosed -> FailureCopy(
            tr(R.string.fail_window_closed),
            tr(R.string.fail_window_closed_body),
        )
        BuiltinShellFailure.NoWifi -> FailureCopy(
            tr(R.string.fail_no_wifi),
            tr(R.string.fail_no_wifi_body),
        )
        BuiltinShellFailure.WifiNotAllowed -> FailureCopy(
            tr(R.string.fail_wifi_not_allowed),
            tr(R.string.fail_wifi_not_allowed_body),
        )
        BuiltinShellFailure.ConnectFailed -> FailureCopy(
            tr(R.string.fail_connect),
            tr(R.string.fail_connect_body),
        )
        BuiltinShellFailure.AuthorizationRevoked -> FailureCopy(
            tr(R.string.fail_revoked),
            tr(R.string.fail_revoked_body),
        )
        BuiltinShellFailure.KeyUnreadable -> FailureCopy(
            tr(R.string.fail_key_unreadable),
            tr(R.string.fail_key_unreadable_body),
        )
        BuiltinShellFailure.HelperStartFailed -> FailureCopy(
            tr(R.string.fail_helper),
            tr(R.string.fail_helper_body),
        )
        BuiltinShellFailure.UsbHelperStopped -> FailureCopy(
            tr(R.string.fail_usb_stopped),
            tr(R.string.fail_usb_stopped_body),
        )
        BuiltinShellFailure.Unknown -> FailureCopy(
            tr(R.string.fail_unknown),
            tr(R.string.fail_unknown_body),
        )
    }

    fun fixLabel(fix: BuiltinShellFix): String = when (fix) {
        BuiltinShellFix.Retry -> tr(R.string.retry)
        BuiltinShellFix.RePair -> tr(R.string.phone_repair)
        BuiltinShellFix.OpenWifi -> tr(R.string.phone_open_wifi)
        BuiltinShellFix.OpenWirelessDebugging -> tr(R.string.phone_open_wireless)
        BuiltinShellFix.RunUsbCommand -> USB_COPY
    }

    // ---------- 設定ページ ----------

    val RECONNECT: String get() = tr(R.string.phone_reconnect)

    /** はペアリングされていますが、接続されていないか、アシスタントが失われています。ブート ページに戻らずに、クリックしてペアリングされたキーを使用して再度接続してください。 */
    fun canReconnect(s: BuiltinShellStatus): Boolean = s is BuiltinShellStatus.Failed && s.fix == BuiltinShellFix.Retry

    val ROW_TITLE: String get() = tr(R.string.settings_phone_ops)
    val ROW_SUBTITLE: String get() = tr(R.string.phone_row_sub)

    /** 設定 › 携帯電話の操作ページの下部にある完全な手順: USB 起動コマンドのみを使用してアシスタントを起動すると、再起動後にコマンドを再度実行するように表示されますが、ワイヤレス デバッグをオンにするようには表示されません。 */
    fun usageNote(restart: HelperRestart): String =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usage_note_usb) else tr(R.string.phone_usage_note)

    fun statusValue(s: BuiltinShellStatus): Pair<String, ValueTone> = when (s) {
        BuiltinShellStatus.Running -> tr(R.string.sandbox_ready) to ValueTone.Ok
        BuiltinShellStatus.NotPaired -> tr(R.string.settings_not_set) to ValueTone.Bad
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_wireless_off) to ValueTone.Bad
        BuiltinShellStatus.Connecting -> tr(R.string.phone_connecting) to ValueTone.Plain
        is BuiltinShellStatus.Failed -> tr(R.string.phone_error) to ValueTone.Bad
    }

    /** 「詳細」のステータス行の説明。 [usbDebugging] がオフになっている場合、実行中にリマインダーが表示されます。Wi-Fi が切断されると停止します。 */
    fun statusLine(s: BuiltinShellStatus, usbDebugging: Boolean = true): String = when (s) {
        BuiltinShellStatus.Running ->
            if (usbDebugging) tr(R.string.phone_line_running) else tr(R.string.phone_line_running_no_usb)
        BuiltinShellStatus.NotPaired -> tr(R.string.phone_line_not_paired)
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_line_wireless_off)
        BuiltinShellStatus.Connecting -> tr(R.string.phone_connecting)
        is BuiltinShellStatus.Failed -> when (s.reason) {
            BuiltinShellFailure.NoWifi -> NO_WIFI_LINE
            BuiltinShellFailure.WifiNotAllowed -> tr(R.string.phone_line_joined, failure(s.reason).title, WIFI_NOT_ALLOWED_LINE)
            // は停止し、ユーザーにアクションを実行するよう求めました。再開する方法を直接指示しました。
            BuiltinShellFailure.UsbHelperStopped -> failure(s.reason).body
            else -> failure(s.reason).title
        }
    }

    val NO_WIFI_LINE: String get() = tr(R.string.phone_no_wifi_line)
    val WIFI_NOT_ALLOWED_LINE: String get() = tr(R.string.phone_wifi_not_allowed_line)

    val REPAIR: String get() = tr(R.string.phone_repair)
    val REPAIR_SUBTITLE: String get() = tr(R.string.phone_repair_sub)
    val UNPAIR: String get() = tr(R.string.phone_unpair)
    val UNPAIR_SUBTITLE: String get() = tr(R.string.phone_unpair_sub)
    val UNPAIR_TITLE: String get() = tr(R.string.phone_unpair_title)
    val UNPAIR_BODY: String get() = tr(R.string.phone_unpair_body)
    val UNPAIR_CONFIRM: String get() = tr(R.string.phone_unpair_confirm)

    // ---------- ダイアログページ（携帯電話操作不可時） ----------

    val DEGRADED_LINE: String get() = tr(R.string.phone_degraded)
    val DEGRADED_ACTION: String get() = tr(R.string.chat_go_settings)

    // ---------- 注意事項 ----------

    val CHANNEL_NAME: String get() = tr(R.string.phone_channel)
    val NOTIFY_ENTER_TITLE: String get() = tr(R.string.phone_notify_enter_title)
    val NOTIFY_ENTER_TEXT: String get() = tr(R.string.phone_notify_enter_text)
    val NOTIFY_WINDOW_FOUND: String get() = tr(R.string.phone_notify_window_found)
    /** は 6 桁の番号ではありませんでした。その場の通知で、試さないと言いました。 */
    val NOTIFY_CODE_INVALID: String get() = tr(R.string.phone_notify_code_invalid)

    /** ペアリング フェーズが失敗した場合の通知にはどのようなメッセージが表示されますか。コードが間違っているか、有効期限が切れているか、ウィンドウが閉じられているため、新しいコードを取得して再度入力する必要があります。 */
    fun notifyRetry(f: BuiltinShellFailure): String = when (f) {
        BuiltinShellFailure.WrongCode -> tr(R.string.phone_notify_retry_code)
        BuiltinShellFailure.PairingWindowClosed -> tr(R.string.phone_notify_retry_window)
        else -> failure(f).title
    }
    val NOTIFY_ACTION: String get() = tr(R.string.phone_notify_action)
    val NOTIFY_INPUT_LABEL: String get() = tr(R.string.phone_notify_input)
    val NOTIFY_WORKING: String get() = tr(R.string.phone_notify_working)
    val NOTIFY_DONE_TITLE: String get() = tr(R.string.phone_notify_done_title)
    val NOTIFY_DONE_TEXT: String get() = tr(R.string.phone_notify_done_text)
    val NOTIFY_PROBLEM_TEXT: String get() = tr(R.string.phone_notify_problem)
}
