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
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.InputEvent

/**
 * Keeps the connection alive while the app is in the background, if the person asked for that
 * (it is off by default: it costs a notification and a little battery).
 *
 * Android freezes or kills background apps, which would quietly end the connection in the
 * middle of a presentation. A foreground service is the platform's way of saying "this is doing
 * something the person asked for", and the notification is how it stays honest about it.
 * The notification doubles as a small remote: play or pause, volume, and disconnect.
 *
 * `connectedDevice` is the honest service type for talking to a PC (Android 14 requires one).
 */
class TelepadConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = ConnectionManager.getInstance(application)

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                manager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> manager.dispatch(InputEvent.MediaCommand(InputEvent.MediaAction.PLAY_PAUSE))
            ACTION_VOLUME_UP -> manager.dispatch(InputEvent.VolumeCommand(InputEvent.VolumeDirection.UP))
            ACTION_VOLUME_DOWN -> manager.dispatch(InputEvent.VolumeCommand(InputEvent.VolumeDirection.DOWN))
        }

        ensureNotificationChannel(this)
        val name = (manager.connectionState.value as? ConnectionState.Connected)?.deviceName ?: getString(R.string.app_name)
        val notification = buildNotification(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // If Android ends the process the connection is gone, so a restarted service would only
        // show a notification about nothing.
        return START_NOT_STICKY
    }

    private fun buildNotification(deviceName: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        fun action(requestCode: Int, action: String, label: Int) = NotificationCompat.Action.Builder(
            0,
            getString(label),
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, TelepadConnectionService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, deviceName))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(action(1, ACTION_PLAY_PAUSE, R.string.notification_play_pause))
            .addAction(action(2, ACTION_VOLUME_DOWN, R.string.notification_volume_down))
            .addAction(action(3, ACTION_VOLUME_UP, R.string.notification_volume_up))
            .addAction(action(4, ACTION_DISCONNECT, R.string.notification_disconnect))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "telepad_connection"
        private const val NOTIFICATION_ID = 0xC0FFEE
        const val ACTION_DISCONNECT = "com.omsingh.telepad.DISCONNECT"
        const val ACTION_PLAY_PAUSE = "com.omsingh.telepad.PLAY_PAUSE"
        const val ACTION_VOLUME_UP = "com.omsingh.telepad.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.omsingh.telepad.VOLUME_DOWN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TelepadConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelepadConnectionService::class.java))
        }

        fun ensureNotificationChannel(ctx: Context) {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
