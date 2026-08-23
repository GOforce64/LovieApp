package com.lovebutton.app.device

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
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

/**
 * Opens this app's notification settings.
 *
 * The only route left once a denial is USER_FIXED: at that point Android will
 * never show the permission dialog again, so asking is a silent no-op and the
 * system settings page is the sole place the user can still say yes.
 */
fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    startOrFallBack(context, intent)
}

/**
 * Whether the notification permission can no longer be asked for.
 *
 * Only meaningful **after** a request attempt. `shouldShowRequestPermissionRationale`
 * is false both before the first ask and after a permanent denial, so it cannot
 * tell those apart on its own — but once a request has just returned "denied",
 * false means no dialog was shown and none ever will be again.
 */
fun isNotificationPermissionPermanentlyDenied(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = context.findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}

/** Compose hands out a ContextWrapper, not the Activity the permission API needs. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
