package com.lovebutton.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lovebutton.app.device.areNotificationsEnabled
import com.lovebutton.app.device.isIgnoringBatteryOptimisations
import com.lovebutton.app.device.isProbablyXiaomi
import com.lovebutton.app.device.openAppSettings
import com.lovebutton.app.device.openBatteryOptimisationSettings
import com.lovebutton.app.device.openMiuiAutostart
import com.lovebutton.app.device.openMiuiBatterySaver

/**
 * The delivery setup checklist.
 *
 * On MIUI three separate mechanisms will each independently stop pushes:
 * Autostart is off by default for sideloaded apps, battery saver defaults to
 * restricted, and unlocked apps get purged under memory pressure. None of this
 * can be fixed in code — the most an app can do is take you straight to each
 * setting and then re-check whether it stuck.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    // Re-read the live state whenever the screen recomposes after returning
    // from a settings page, so a change you just made shows up immediately.
    val notificationsOn = remember(refresh) { areNotificationsEnabled(context) }
    val batteryExempt = remember(refresh) { isIgnoringBatteryOptimisations(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Delivery setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Android will quietly stop delivering messages unless these are set. " +
                "Check them again after a system update.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CheckItem(
            title = "Notifications allowed",
            done = notificationsOn,
            action = "Grant",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Before Android 13 there is no runtime permission to request —
                    // notifications are on unless the user turned them off, so the
                    // only useful action is to open the app's own settings page.
                    openAppSettings(context)
                }
            },
        )

        CheckItem(
            title = "Battery optimisation off",
            done = batteryExempt,
            action = "Open",
            onClick = {
                openBatteryOptimisationSettings(context)
                refresh++
            },
        )

        if (isProbablyXiaomi()) {
            Text(
                "Xiaomi / MIUI",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            // These two cannot be read back — MIUI exposes no API for either, so
            // the app cannot show a tick. You have to confirm them by eye.
            CheckItem(
                title = "Autostart enabled",
                done = null,
                action = "Open",
                onClick = { openMiuiAutostart(context) },
            )
            CheckItem(
                title = "Battery saver: No restrictions",
                done = null,
                action = "Open",
                onClick = { openMiuiBatterySaver(context) },
            )
            CheckItem(
                title = "Locked in recents",
                done = null,
                action = null,
                onClick = {},
                detail = "Open the recent apps view, swipe down on Love Button " +
                    "(or long-press it) and tap the padlock.",
            )
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text("Done")
        }
    }
}

/** `done = null` means the state cannot be read back and must be checked by eye. */
@Composable
private fun CheckItem(
    title: String,
    done: Boolean?,
    action: String?,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val marker = when (done) {
                true -> "✓ "
                false -> "✗ "
                null -> "• "
            }
            Text("$marker$title", style = MaterialTheme.typography.titleSmall)

            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (action != null && done != true) {
                Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(action)
                }
            }
        }
    }
}
