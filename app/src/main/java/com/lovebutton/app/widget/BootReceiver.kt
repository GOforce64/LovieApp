package com.lovebutton.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Redraws every widget after a reboot.
 *
 * Glance only draws when something runs its receiver. After a reboot the host
 * recreates the tiles but nothing re-renders them, so they sit on the loading
 * layout indefinitely — and a tap on an unrendered tile falls through to the
 * host's default, which opens the app instead of sending. Opening the app is
 * what used to fix it, because that finally gave the widgets a chance to draw.
 *
 * On MIUI this receiver only fires if Autostart is enabled for the app, which
 * the Delivery setup screen already asks for. That is the same permission the
 * push path depends on, so there is nothing new to grant.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // updateAll suspends, and onReceive must not block. goAsync keeps the
        // broadcast alive while the redraws happen off the main thread.
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                LoveWidget().updateAll(app)
                ThinkingWidget().updateAll(app)
                MissWidget().updateAll(app)
                CallWidget().updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
