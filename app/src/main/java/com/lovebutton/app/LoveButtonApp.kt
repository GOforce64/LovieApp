package com.lovebutton.app

import android.app.Application
import com.lovebutton.app.push.UnlockReceiver
import com.lovebutton.app.push.ensureChannels

class LoveButtonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Creating a channel that already exists is a no-op, so this is safe on
        // every launch and guarantees the channels exist before the first push.
        ensureChannels(this)
        // Must be registered in code rather than the manifest — see UnlockReceiver.
        // Doing it here means any component starting the process (a push above
        // all) also starts listening for the unlock that follows it.
        UnlockReceiver.register(this)
    }
}
