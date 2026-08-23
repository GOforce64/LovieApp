package com.lovebutton.app.device

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

fun isIgnoringBatteryOptimisations(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java)
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

fun areNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

fun isProbablyXiaomi(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

/**
 * Opens an OEM settings screen, falling back to this app's own settings page.
 *
 * MIUI's component names differ between versions and are not part of any public
 * API — they throw ActivityNotFoundException on builds that renamed or removed
 * them. A missing OEM activity must never crash the app, so every one of these
 * goes through here.
 */
private fun startOrFallBack(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        openAppSettings(context)
    } catch (e: SecurityException) {
        // Some MIUI builds export the activity but refuse external launches.
        openAppSettings(context)
    }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** Standard Android — asks to exempt the app from Doze battery optimisation. */
fun openBatteryOptimisationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    startOrFallBack(context, intent)
}

/** MIUI Autostart — off by default for sideloaded apps, which silently kills FCM. */
fun openMiuiAutostart(context: Context) {
    val intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    )
    startOrFallBack(context, intent)
}

/** MIUI battery saver — must be set to "No restrictions" for this app. */
fun openMiuiBatterySaver(context: Context) {
    val intent = Intent().setComponent(
        ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        )
    ).putExtra("package_name", context.packageName)
        .putExtra("package_label", "Love Button")
    startOrFallBack(context, intent)
}
