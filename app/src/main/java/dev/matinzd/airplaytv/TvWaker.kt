package dev.matinzd.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Wakes the Streamer when an iPhone starts an AirPlay session. Turning the
 * Streamer's display on makes Google TV send HDMI-CEC "One Touch Play", which
 * powers on the TV and switches it to the Streamer's input.
 */
object TvWaker {
    private const val TAG = "TvWaker"
    private const val CHANNEL = "airplay_session"
    private const val NOTIFICATION_ID = 2

    fun wake(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            Log.i(TAG, "device is in standby, waking it")
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "airplaytv:wake",
            ).acquire(10_000)
        }

        val intent = playbackIntent(context)
        // Starting an activity from the background needs the "display over other
        // apps" app-op (granted by scripts/setup-device.sh).
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(intent)
        } else {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; falling back to a full-screen notification")
            showFullScreenNotification(context, intent)
        }
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun playbackIntent(context: Context) = Intent(context, PlaybackActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun showFullScreenNotification(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirPlay sessions", NotificationManager.IMPORTANCE_HIGH),
        )
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("AirPlay")
            .setContentText("Incoming AirPlay session")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
