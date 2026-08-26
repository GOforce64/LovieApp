package com.lovebutton.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lovebutton.app.data.UnseenSends
import com.lovebutton.app.work.ReceiptWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns "she picked her phone up" into a `seen` receipt.
 *
 * This is the whole reason seen no longer needs a notification tap: a message
 * she glanced at on the lock screen and then swiped away was still read, and
 * making her open the app to prove it reports the wrong thing.
 *
 * Registered from code, never from the manifest. `ACTION_USER_PRESENT` is not on
 * Android's implicit-broadcast exemption list, so on targetSdk 26+ a
 * manifest-declared receiver for it is simply never invoked — it would look
 * correct and do nothing.
 */
class UnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // ACTION_SCREEN_ON arrives before any unlock, and on a phone with no
        // keyguard at all it is the only signal there will be. Asking the device
        // rather than trusting the action keeps both paths honest.
        if (!couldBeLookingNow(context)) return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                UnseenSends(app).drain().forEach { sendId ->
                    ReceiptWorker.enqueue(app, sendId, "seen")
                }
            } finally {
                // Without this the process may be reclaimed mid-drain and the
                // receipts never enqueued.
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * Listens for as long as this process lives.
         *
         * Called from [com.lovebutton.app.LoveButtonApp], which runs whenever any
         * component does — including `PushService`. The push that delivers the
         * message is therefore what brings this listener up, which is exactly the
         * window that matters: she unlocks seconds after the buzz.
         *
         * If MIUI reclaims the process before she unlocks, no `seen` is reported.
         * That degrades to silence rather than to a wrong answer, and §8's
         * autostart and battery setup is what keeps it rare.
         */
        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                context,
                UnlockReceiver(),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
