package com.lovebutton.app

import android.app.Application
import com.lovebutton.app.push.ensureChannel

class LoveButtonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Creating a channel that already exists is a no-op, so this is safe on
        // every launch and guarantees the channel exists before the first push.
        ensureChannel(this)
    }
}
