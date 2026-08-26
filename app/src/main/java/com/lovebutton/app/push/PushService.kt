package com.lovebutton.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.data.PendingSends
import com.lovebutton.app.data.UnseenSends
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.advanceWidgetState
import com.lovebutton.app.widget.clearWidgetStateIf
import com.lovebutton.app.widget.holdMillis
import com.lovebutton.app.work.ReceiptWorker
import com.lovebutton.app.work.RegisterTokenWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
                postMessageNotification(applicationContext, msgId, fromName)
                // After posting, not before: the receipt says "it arrived and she
                // can see it", and a receipt for a notification that failed to
                // post would be a lie.
                if (sendId != null) {
                    ReceiptWorker.enqueue(applicationContext, sendId, "delivered")
                    reportOrRememberSeen(sendId)
                }
            }
            "receipt" -> {
                // Never a notification. A phone that buzzes when she reads a
                // message is a phone nobody wants (spec §6.4).
                val sendId = data["send_id"] ?: return
                val state = when (data["state"]) {
                    "delivered" -> WidgetState.DELIVERED
                    "seen" -> WidgetState.SEEN
                    else -> return
                }
                CoroutineScope(Dispatchers.Default).launch {
                    // Before the widget lookup, deliberately: the widget mapping
                    // expires after 20 seconds, but the app screen keeps the last
                    // outcome (spec §4.3). Returning early on an expired widget
                    // must not also skip the app.
                    CurrentSend(applicationContext).update(sendId, state)

                    val pending = PendingSends(applicationContext)
                    // Null means the window expired or this app never sent it.
                    // Dropping it silently is the spec's answer: a tile lighting
                    // up for something sent an hour ago is confusing.
                    val appWidgetId = pending.widgetFor(sendId) ?: return@launch

                    advanceWidgetState(applicationContext, appWidgetId, state)
                    // `delivered` deliberately does not forget the entry: `seen`
                    // may still arrive within the window and needs the mapping.
                    if (state == WidgetState.SEEN) pending.forget(sendId)

                    // DELIVERED is still waiting for a `seen`, so it holds for
                    // whatever is left of the window rather than a flat duration
                    // from now — see PendingSends.remainingMs. SEEN is terminal and
                    // uses its own short hold.
                    val hold = when (state) {
                        WidgetState.DELIVERED -> pending.remainingMs(sendId)
                        else -> state.holdMillis ?: 0L
                    }
                    delay(hold)
                    clearWidgetStateIf(applicationContext, appWidgetId, state)
                }
            }
            else -> Unit
        }
    }

    /**
     * Decides, at the moment the notification lands, whether it has been seen.
     *
     * Seen no longer means "she tapped it" — it means she looked at the screen.
     * If the phone is awake and unlocked she is already looking at it, so report
     * immediately; otherwise the message waits for [UnlockReceiver] to report it
     * on the next unlock.
     *
     * `runBlocking` rather than a launched coroutine: this method runs on FCM's
     * own background thread, and the wakelock behind `onMessageReceived` ends
     * when it returns. A fire-and-forget write could lose the id to process death
     * in exactly the case this exists for — a phone that is asleep.
     */
    private fun reportOrRememberSeen(sendId: String) {
        if (couldBeLookingNow(applicationContext)) {
            ReceiptWorker.enqueue(applicationContext, sendId, "seen")
        } else {
            runBlocking { UnseenSends(applicationContext).remember(sendId) }
        }
    }
}
