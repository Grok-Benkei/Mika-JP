package com.wangzan101.mika.settings

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ChatGPT を使用したログイン中のフォアグラウンド サービス: ミカはバックグラウンドに退き、ユーザーはブラウザーでログインするときにプロセスをサポートし、システムはプロセスをフリーズしません。
 * ローカル コールバックが呼び出された場合にのみ誰かが呼び出しに応答し、トークンの変更は遅延されません。このログインを「キャンセル」できる静かな通知をハングするだけです。
 * は単独で処理を行いません。ログインは [ChatGptSignIns] で実行され、その実行はここで監視されます。ログインが完了すると（成功、失敗、キャンセル、タイムアウト）すぐに停止します。
 */
class ChatGptSignInService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signIns get() = (application as MikaApp).chatGptSignIn
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        // ForegroundService を開始した後は、たとえ停止しそうになったとしても、できるだけ早くフォアグラウンドに入る必要があります。フォアグラウンドに入ることができない場合は、停止して通常どおりログインします (この保護層がない場合のみ)。
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w(TAG, "sign-in service could not enter the foreground", it) }.isSuccess
        if (intent?.action == ACTION_CANCEL) signIns.cancel()
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch?.cancel()
        watch = scope.launch {
            signIns.running.first { !it }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // プロセスが失われ、ログインが失われている場合は、再構築する必要はありません。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ChatGptSignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(signIns.noticeTitle ?: tr(R.string.chatgpt_signin_notif_title))
            .setContentText(signIns.noticeBody ?: tr(R.string.chatgpt_signin_notif_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "MikaChatGpt"
        private const val ACTION_CANCEL = "com.wangzan101.mika.chatgpt.CANCEL_SIGN_IN"
        /** タスクとコンピューター接続の通知は時間差で行われます。 */
        private const val NOTIFICATION_ID = 30_002

        /** ログインの開始時に呼び出されます (ユーザーがログインをクリックしたばかりで、Mika がフォアグラウンドにあり、フロントエンド サービスを開くことができます)。開けない場合はスローされ、呼び出し側が記録します。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChatGptSignInService::class.java))
        }
    }
}
