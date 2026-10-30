package com.itmanager.app.core.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * «VPN завода подключён · Отключить»: while the tunnel the app brought up is on, so it can be closed after leaving the
 * app with Home, which the app cannot catch. The button goes straight to Cisco Secure Client (a tap is the user's own
 * action, so Android lets it open another app); AnyConnect then hands back to the app only to let it go.
 */
object VpnNotice {
    private const val CHANNEL = "factory_vpn"
    private const val ID = 4201

    fun show(context: Context) {
        // Android 13 and newer ask the user; before that notifications need no permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN завода", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Пока подключён VPN завода, поднятый приложением"
            },
        )
        val disconnect = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse(AnyConnect.disconnectUri(leaving = true))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("VPN завода подключён")
            .setContentText("Подключило приложение ItManager. Отключите, чтобы вернуть свой VPN.")
            .setOnlyAlertOnce(true)
            .addAction(0, "Отключить", disconnect)
            .setContentIntent(disconnect)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
