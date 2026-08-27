package com.lovebutton.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lovebutton.app.data.DeliverySetup
import com.lovebutton.app.device.areNotificationsEnabled
import com.lovebutton.app.device.isIgnoringBatteryOptimisations
import com.lovebutton.app.device.isNotificationPermissionPermanentlyDenied
import com.lovebutton.app.device.isProbablyXiaomi
import com.lovebutton.app.device.openAppSettings
import com.lovebutton.app.device.openBatteryOptimisationSettings
import com.lovebutton.app.device.openMiuiAutostart
import com.lovebutton.app.device.openMiuiBatterySaver
import com.lovebutton.app.device.openNotificationSettings
import kotlinx.coroutines.launch

/**
 * Whether delivery is set up as far as this phone can tell.
 *
 * The two Android checks are read live. The MIUI ones cannot be read at all, so
 * on a Xiaomi they count only once they have been confirmed by hand — see
 * [DeliverySetup]. Without that clause the home screen would stop nagging while
 * Autostart was still off, which is the single likeliest reason a message never
 * arrives, and the app would look ready while silently dropping everything.
 */
fun deliveryReady(context: Context, miuiConfirmed: Boolean): Boolean =
    areNotificationsEnabled(context) &&
        isIgnoringBatteryOptimisations(context) &&
        (!isProbablyXiaomi() || miuiConfirmed)

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { DeliverySetup(context) }
    val miuiConfirmed by setup.miuiConfirmed.collectAsState(initial = false)
    var refresh by remember { mutableStateOf(0) }

    // Re-read the live state whenever the screen recomposes after returning
    // from a settings page, so a change you just made shows up immediately.
    val notificationsOn = remember(refresh) { areNotificationsEnabled(context) }
    val batteryExempt = remember(refresh) { isIgnoringBatteryOptimisations(context) }

    // Re-read on every return to the foreground. Sending the user to a settings
    // page yields no result callback, so without this the ticks stay stale until
    // something else happens to recompose — and spec 8 wants the check repeated
    // on every launch anyway, so a HyperOS update that silently undoes the setup
    // is caught here rather than by a message that never arrives.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        // Once a denial is USER_FIXED, launch() shows no dialog and returns denied
        // immediately — so without this the button silently does nothing in exactly
        // the state where the user needs it. Send them where they can still say yes.
        if (!granted && isNotificationPermissionPermanentlyDenied(context)) {
            openNotificationSettings(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Sticker.Ground)
            // This screen had no inset handling at all, so under targetSdk 36 its
            // heading sat behind the status bar. Outside the scroll, so the list
            // stops at the bars rather than running under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Delivery setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Android will quietly stop delivering messages unless these are set. " +
                "Check them again after a system update.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.size(2.dp))

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
                modifier = Modifier.padding(top = 10.dp),
            )

            // These three cannot be read back — MIUI exposes no API for any of
            // them, so the app cannot show a tick. You confirm them by eye below.
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

            ConfirmItem(
                confirmed = miuiConfirmed,
                onToggle = { scope.launch { setup.setMiuiConfirmed(!miuiConfirmed) } },
            )
        }

        Spacer(Modifier.size(6.dp))

        StickerButton(
            label = "Done",
            onClick = onDone,
            fill = Sticker.Mint,
            radius = 14,
            modifier = Modifier.fillMaxWidth(),
        )
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
    // Colour carries the state before the marker does, the same way the four
    // message stickers are told apart by hue before anyone reads them.
    val fill = when (done) {
        true -> Sticker.Mint
        false -> Sticker.Blossom
        null -> Sticker.Surface
    }
    StickerBox(fill = fill, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Marker(done)
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }

            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, start = 30.dp),
                )
            }

            if (action != null && done != true) {
                StickerButton(
                    label = action,
                    onClick = onClick,
                    fill = Sticker.Surface,
                    radius = 11,
                    modifier = Modifier.padding(top = 10.dp, start = 30.dp).width(104.dp),
                )
            }
        }
    }
}

/** A tick, a cross, or a question mark for the ones nothing can read. */
@Composable
private fun Marker(done: Boolean?) {
    val glyph = when (done) {
        true -> "✓"
        false -> "✗"
        null -> "?"
    }
    Box(
        Modifier.size(20.dp).background(Sticker.Ink, RoundedCornerShapeAll),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.labelMedium,
            color = Sticker.Surface,
            textAlign = TextAlign.Center,
        )
    }
}

private val RoundedCornerShapeAll = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

/**
 * The one check the user ticks themselves.
 *
 * Three MIUI settings above it report nothing back, so this is what turns the
 * home screen's nudge off. It is deliberately a claim rather than a reading, and
 * says so: pretending the app verified them would be worse than admitting it
 * cannot.
 */
@Composable
private fun ConfirmItem(confirmed: Boolean, onToggle: () -> Unit) {
    StickerBox(
        fill = if (confirmed) Sticker.Mint else Sticker.Butter,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).background(
                    if (confirmed) Sticker.Ink else Color.Transparent,
                    RoundedCornerShapeAll,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (confirmed) "✓" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Sticker.Surface,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column {
                Text(
                    if (confirmed) "The three above are done" else "Tap when you have done all three",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "The phone cannot check these, so it takes your word for it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
