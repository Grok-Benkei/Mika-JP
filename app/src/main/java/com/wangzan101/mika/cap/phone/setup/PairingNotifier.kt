package com.wangzan101.mika.cap.phone.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.PhoneSetupCopy
import com.wangzan101.mika.setup.PairingCodeReceiver

/** このときペアリング通知に表示される内容。 */
sealed interface PairingNotice {
    /** [error] を示してコードの再入力を受け付けます。[quiet] の場合は音やポップアップを出さず通知欄に準備します。 */
    data class EnterCode(val port: Int?, val error: String? = null, val quiet: Boolean = false) : PairingNotice
    data object Working : PairingNotice
    data object Done : PairingNotice
    /** はペアリング後に接続できませんでした: ミカに戻って何をすべきかを確認してください。 */
    data class Problem(val title: String) : PairingNotice

    companion object {
        /**
         * は、ユーザーがペアリングしている期間 ([PairingCoordinator] がオンのとき) にのみ使用されます。入力ボックスは、ペアリング ウィンドウが見つかった場合にのみポップアップ表示されます。
         * システム設定へ移ると停止される機種に備え、探索中から通知欄にコード入力を準備します。
         * ウィンドウの発見後では通知できない場合もあるため、利用者が通知欄から入力できる状態にします。
         * ペアリング段階での失敗 (コードが正しくない、期限切れ、ウィンドウが閉じている) は通知に残され、補充されます。再ペアリングに失敗した場合 (ペアリングが無効な場合)、ペアリング ウィンドウを見つけた後に入力ボックスも表示されます。
         * PairingScreen.attempt と同じ判定で探索中も入力を準備し、それ以外の失敗はアプリへ戻るよう案内します。
         */
        fun of(status: BuiltinShellStatus, discovery: PairingDiscovery): PairingNotice? {
            val port = (discovery as? PairingDiscovery.Found)?.port
            return when (status) {
                BuiltinShellStatus.Running -> Done
                BuiltinShellStatus.Connecting -> Working
                is BuiltinShellStatus.Failed -> when {
                    status.reason.duringPairing -> EnterCode(port, PhoneSetupCopy.notifyRetry(status.reason))
                    status.fix == BuiltinShellFix.RePair && port != null -> EnterCode(port)
                    status.fix == BuiltinShellFix.RePair && discovery == PairingDiscovery.Searching -> EnterCode(null, quiet = true)
                    else -> Problem(PhoneSetupCopy.failure(status.reason).title)
                }
                BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> when (discovery) {
                    is PairingDiscovery.Found -> EnterCode(discovery.port)
                    PairingDiscovery.Searching -> EnterCode(null, quiet = true)
                    PairingDiscovery.Idle -> null
                }
            }
        }

        /** アクセシビリティ ペアリング コードを読み取ると、非常に長い時間が経った後にコード入力通知がポップアップ表示されます。おそらくペアリングされているので、気にしないでください。 */
        const val AUTO_READ_GRACE_MS = 5_000L
        /** 通知がポップアップ表示された後は誰もコードを入力しないため、非常に長い時間が経つと再び通知が表示されます。画面を更新せずに最大 [MAX_REALERTS] 回実行できます。 */
        val REALERT_MS = listOf(8_000L, 20_000L, 40_000L)
        val MAX_REALERTS = REALERT_MS.size

        /** 通知までの待機時間。無音の準備通知とユーザー補助なしの場合は待たず、それ以外の入力通知だけ自動読取りの猶予を設けます。 */
        fun delayBefore(notice: PairingNotice, autoRead: Boolean): Long =
            if (notice is EnterCode && autoRead && !notice.quiet) AUTO_READ_GRACE_MS else 0L

        /** は、次回までどれくらい時間がかかるかを [sent] 回通知されました。 null は、それ以上リマインダーがないことを意味します。 */
        fun realertAfter(notice: PairingNotice, sent: Int): Long? = if (notice is EnterCode && !notice.quiet) REALERT_MS.getOrNull(sent) else null

        /**
         * [PairingCoordinator] ステータス更新通知を追跡する場合、現時点でどの項目を表示するか、表示後に追跡を停止するかどうかの計算。
         * がコードを入力し、ペアリングが開始されていない場合 ([submitted])、常に「ペアリング中」となり、この時点では終了しません (アシスタントが最初から実行されている場合も同様で、ペアリングの開始後にステータスが変わります)。
         * 「再ペアリング」中の古い実行状態は、適切な一致とみなされません ([PhoneSetupFlow.effectiveStatus])。アシスタントは実行中ですが、終了するまで再ペアリングされません。
         */
        fun follow(status: BuiltinShellStatus, discovery: PairingDiscovery, repairing: Boolean, submitted: Boolean): Pair<PairingNotice?, Boolean> =
            if (submitted) Working to false
            else of(PhoneSetupFlow.effectiveStatus(status, repairing), discovery) to (status == BuiltinShellStatus.Running && !repairing)
    }
}

/** はシステム内にすでにペアになっているチャネルです。 [userSet] は、ユーザーが変更することが重要です。 */
data class ChannelSnapshot(val id: String, val importance: Int, val userSet: Boolean)

/** ペアリング チャネルの対処方法: [Create]、新しい [id] を作成します (高優先度)。 [Use]、既存の [id] を使用、[health] は現在のようになります。 */
sealed interface ChannelPlan {
    val id: String
    val health: PairingChannelHealth

    data class Create(override val id: String) : ChannelPlan {
        override val health: PairingChannelHealth get() = PairingChannelHealth.Ready
    }

    data class Use(override val id: String, override val health: PairingChannelHealth) : ChannelPlan
}

/**
 * ペアリングチャンネル選択 (純粋な機能、テストが簡単)。チャネルの重要性は、作成後にアプリで変更することはできません。
 * 古いバージョンは十分な高さで構築されておらず、ユーザーが触れていないため、新しい ID で再構築されます。ユーザーが自分で設定を下げるかオフにすると、元に戻すことはできず、ブート ページでプロンプトが表示されます。
 */
object PairingChannel {
    /** 使用されるチャネル ID は、進むにつれて新しくなります。新しくインストールされたチャネルの場合は、最後のチャネルを使用してください。 */
    val IDS = listOf("pairing_code", "pairing_alert")
    /** 以前のバージョンのチャンネルの場合は、設定に別の項目が追加されないように、表示されたら削除してください。 */
    const val LEGACY = "pairing"

    fun plan(existing: List<ChannelSnapshot>): ChannelPlan {
        val current = existing.filter { it.id in IDS }.maxByOrNull { IDS.indexOf(it.id) }
            ?: return ChannelPlan.Create(IDS.last())
        val newer = IDS.getOrNull(IDS.indexOf(current.id) + 1)
        return when {
            current.importance == NotificationManager.IMPORTANCE_NONE -> ChannelPlan.Use(current.id, PairingChannelHealth.Off)
            current.importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelPlan.Use(current.id, PairingChannelHealth.Ready)
            current.userSet -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
            newer != null -> ChannelPlan.Create(newer)
            else -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
        }
    }
}

/**
 * ペアリング通知: 優先度が高く、音と振動を伴い (おやすみ中にシステムの音声を聞きます)、システム設定のペアリング ウィンドウの上にポップアップ表示されます。
 * 入力ボックスは RemoteInput を使用するため、ユーザーはペアリング ウィンドウを離れる必要はありません。入力したコードは[PairingCodeInput]に付与されます。
 */
object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    private const val ID = 4401
    private const val DONE_TIMEOUT_MS = 8_000L
    /** コード埋め込み通知は、最大でこの長さ (一致するウィンドウと同じ長さ) にわたって留まることができます。たとえアプリが強制終了されても、常にハングするわけではありません。 */
    private const val INPUT_TIMEOUT_MS = 10 * 60_000L
    private const val TAG = "MikaPairing"
    /** 概要のない通知グループを使って音とポップアップを抑えます。低優先度チャンネルへ分けず、通知欄で見つけられる状態を保ちます。 */
    private const val QUIET_GROUP = "pairing_quiet"

    /** ペアリングされたチャネルがあることを確認し、使用するチャネルに戻ります (ブート ページにそれに応じたプロンプトが表示され、その設定ページが開きます)。 */
    fun ensureChannel(context: Context): ChannelPlan {
        val nm = context.getSystemService(NotificationManager::class.java)
        val existing = PairingChannel.IDS.mapNotNull { id ->
            nm.getNotificationChannel(id)?.let { ChannelSnapshot(id, it.importance, it.hasUserSetImportance()) }
        }
        val plan = PairingChannel.plan(existing)
        if (plan is ChannelPlan.Create) {
            nm.createNotificationChannel(
                NotificationChannel(plan.id, PhoneSetupCopy.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(false)
                },
            )
        }
        (PairingChannel.IDS + PairingChannel.LEGACY).filter { it != plan.id }.forEach { id ->
            if (nm.getNotificationChannel(id) != null) nm.deleteNotificationChannel(id)
        }
        return plan
    }

    fun show(context: Context, notice: PairingNotice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(context).id
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
        when (notice) {
            is PairingNotice.EnterCode -> {
                val input = RemoteInput.Builder(KEY_CODE).setLabel(PhoneSetupCopy.NOTIFY_INPUT_LABEL).setAllowFreeFormInput(true).build()
                // インテントに結果を入力するには、RemoteInput を変数にする必要があります。 画面がロックされている場合でも、ロックを解除する必要がなく、
                val reply = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, PairingCodeReceiver::class.java).apply { notice.port?.let { putExtra(EXTRA_PORT, it) } },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val action = Notification.Action.Builder(null, PhoneSetupCopy.NOTIFY_ACTION, reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                // を直接入力できます。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                val text = notice.error ?: PhoneSetupCopy.NOTIFY_ENTER_TEXT
                b.setContentTitle(PhoneSetupCopy.NOTIFY_ENTER_TITLE)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    // は、誰も入力しない場合のリマインダーや、間違った入力の後にプロンプ​​トを表示するなど、送信されるたびに呼び出し音とポップアップを表示します。
                    .setOnlyAlertOnce(false)
                    .setTimeoutAfter(INPUT_TIMEOUT_MS)
                    .addAction(action.build())
                if (notice.port != null) b.setSubText(PhoneSetupCopy.NOTIFY_WINDOW_FOUND)
            }
            PairingNotice.Working -> b.setContentTitle(PhoneSetupCopy.NOTIFY_WORKING).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
            PairingNotice.Done -> b.setContentTitle(PhoneSetupCopy.NOTIFY_DONE_TITLE).setContentText(PhoneSetupCopy.NOTIFY_DONE_TEXT)
                .setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(DONE_TIMEOUT_MS)
            is PairingNotice.Problem -> b.setContentTitle(notice.title).setContentText(PhoneSetupCopy.NOTIFY_PROBLEM_TEXT).setAutoCancel(true)
        }
        // 高優先度のチャンネルでも音やポップアップを出さず、通知欄に準備します。
        if (notice is PairingNotice.EnterCode && notice.quiet) b.setOnlyAlertOnce(true).setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        // 許可されていない場合、システムは通知を破棄します。ブート ページは、ペアリング手順で最初にユーザーに許可を求めます。
        runCatching { nm.notify(ID, b.build()) }.onFailure { Log.w(TAG, "ペアリング通知を表示できませんでした", it) }
        // アプリ全体の通知許可とチャンネルの重要度を記録します。メーカー独自の設定までは取得できません。
        if (!nm.areNotificationsEnabled()) Log.w(TAG, "アプリの通知が無効なためペアリング通知を表示できません")
        else nm.getNotificationChannel(channel)?.let { if (it.importance < NotificationManager.IMPORTANCE_HIGH) Log.i(TAG, "ペアリング通知の重要度は ${it.importance} のためポップアップ表示されません") }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
