package com.omsingh.telepad.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omsingh.telepad.MainActivity
import com.omsingh.telepad.R

/**
 * Foreground service that keeps the Telepad connection alive when the app
 * is backgrounded.
 *
 * Required because Android aggressively kills background processes, which
 * would silently sever the Wi-Fi connection mid-typing. The persistent
 * notification is the OS's required acknowledgement that we keep running.
 *
 * **Tradeoff:** Costs the user a permanent notification in their shade and
 * a small battery hit. That's why this is **opt-in** via the
 * `keepConnectionAlive` preference. Default off — user can enable when they
 * need it (presentations, long typing sessions).
 *
 * **`foregroundServiceType="connectedDevice"`** — required on Android 14+.
 * Telegram chose `dataSync` here historically; `connectedDevice` is the more
 * honest description for our use case.
 */
class TelepadConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectPi = PendingIntent.getService(
            this,
            1,
            Intent(this, TelepadConnectionService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.app_name))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPi)
            .addAction(0, getString(R.string.notification_disconnect), disconnectPi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "telepad_connection"
        private const val NOTIFICATION_ID = 0xC0FFEE  // arbitrary
        const val ACTION_DISCONNECT = "com.omsingh.telepad.DISCONNECT"

        fun start(context: Context) {
            val i = Intent(context, TelepadConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelepadConnectionService::class.java))
        }

        fun ensureNotificationChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }
}


