package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.fromName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// `internal` rather than private only so tests can reset it: DataStore's
// delegate caches one instance per class-loader, so a single-record store
// survives from one test method into the next.
internal val Context.currentSendStore by preferencesDataStore(name = "current_send")

/** One send, as the app screen needs to see it. */
data class SendSnapshot(
    val sendId: String,
    val msgId: Int,
    val state: WidgetState,
    val at: Long,
)

/**
 * The single most recent send, whatever started it.
 *
 * The app screen has no widget id to be addressed by, so `setWidgetState` can
 * never reach it — an app-originated send passes INVALID_APPWIDGET_ID and that
 * function returns immediately. This store is the channel that replaces it.
 *
 * Persisted rather than held in memory because MIUI kills this process as a
 * matter of routine, and because the screen must show how the last send ended
 * on a cold open. One mechanism serves both; an in-memory StateFlow would need
 * a second one for the cold open anyway.
 *
 * Exactly one record. This is not a log, and the decision that the server keeps
 * no history (spec §5.1) is not reopened by it.
 */
class CurrentSend(private val context: Context) {

    private object Keys {
        val SEND_ID = stringPreferencesKey("send_id")
        val MSG_ID = intPreferencesKey("msg_id")
        val STATE = stringPreferencesKey("state")
        val AT = longPreferencesKey("at")
    }

    val flow: Flow<SendSnapshot?> = context.currentSendStore.data.map { prefs ->
        val sendId = prefs[Keys.SEND_ID] ?: return@map null
        val msgId = prefs[Keys.MSG_ID] ?: return@map null
        val at = prefs[Keys.AT] ?: return@map null
        SendSnapshot(sendId, msgId, fromName(prefs[Keys.STATE]), at)
    }

    suspend fun current(): SendSnapshot? = flow.first()

    /** Replaces whatever was there. A new send is a new subject, not an update. */
    suspend fun start(sendId: String, msgId: Int, now: Long = System.currentTimeMillis()) {
        context.currentSendStore.edit { prefs ->
            prefs[Keys.SEND_ID] = sendId
            prefs[Keys.MSG_ID] = msgId
            prefs[Keys.STATE] = WidgetState.SENDING.name
            prefs[Keys.AT] = now
        }
    }

    /**
     * Advances the stored send, and only if it IS the stored send.
     *
     * Receipts arrive by push and can land after the user has sent something
     * else. Without this guard an old `seen` would light the focal area for a
     * message that is no longer on screen — the read and the compare happen
     * inside one edit block so a concurrent writer cannot land between them.
     */
    suspend fun update(sendId: String, state: WidgetState) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] == sendId) {
                prefs[Keys.STATE] = state.name
            }
        }
    }
}
