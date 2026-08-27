package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one part of delivery setup the phone cannot check for itself.
 *
 * MIUI's Autostart, its battery saver policy and locking an app in recents each
 * independently stop pushes, and MIUI exposes no API to read any of them — the
 * checklist shows them with no tick because there is nothing to read. So the
 * only way the app can know whether setup is finished is to be told.
 *
 * Its own store rather than a key in [Prefs], because `clearEnrolment` calls
 * `prefs.clear()`: these settings belong to the phone, not to the account, and
 * they survive signing out exactly as they survive on the device itself.
 */
private val Context.deliverySetupStore by preferencesDataStore(name = "delivery_setup")

class DeliverySetup(private val context: Context) {

    private object Keys {
        val MIUI_CONFIRMED = booleanPreferencesKey("miui_confirmed")
    }

    /** Whether the MIUI steps have been confirmed done by hand. */
    val miuiConfirmed: Flow<Boolean> =
        context.deliverySetupStore.data.map { it[Keys.MIUI_CONFIRMED] ?: false }

    suspend fun setMiuiConfirmed(confirmed: Boolean) {
        context.deliverySetupStore.edit { it[Keys.MIUI_CONFIRMED] = confirmed }
    }
}
