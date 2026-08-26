package com.lovebutton.app.push

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Whether someone could actually be looking at the screen right now.
 *
 * Both halves are needed and neither is sufficient. An interactive screen that
 * is still behind the keyguard means the phone woke for the notification, not
 * that anyone read it; an unlocked phone with the screen off is one in a pocket
 * that happens not to have re-locked yet.
 */
fun couldBeLookingNow(interactive: Boolean, keyguardLocked: Boolean): Boolean =
    interactive && !keyguardLocked

/**
 * [couldBeLookingNow] asked of the live device.
 *
 * Returns false when either service is unavailable rather than guessing: a
 * missing answer must never be reported as "she read it".
 */
fun couldBeLookingNow(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
    return couldBeLookingNow(power.isInteractive, keyguard.isKeyguardLocked)
}
