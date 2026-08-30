package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/** One message, as the app screen needs to see it — sent by either of you. */
data class SendSnapshot(
    val sendId: String,
    val msgId: Int,
    val state: WidgetState,
    /** This phone's clock, at the tap. Feeds the timeout, never the ordering. */
    val at: Long,
    /** False when the other phone sent it. */
    val fromMe: Boolean = true,
    /** The server's clock. Null only while a send of your own is in flight. */
    val serverAt: Long? = null,
)

/**
 * Whether an arriving message takes the bubble from whatever is already there.
 *
 * The bubble is shared, so both phones have to reach the same answer from the
 * same facts — which means the server's clock decides it, and only ever against
 * another server clock. Comparing it to this phone's own `at` would be two
 * clocks pretending to be one, and the two bubbles would disagree exactly when
 * it mattered.
 *
 * The clock is epoch SECONDS, so two sends moments apart routinely carry the
 * same value — and "strictly newer" alone would then leave each phone holding
 * its own message and the two bubbles disagreeing, which is the one outcome this
 * whole design exists to prevent. The id breaks the tie. It is arbitrary, but it
 * is a total order, and both phones hold both ids, so they break it the same way
 * and stay in agreement. That is the only property that matters here.
 *
 * A send of your own that has not had its response yet carries no server
 * timestamp to compare, and yields: the ruling is that the newest message takes
 * the bubble even mid-ladder, and the tile you tapped still finishes its own.
 *
 * One push delivered twice is a no-op: same timestamp, same id, so neither test
 * passes.
 */
fun receivedWins(
    current: SendSnapshot?,
    incomingServerAt: Long,
    incomingSendId: String,
): Boolean = when {
    current == null -> true
    current.serverAt == null -> true
    incomingServerAt != current.serverAt -> incomingServerAt > current.serverAt
    else -> incomingSendId > current.sendId
}

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
        val FROM_ME = booleanPreferencesKey("from_me")
        val SERVER_AT = longPreferencesKey("server_at")
    }

    val flow: Flow<SendSnapshot?> = context.currentSendStore.data.map { prefs ->
        val sendId = prefs[Keys.SEND_ID] ?: return@map null
        val msgId = prefs[Keys.MSG_ID] ?: return@map null
        val at = prefs[Keys.AT] ?: return@map null
        SendSnapshot(
            sendId,
            msgId,
            fromName(prefs[Keys.STATE]),
            at,
            fromMe = prefs[Keys.FROM_ME] ?: true,
            serverAt = prefs[Keys.SERVER_AT],
        )
    }

    suspend fun current(): SendSnapshot? = flow.first()

    /** Replaces whatever was there. A new send is a new subject, not an update. */
    suspend fun start(sendId: String, msgId: Int, now: Long = System.currentTimeMillis()) {
        context.currentSendStore.edit { prefs ->
            prefs[Keys.SEND_ID] = sendId
            prefs[Keys.MSG_ID] = msgId
            prefs[Keys.STATE] = WidgetState.SENDING.name
            prefs[Keys.AT] = now
            // A new send is a new subject: it must not inherit the previous
            // record's provenance or its place in the ordering.
            prefs[Keys.FROM_ME] = true
            prefs.remove(Keys.SERVER_AT)
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

    /**
     * Records the server's timestamp for a send of ours, once the response
     * carries it.
     *
     * Until this lands the record has no place in the ordering at all, and
     * anything arriving takes the bubble — which is the agreed behaviour, not a
     * gap. Guarded on the id like [update], because a later send may already
     * have replaced the record by the time a slow response comes back.
     */
    suspend fun markSentAt(sendId: String, serverAt: Long) {
        context.currentSendStore.edit { prefs ->
            if (prefs[Keys.SEND_ID] != sendId) return@edit
            prefs[Keys.SERVER_AT] = serverAt
        }
    }

    /**
     * Puts a message the other phone sent into the bubble, if it wins.
     *
     * Stored as SEEN because that is precisely what it is: it arrived, and you
     * are looking at it. That one choice also buys the gold artwork, immunity
     * from the twenty-second timeout — SEEN is not awaiting an outcome — and the
     * top of the ladder, so no receipt and no stale push can move it afterwards.
     * A seventh state would have had to be taught to the widget art, the guide
     * and the ladder for something no widget can ever show.
     *
     * The read and the compare happen inside one edit block, so a send of your
     * own cannot land between them and be silently overwritten.
     */
    suspend fun receive(sendId: String, msgId: Int, serverAt: Long) {
        context.currentSendStore.edit { prefs ->
            val currentId = prefs[Keys.SEND_ID]
            val current = if (currentId == null) null else SendSnapshot(
                currentId,
                prefs[Keys.MSG_ID] ?: 0,
                fromName(prefs[Keys.STATE]),
                prefs[Keys.AT] ?: 0L,
                fromMe = prefs[Keys.FROM_ME] ?: true,
                serverAt = prefs[Keys.SERVER_AT],
            )
            if (!receivedWins(current, serverAt, sendId)) return@edit

            prefs[Keys.SEND_ID] = sendId
            prefs[Keys.MSG_ID] = msgId
            prefs[Keys.STATE] = WidgetState.SEEN.name
            prefs[Keys.AT] = System.currentTimeMillis()
            prefs[Keys.FROM_ME] = false
            prefs[Keys.SERVER_AT] = serverAt
        }
    }
}
