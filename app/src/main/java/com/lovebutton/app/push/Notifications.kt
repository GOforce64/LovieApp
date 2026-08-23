package com.lovebutton.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lovebutton.app.MainActivity
import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.messageForId
import java.util.concurrent.atomic.AtomicInteger

private val notificationCounter = AtomicInteger(1)

/**
 * Creates the temporary development channel.
 *
 * Android freezes a channel's sound at creation and will not let you change it
 * afterwards. The four real sounds are not chosen until milestone 4, so this uses
 * a throwaway channel id that milestone 4 deletes — creating `msg_1` now would
 * permanently weld the default sound to it.
 */
fun ensureChannel(context: Context) {
    val channel = NotificationChannel(
        DEV_CHANNEL_ID,
        "Messages (temporary)",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Provisional channel used until the real sounds are chosen."
        enableVibration(true)
    }

    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

/**
 * Posts one notification for a received message.
 *
 * Each gets a unique id so rapid sends stack rather than replacing one another —
 * four taps should feel like four messages, not one that keeps changing.
 */
fun postMessageNotification(context: Context, msgId: Int, fromName: String) {
    val message = messageForId(msgId)
    val text = message?.text ?: "New message"
    val channelId = message?.channelId ?: DEV_CHANNEL_ID

    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(fromName)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openApp)
        .build()

    // On Android 13+ this silently does nothing without POST_NOTIFICATIONS.
    // areNotificationsEnabled() is what the Setup screen checks in Task 8.
    NotificationManagerCompat.from(context)
        .notify(notificationCounter.getAndIncrement(), notification)
}
