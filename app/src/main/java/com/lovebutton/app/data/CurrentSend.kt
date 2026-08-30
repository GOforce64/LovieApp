package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.advancesTo
import com.lovebutton.app.widget.fromName
import com.lovebutton.app.widget.isAwaitingOutcome
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
 * What the screen should show, which is not always what was stored.
 *
 * A send can hang in two ways, and both used to read as success. This phone
 * never got online, so the request was still queued behind a connection that had
 * not arrived — the record sat on SENDING. Or her phone never came online to
 * acknowledge it — the record sat on SENT. Either way the focal area said
 * "traveling in the interwebs" indefinitely, on this open and every one after.
 *
 * Derived on read rather than written by a timer at the twenty-second mark, for
 * two reasons. MIUI kills this process as a matter of routine, so a write that
 * has to survive twenty seconds often will not happen at all — the same trap
 * spec §7.1 describes for the tile's own guard. And deriving it fixes the cold
 * open for free: a send from an hour ago that nothing ever came back for is grey
 * the moment the screen is opened, rather than still hopeful.
 *
 * Once grey it stays grey. The next tap replaces the record outright, which is
 * the only thing that starts a new story.
 */
fun SendSnapshot.displayState(now: Long = System.currentTimeMillis()): WidgetState =
    if (state.isAwaitingOutcome && windowClosed(at, now)) WidgetState.FAILED else state

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
     * Advances the stored send, and only if it IS the stored send, and only
     * forward.
     *
     * Two guards, for two different races. The id check: receipts arrive by push
     * and can land after the user has sent something else, and without it an old
     * `seen` would light the focal area for a message that is no longer on
     * screen. The rank check: the two receipts for one send are reported
     * concurrently and arrive in either order, so a `delivered` trailing a
     * `seen` must not drag the ladder back down — see [advancesTo].
     *
     * Both the read and the compare happen inside one edit block, so a
     * concurrent writer cannot land between them.
     */
    suspend fun update(sendId: String, state: WidgetState) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] != sendId) return@edit
            if (fromName(prefs[Keys.STATE]).advancesTo(state)) {
                prefs[Keys.STATE] = state.name
            }
        }
    }
}
