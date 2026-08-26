package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.unseenStore by preferencesDataStore(name = "unseen_sends")

/**
 * Sends that arrived on this phone while nobody could have been looking.
 *
 * The receiving side of the ladder. A message that lands while the screen is off
 * or the phone is locked cannot have been seen yet, so its send id waits here
 * until the next unlock, at which point the whole set is reported as `seen`.
 *
 * Deliberately unbounded in age: spec §7.1 stops the sender's *tile* lighting up
 * for something old, but the receipt itself is a record and is always worth
 * reporting. Draining on every unlock is what keeps this small in practice —
 * an entry only survives as long as the phone stays untouched.
 *
 * A separate DataStore file from `love_button` for the same reason as
 * [PendingSends]: disposable state must never share a file with the enrolment,
 * whose loss costs a re-enrolment.
 */
class UnseenSends(private val context: Context) {

    suspend fun remember(sendId: String) {
        context.unseenStore.edit { prefs ->
            prefs[longPreferencesKey(sendId)] = System.currentTimeMillis()
        }
    }

    /**
     * Returns every waiting send id and clears the store in one edit.
     *
     * Read and clear together rather than as two calls: two unlock broadcasts can
     * land close enough to overlap, and a separate read-then-clear would let both
     * see the same ids and report each one twice. The server treats a repeated
     * `seen` as an idempotent no-op, so a double report is harmless — but it is
     * still a wasted round trip on a phone whose battery we already fight for.
     */
    suspend fun drain(): List<String> {
        var drained = emptyList<String>()
        context.unseenStore.edit { prefs ->
            drained = prefs.asMap().keys.map { it.name }
            prefs.clear()
        }
        return drained
    }

    /** Everything currently waiting, without clearing it. For tests. */
    suspend fun peek(): List<String> =
        context.unseenStore.data.first().asMap().keys.map { it.name }
}
