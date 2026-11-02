package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupCopyTest {
    @get:Rule val texts = ChineseTexts()

    /** これらの専門用語は、ユーザーに表示される単語には表示されません (システム設定にもともと存在するオプション名を除く、以下を参照)。 */
    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "プロセス")

    private fun allText(): List<String> = buildList {
        add(PhoneSetupCopy.INTRO)
        SetupStep.entries.forEach { val c = PhoneSetupCopy.step(it); add(c.title); add(c.body); c.action?.let(::add); add(c.done); add(c.brief) }
        BuiltinShellFailure.entries.forEach { val f = PhoneSetupCopy.failure(it); add(f.title); add(f.body) }
        BuiltinShellFix.entries.forEach { add(PhoneSetupCopy.fixLabel(it)) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it)); add(PhoneSetupCopy.statusLine(it, usbDebugging = false)); add(PhoneSetupCopy.statusValue(it).first) }
        addAll(
            listOf(
                PhoneSetupCopy.SUCCESS_LINE, PhoneSetupCopy.UNPAIR_BODY, PhoneSetupCopy.SKIP_NOTE, PhoneSetupCopy.DEGRADED_LINE, PhoneSetupCopy.PORT_FOUND,
                PhoneSetupCopy.NEED_NOTIFICATIONS, PhoneSetupCopy.MANUAL_HINT, PhoneSetupCopy.NOTIFY_ENTER_TEXT,
                PhoneSetupCopy.NOTIFY_ENTER_TITLE, PhoneSetupCopy.NOTIFY_ACTION, PhoneSetupCopy.NOTIFY_INPUT_LABEL,
                PhoneSetupCopy.AUTO_READ_HINT, PhoneSetupCopy.ACCESSIBILITY_SKIP, PhoneSetupCopy.NOTIFY_WINDOW_FOUND,
                PhoneSetupCopy.NOTIFY_CODE_INVALID, PhoneSetupCopy.PAIRING_CHANNEL_OFF, PhoneSetupCopy.OPEN_PAIRING_CHANNEL,
                PhoneSetupCopy.PAIRING_LOWERED, PhoneSetupCopy.NOTIFY_DONE_TITLE, PhoneSetupCopy.NOTIFY_DONE_TEXT,
                PhoneSetupCopy.ACCESSIBILITY_SKIPPED, PhoneSetupCopy.APP_INFO, PhoneSetupCopy.TURNED_ON_NEXT,
                PhoneSetupCopy.REVISIT_BACK, PhoneSetupCopy.REVISIT_NOTE, PhoneSetupCopy.CONNECT_PORT_SUBMIT,
            ),
        )
        HelperRestart.entries.forEach { add(PhoneSetupCopy.successNote(it)); add(PhoneSetupCopy.usageNote(it)) }
        suspects.forEach { add(PhoneSetupCopy.suspect(it)); add(PhoneSetupCopy.goBackTo(it.step)) }
        addAll(PhoneSetupCopy.NOTIFY_GUIDE)
        BuiltinShellFailure.entries.forEach { add(PhoneSetupCopy.notifyRetry(it)) }
    }

    private val suspects = listOf(
        SetupSuspect(SetupStep.DeveloperOptions, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = true),
    )

    private val statuses = listOf(
        BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
    ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }

    @Test
    fun connectPortInputAcceptsOnlyValidPorts() {
        assertEquals(41235, ConnectPortInput.parse("41235"))
        assertEquals(41235, ConnectPortInput.parse(" 41 235 "))
        assertEquals(41235, ConnectPortInput.parse("４１２３５"))
        // 「IPアドレスとポート」は全部貼り付けても大丈夫です
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10:41235"))
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10：41235"))
        assertEquals(1, ConnectPortInput.parse("1"))
        assertEquals(65535, ConnectPortInput.parse("65535"))
        for (bad in listOf("", " ", "0", "65536", "70000", "abc", "41a35", "-1", "192.0.2.10:")) assertNull(bad, ConnectPortInput.parse(bad))
    }

    @Test
    fun reconnectOnlyWhenRetryingIsTheFix() {
        val reconnectable = statuses.filter { PhoneSetupCopy.canReconnect(it) }
        assertEquals(
            listOf(BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.HelperStartFailed, BuiltinShellFailure.Unknown),
            reconnectable.map { (it as BuiltinShellStatus.Failed).reason },
        )
        reconnectable.forEach { s ->
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(s)))
        }
        // がペアリングされていない場合は、起動する必要があります。ワイヤレス デバッグがオフになっている場合は、最初にオンにする必要があります。 Wi-Fi がない場合は自動的に回復します。ペアリングに失敗した場合は、再度ペアリングする必要があります。
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Running, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
            BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
        ).forEach { assertFalse(PhoneSetupCopy.canReconnect(it)) }
    }

    @Test
    fun bannerAndFixForPhoneHelperProblem() {
        statuses.filter { it != BuiltinShellStatus.Running }.forEach { s ->
            val p = Problem.PhoneHelper(s)
            val fix = when {
                PhoneSetupCopy.canReconnect(s) -> Fix.RetryPhoneHelper
                // この Wi-Fi ではまだワイヤレス デバッグが許可されていません。自分で開いてください。
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) -> Fix.OpenWirelessDebugging
                else -> Fix.OpenPhoneSetup
            }
            assertEquals(fix, StatusCopy.directFix(p))
            assertEquals(Fix.OpenPhoneSetup, StatusCopy.explain(p, "14").fix)
        }
    }

    @Test
    fun copyIsPlainJapanese() {
        // 接続が失敗すると、ユーザーは「ワイヤレス デバッグ」ページに従ってポートを入力するよう求められます。「ポート」と直接書かれているのは唯一の文です。
        val portAllowed = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> if (!(j == "端口" && t == portAllowed)) assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
            // システム内のオプション名のみに adb が含まれます
            if (t.contains("adb")) assertTrue(t, t.contains("「adb 認証のタイムアウトを無効にする」"))
        }
    }

    @Test
    fun failuresAreDistinctAndActionable() {
        val titles = BuiltinShellFailure.entries.map { PhoneSetupCopy.failure(it).title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("もう一度ペア設定", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode).fix))
        assertEquals("再試行", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed).fix))
    }

    /** USB コマンドによって開始されたアシスタントが停止しました。設定ページとバナーにはすべて停止していることが表示され、コマンドを実行します。ワイヤレス デバッグにはつながりません (携帯電話によっては、ワイヤレス デバッグがまったくできない場合もあります)。 */
    @Test
    fun usbHelperStoppedSaysHowToStartItAgain() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        assertEquals("スマホのヘルパーが停止しました", PhoneSetupCopy.failure(BuiltinShellFailure.UsbHelperStopped).title)
        val line = PhoneSetupCopy.statusLine(stopped)
        assertTrue(line, line.contains("USB の開始コマンド") && line.contains("もう一度実行"))
        assertFalse(line, line.contains("ワイヤレス デバッグ"))
        assertEquals(BuiltinShellFix.RunUsbCommand, stopped.fix)
        assertEquals("コピー", PhoneSetupCopy.fixLabel(stopped.fix))
        // はもともとコマンドで開始されました。カードには「もう一度実行してください」と表示されましたが、「ワイヤレス デバッグを開けません」とは表示されませんでした。
        val (againTitle, againBody) = PhoneSetupCopy.usbCard(HelperRestart.UsbCommand)
        assertEquals("開始コマンドをもう一度実行", againTitle)
        assertTrue(againBody.contains("再起動"))
        assertEquals(PhoneSetupCopy.USB_TITLE to PhoneSetupCopy.USB_BODY, PhoneSetupCopy.usbCard(HelperRestart.None))
        val banner = StatusCopy.banner(Problem.PhoneHelper(stopped))
        assertEquals("スマホのヘルパーが停止しました", banner.title)
        assertEquals(line, banner.detail)
        assertEquals("このスマホでワイヤレス デバッグを使えませんか？ 代わりにパソコンから開始します", PhoneSetupCopy.USB_TITLE)
        assertEquals("ワイヤレス デバッグが見つかりませんか？", PhoneSetupCopy.USB_ASK)
        assertTrue(PhoneSetupCopy.USB_BODY.contains("USB ケーブル"))
        assertTrue(PhoneSetupCopy.USB_BODY.contains("再起動"))
    }
}
