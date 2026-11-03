package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.wangzan101.mika.sandbox.SandboxService

/**
 * 実行環境の準備中のフォアグラウンド サービス (タイプ dataSync、Android は長期的なバックグラウンド作業のためにフォアグラウンド サービスを必要とします)。
 * は、優先度の低いメッセージ「Mika が準備中です」のみを表示し、準備後に削除します。結果通知は送信されません。準備自体は[RuntimePreparer]にあります。
 */
open class SandboxForeground : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.notif_channel_prepare), NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 後、できるだけ早くフォアグラウンドに入らなければなりません
        startForeground(ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher?.isActive == true) return START_NOT_STICKY
        watcher = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            val progress = launch {
                SandboxState.get(this@SandboxForeground).status.collect { s ->
                    if (s is SandboxStatus.Installing) nm.notify(ID, notification(s.percent))
                }
            }
            RuntimePreparer.current?.join()
            progress.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(percent: Int?): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.notif_preparing))
        .apply { if (percent != null) setProgress(100, percent, false) }
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL = "runtime_prepare"
        private const val ID = 20

        /** はフロントデスクに入り、[RuntimePreparer] の現在の準備が終了するのを待ちます。 [RuntimePreparer] によってのみ呼び出されます。 */
        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, SandboxService::class.java))
        }
    }
}
