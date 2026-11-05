package com.wangzan101.mika.cap.phone.setup

import android.app.NotificationManager
import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PairingScreen
import com.wangzan101.mika.core.shell.PairingScreenReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNoticeTest {
    @get:Rule val texts = ChineseTexts()

    /** 探索中は無音で通知を準備し、ウィンドウを検出したら入力欄をポップアップします。探索していない場合は通知しません。 */
    @Test
    fun codeEntryPopsOnceThePairingWindowIsFoundAndWaitsQuietlyBefore() {
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.WirelessDebuggingOff, PairingDiscovery.Searching))
        assertNull(PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Idle))
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Found(37001)))
    }

    /** 無音の入力通知はユーザー補助の有無にかかわらず即座に準備し、繰り返し通知しません。 */
    @Test
    fun quietEntryIsPostedAtOnceAndNeverRealerts() {
        val quiet = PairingNotice.EnterCode(null, quiet = true)
        assertEquals(0L, PairingNotice.delayBefore(quiet, autoRead = true))
        assertNull(PairingNotice.realertAfter(quiet, 0))
    }

    @Test
    fun enterCodeThenPairingThenSuccess() {
        val found = PairingDiscovery.Found(1)
        assertEquals(PairingNotice.EnterCode(1), PairingNotice.of(BuiltinShellStatus.NotPaired, found))
        assertEquals(PairingNotice.Working, PairingNotice.of(BuiltinShellStatus.Connecting, found))
        assertEquals(PairingNotice.Done, PairingNotice.of(BuiltinShellStatus.Running, PairingDiscovery.Idle))
    }

    @Test
    fun pairingFailuresKeepTheInputWithHowToRetry() {
        assertEquals(
            PairingNotice.EnterCode(5, "コードが違うか、期限切れです。ペア設定のウィンドウで「ペア設定コードによるデバイスのペア設定」をもう一度タップして新しいコードを表示し、入力し直してください"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), PairingDiscovery.Found(5)),
        )
        // ウィンドウは閉じられています (ペアリング ウィンドウが見つかりません) が、入力ボックスはまだ残っています。同じ方法が使用されます。
        val closed = PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), PairingDiscovery.Searching)
        closed as PairingNotice.EnterCode
        assertNull(closed.port)
        assertTrue(closed.error!!.endsWith("新しいコードを表示し、入力し直してください"))
        // ペアリングに失敗した場合は、Mika までご連絡ください。
        assertEquals(
            PairingNotice.Problem("接続しましたが、準備できませんでした"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), PairingDiscovery.Found(5)),
        )
    }

    /** ペアリングの失敗: ガイド ページでは、ユーザーに再ペアリングするよう求められます。ペアリング ウィンドウを見つけた後の入力ボックスは、ペアになっていないときと同じになります。まだ見つからない場合でも、ユーザーはミカに戻るように求められます。 */
    @Test
    fun revokedPairingGetsTheInputOnceThePairingWindowIsFound() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(revoked, PairingDiscovery.Found(37001)))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(revoked, PairingDiscovery.Searching))
        assertEquals(PairingNotice.Problem("ペア設定の期限が切れました"), PairingNotice.of(revoked, PairingDiscovery.Idle))
        // USBから起動したヘルパーが停止した場合は、探索中でもコード入力欄を出しません。
        assertEquals(
            PairingNotice.Problem("スマホのヘルパーが停止しました"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Searching),
        )
        // USB 起動コマンドで起動したアシスタントが停止し、コマンドを再度実行する必要があります。ペアリングウィンドウを見つけても、入力ボックスがありません。
        assertEquals(
            PairingNotice.Problem("スマホのヘルパーが停止しました"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Found(37001)),
        )
    }

    /** ペアリングウィンドウが見つかった場合、入力ボックスを与えるかどうか、障害なくコードを読み取って試行するかどうかの通知(PairingScreen.attempt)は状態ごとに一貫しています。 */
    @Test
    fun codeInputMatchesWhatAutoReadWouldTry() {
        val reading = PairingScreenReading("123456", null)
        val statuses = listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
        ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }
        statuses.forEach { s ->
            val input = PairingNotice.of(s, PairingDiscovery.Found(5)) is PairingNotice.EnterCode
            assertEquals(s.toString(), PairingScreen.attempt(reading, portFound = true, s, emptySet()) != null, input)
        }
    }

    @Test
    fun codeEntryPopsAtOnceWithoutAccessibilityAndWaitsForAutoRead() {
        val enter = PairingNotice.EnterCode(5)
        assertEquals(0L, PairingNotice.delayBefore(enter, autoRead = false))
        assertEquals(PairingNotice.AUTO_READ_GRACE_MS, PairingNotice.delayBefore(enter, autoRead = true))
        assertEquals(0L, PairingNotice.delayBefore(PairingNotice.Working, autoRead = true))
    }

    @Test
    fun realertsAreCappedAtThree() {
        val enter = PairingNotice.EnterCode(5)
        val waits = generateSequence(0) { it + 1 }.map { PairingNotice.realertAfter(enter, it) }.takeWhile { it != null }.toList()
        assertEquals(3, waits.size)
        assertEquals(PairingNotice.MAX_REALERTS, waits.size)
        // 後ろに行くほど時間がかかります 間違った情報を入力すると、
        assertEquals(waits.filterNotNull().sorted(), waits)
        // に再度通知されます。他のステータスについてはリマインダーは表示されません。
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(PairingNotice.EnterCode(5, "x"), 0))
        listOf(PairingNotice.Working, PairingNotice.Done, PairingNotice.Problem("x")).forEach { assertNull(PairingNotice.realertAfter(it, 0)) }
    }

    @Test
    fun aSubmittedCodeShowsPairingUntilThePairingStarts() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        // コードを入力した後、ペアリングが開始されていません: コードを入力する前の状態に従ってカウントされず、終了しません (アシスタントが最初に実行されている場合も同様で、ペアリング開始後にステータスが変わります)
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(revoked, PairingDiscovery.Searching, repairing = false, submitted = true))
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = false, submitted = true))
        // コードが入力されていない場合は、通常通りステータスに応じて計算されます。
        assertEquals(PairingNotice.Problem("ペア設定の期限が切れました") to false, PairingNotice.follow(revoked, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    @Test
    fun followingEndsOnlyWhenRunningOutsideARepair() {
        // 再ペアリング前の実行状態を成功と見なさず、無音のコード入力通知を準備します。
        assertEquals(PairingNotice.EnterCode(null, quiet = true) to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = true, submitted = false))
        assertEquals(PairingNotice.Done to true, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    /** 無音の準備・ウィンドウ検出・コード入力・成功の各状態で通知が更新され、同じ通知を続けて送らないことを確認します。 */
    @Test
    fun aPairingRunGoesQuietThenAlertThenWorkingThenDone() {
        val steps = listOf(
            BuiltinShellStatus.NotPaired to PairingDiscovery.Searching,
            BuiltinShellStatus.NotPaired to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Connecting to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Running to PairingDiscovery.Idle,
        ).map { (s, d) -> PairingNotice.follow(s, d, repairing = false, submitted = false) }
        assertEquals(
            listOf(
                PairingNotice.EnterCode(null, quiet = true) to false,
                PairingNotice.EnterCode(37001) to false,
                PairingNotice.Working to false,
                PairingNotice.Done to true,
            ),
            steps,
        )
        // ウィンドウ発見後は、ポップアップと再通知の対象になります。
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(steps[1].first!!, 0))
    }

    @Test
    fun channelIsCreatedHighWhenMissing() {
        assertEquals(ChannelPlan.Create(PairingChannel.IDS.last()), PairingChannel.plan(emptyList()))
        assertEquals(PairingChannelHealth.Ready, PairingChannel.plan(emptyList()).health)
    }

    @Test
    fun channelKeepsWhatWorksMigratesOldOnesAndRespectsTheUser() {
        val old = PairingChannel.IDS.first()
        val new = PairingChannel.IDS.last()
        // は古いバージョンで構築されており、十分な高さがあるため、ID を変更せずに引き続き使用します。
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Ready),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_HIGH, userSet = false))),
        )
        // 古いバージョンは十分な高さで構築されておらず、ユーザーが触っていません: 新しい ID に置き換えて再構築してください
        assertEquals(
            ChannelPlan.Create(new),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = false))),
        )
        // ユーザーが自分で調整します: 元に戻さないでください。プロンプトが表示されます
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = true))),
        )
        // ユーザーがオフになりました: 送信できません
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Off),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_NONE, userSet = true))),
        )
        // の最新 ID は十分な高さではなく、これを置き換える新しい ID がありません。プロンプトを表示することしかできません。
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_LOW, userSet = false))),
        )
        // 老いも若きも新しいものを見よ
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Ready),
            PairingChannel.plan(
                listOf(
                    ChannelSnapshot(old, NotificationManager.IMPORTANCE_LOW, userSet = true),
                    ChannelSnapshot(new, NotificationManager.IMPORTANCE_HIGH, userSet = false),
                ),
            ),
        )
    }
}
