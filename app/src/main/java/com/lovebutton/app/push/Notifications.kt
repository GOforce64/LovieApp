package com.lovebutton.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lovebutton.app.MainActivity
import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import java.util.concurrent.atomic.AtomicInteger

private val notificationCounter = AtomicInteger(1)

/**
 * Builds a channel's sound URI from the resource NAME, never its numeric id.
 *
 * Resource ids are not stable across builds: adding resources renumbers them.
 * A channel stores whatever URI it was created with and can never be changed,
 * so an id-based URI silently stops resolving the moment the numbering shifts —
 * and Android's response to an unresolvable sound is no sound at all, with no
 * error anywhere. This form survives any renumbering.
 *
 * The caller passes an entry name derived from a compile-checked R.raw constant,
 * so a missing file is still caught at build time rather than becoming a typo.
 */
internal fun soundUriString(packageName: String, entryName: String): String =
    "android.resource://$packageName/raw/$entryName"

/**
 * Creates the four real channels and deletes the retired development one.
 *
 * Creating a channel that already exists is a no-op, so this is safe on every
 * launch. Deleting `dev_buzz_v1` is safe for the opposite reason: it was created
 * as a throwaway precisely so that the `msg_N` ids would still be unburnt when
 * the sounds were finalised.
 *
 * A channel's sound is frozen at creation and cannot be changed afterwards
 * (spec 6.3). Changing one later means deleting the channel, which resets her
 * notification settings visibly. These four are permanent.
 */
fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)

    manager.deleteNotificationChannel(DEV_CHANNEL_ID)

    val attributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

    MESSAGES.forEach { message ->
        val channel = NotificationChannel(
            message.channelId,
            message.text,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Sound for \"${message.text}\""
            enableVibration(true)
            setSound(
                Uri.parse(
                    soundUriString(
                        context.packageName,
                        context.resources.getResourceEntryName(message.soundRes),
                    )
                ),
                attributes,
            )
        }
        manager.createNotificationChannel(channel)
    }
}

/** Extra carrying the send id from a notification tap into MainActivity. */
const val EXTRA_SEND_ID = "send_id"

/**
 * Posts one notification for a received message.
 *
 * Each gets a unique id so rapid sends stack rather than replacing one another —
 * four taps should feel like four messages, not one that keeps changing.
 */
fun postMessageNotification(
    context: Context,
    msgId: Int,
    fromName: String,
    sendId: String? = null,
) {
    val message = messageForId(msgId)
    val text = message?.text ?: "New message"
    val channelId = message?.channelId ?: DEV_CHANNEL_ID

    val openApp = PendingIntent.getActivity(
        context,
        // A per-send request code: with a constant one, FLAG_UPDATE_CURRENT would
        // rewrite every earlier notification's intent to carry the newest send id,
        // so tapping an older notification would report "seen" for the wrong send.
        sendId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SEND_ID, sendId),
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
