package com.wangzan101.mika.settings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.wangzan101.mika.R
import com.wangzan101.mika.core.security.ModelDestination
import com.wangzan101.mika.i18n.tr

/** モデル宛先が変わったことを通知する。本文は名前とホストだけで、鍵は出さない。 */
object ModelEndpointNotifier {
    private const val CHANNEL = "mika-model-destination"
    private const val ID = 44

    fun show(context: Context, destination: ModelDestination) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr(R.string.model_destination_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val scheme = if (destination.secure) "HTTPS" else "HTTP"
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.model_destination_title))
            .setContentText(tr(R.string.model_destination_body, destination.label, scheme))
            .setAutoCancel(true)
            .build()
        nm.notify(ID, notification)
    }
}
