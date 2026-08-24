package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * How long a send waits for a receipt before the tile gives up on it.
 *
 * Spec §7.1: no `delivered` inside this window settles the tile on plain Sent,
 * and receipts arriving afterwards are dropped. A heart lighting up for
 * something sent an hour ago is confusing rather than sweet.
 */
const val PENDING_WINDOW_MS = 20_000L

private val Context.pendingStore by preferencesDataStore(name = "pending_sends")

/**
 * Which widget a send belongs to, so a receipt can find its tile.
 *
 * Written *before* the request leaves, which is the whole point of the app
 * minting the send id: a receipt can arrive before the send response does, and
 * a mapping that only exists afterwards would miss it.
 *
 * Entries are stored as `appWidgetId:timestamp` under the send id. A separate
 * DataStore file from `love_button` keeps enrolment — the thing whose loss
 * costs a re-enrolment — away from disposable correlation state.
 */
class PendingSends(private val context: Context) {

    suspend fun remember(sendId: String, appWidgetId: Int) {
        context.pendingStore.edit { prefs ->
            prefs[stringPreferencesKey(sendId)] = "$appWidgetId:${System.currentTimeMillis()}"
        }
    }

    /** The widget awaiting this send, or null if unknown or expired. */
    suspend fun widgetFor(sendId: String): Int? {
        val raw = context.pendingStore.data.first()[stringPreferencesKey(sendId)] ?: return null
        val parts = raw.split(":")
        val widget = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > PENDING_WINDOW_MS) return null
        return widget
    }

    suspend fun forget(sendId: String) {
        context.pendingStore.edit { prefs -> prefs.remove(stringPreferencesKey(sendId)) }
    }

    /**
     * Drops everything past the window.
     *
     * Without this the store grows forever: every send that never gets a receipt
     * leaves an entry behind, and nothing else would ever remove them.
     */
    suspend fun forgetExpired(now: Long = System.currentTimeMillis()) {
        context.pendingStore.edit { prefs ->
            prefs.asMap().forEach { (key, value) ->
                val at = (value as? String)?.substringAfter(":")?.toLongOrNull() ?: return@forEach
                if (now - at > PENDING_WINDOW_MS) prefs.remove(stringPreferencesKey(key.name))
            }
        }
    }
}
