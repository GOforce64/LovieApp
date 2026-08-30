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

/**
 * Whether a send tapped at [at] has run out of time.
 *
 * One definition, because three places have to agree on it: the focal area, which
 * greys out a send nothing ever came back for; the widget's timeout, which
 * unsticks a tile; and the send itself, which abandons a request queued behind a
 * connection that took too long to arrive. Three separate `now - at > …`
 * comparisons would eventually disagree by a millisecond in the one direction
 * that matters.
 *
 * A negative age is never expired. The clock can go backwards — NTP correction,
 * or the user changing the time — and treating that as "long ago" would grey out
 * a send that had only just been tapped.
 */
fun windowClosed(at: Long, now: Long = System.currentTimeMillis()): Boolean =
    now - at >= PENDING_WINDOW_MS

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

    /**
     * The widget awaiting this send, or null if unknown or expired.
     *
     * The expiry is why this must never be used to decide whether to return a
     * tile to idle after waiting out the window: by then every entry is expired
     * by construction and the answer is always null. `SendWorker.settle` asks
     * the tile what it is displaying instead.
     *
     * @param now injectable only so the expiry boundary can be tested; callers
     *   in production always want the real clock.
     */
    suspend fun widgetFor(sendId: String, now: Long = System.currentTimeMillis()): Int? {
        val raw = context.pendingStore.data.first()[stringPreferencesKey(sendId)] ?: return null
        val parts = raw.split(":")
        val widget = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        if (now - at > PENDING_WINDOW_MS) return null
        return widget
    }

    /**
     * How much of the pending window this send has left, or 0 if it has none.
     *
     * The tile showing `delivered` is waiting for a `seen`, so it should stay lit
     * for exactly as long as a `seen` could still be matched — which is measured
     * from the SEND, not from when `delivered` happened to arrive. Holding a flat
     * window from arrival instead would leave the tile lit well past the point
     * where anything could still update it.
     */
    suspend fun remainingMs(sendId: String, now: Long = System.currentTimeMillis()): Long {
        val raw = context.pendingStore.data.first()[stringPreferencesKey(sendId)] ?: return 0L
        val at = raw.substringAfter(":").toLongOrNull() ?: return 0L
        return (PENDING_WINDOW_MS - (now - at)).coerceAtLeast(0L)
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
