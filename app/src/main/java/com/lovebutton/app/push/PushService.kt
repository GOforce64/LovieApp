package com.lovebutton.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lovebutton.app.work.ReceiptWorker
import com.lovebutton.app.work.RegisterTokenWorker

/**
 * Receives data-only pushes.
 *
 * Because the Worker never sends a `notification` block, every message lands here
 * rather than being rendered by the system tray — which is what lets the app
 * choose the channel, and therefore the sound.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // FCM tokens rotate. If the server still has the old one, pushes go
        // nowhere silently, so this has to be reliable rather than best-effort.
        RegisterTokenWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        when (data["type"]) {
            "msg" -> {
                val msgId = data["msg_id"]?.toIntOrNull() ?: return
                val fromName = data["from_name"] ?: "Someone"
                val sendId = data["send_id"]
                postMessageNotification(applicationContext, msgId, fromName, sendId)
                // After posting, not before: the receipt says "it arrived and she
                // can see it", and a receipt for a notification that failed to
                // post would be a lie.
                if (sendId != null) {
                    ReceiptWorker.enqueue(applicationContext, sendId, "delivered")
                }
            }
            // "receipt" arrives in a later plan and must never post a notification.
            else -> Unit
        }
    }
}
