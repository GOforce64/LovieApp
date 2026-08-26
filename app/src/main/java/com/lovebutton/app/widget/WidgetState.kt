package com.lovebutton.app.widget

import com.lovebutton.app.data.PENDING_WINDOW_MS

/**
 * What a widget is currently showing.
 *
 * Spec 7.1 defines six states. DELIVERED and SEEN arrive from the server's
 * receipts, which is why they can only be produced once a receipt lands.
 */
enum class WidgetState { IDLE, SENDING, SENT, FAILED, DELIVERED, SEEN }

/**
 * How long a state is displayed before falling back to IDLE, or null if it is
 * not time-limited.
 *
 * SENDING deliberately has no duration: it ends when the request ends. A timeout
 * here would race the worker and could clear a tile that is still mid-send.
 */
val WidgetState.holdMillis: Long?
    get() = when (this) {
        WidgetState.IDLE, WidgetState.SENDING -> null
        // Held for the whole pending window: a receipt may still arrive, and
        // dropping to idle sooner would hide a delivered that was on its way.
        WidgetState.SENT -> PENDING_WINDOW_MS
        WidgetState.FAILED -> 3_000L
        // DELIVERED waits out the same window as SENT, for the same reason: a
        // `seen` can arrive right up until the window closes, and a four-second
        // hold made the tile go dark and then light up again a moment later when
        // she picked her phone up. One continuous tile beats a blink.
        WidgetState.DELIVERED -> PENDING_WINDOW_MS
        // SEEN is terminal — nothing further can arrive for this send — so it is
        // the one receipt state that really is done after its four seconds.
        WidgetState.SEEN -> 4_000L
    }

/**
 * Reads a stored state name, tolerating anything unrecognised.
 *
 * Glance's per-widget store outlives reinstalls, so a name written by a different
 * build can come back. Throwing inside a widget update leaves the host showing a
 * blank tile that no user action can fix.
 */
fun fromName(name: String?): WidgetState =
    WidgetState.entries.firstOrNull { it.name == name } ?: WidgetState.IDLE

/**
 * How far along the ladder a state sits.
 *
 * Explicit rather than the enum's own ordinal: the declaration order is a
 * historical accident, and a reorder for readability must not silently change
 * which receipt wins a race.
 *
 * FAILED sits above SENT and below the receipts on purpose. It has to be able
 * to follow SENDING, or a send whose request threw would sit on "sending…"
 * forever; and a receipt has to be able to follow it, because a receipt can only
 * exist if the send reached the server, which is better evidence than this
 * phone's own failed request.
 */
private val WidgetState.ladderRank: Int
    get() = when (this) {
        WidgetState.IDLE -> 0
        WidgetState.SENDING -> 1
        WidgetState.SENT -> 2
        WidgetState.FAILED -> 3
        WidgetState.DELIVERED -> 4
        WidgetState.SEEN -> 5
    }

/**
 * Whether moving from this state to [next] is a step forward.
 *
 * The ladder only ever moves forward, because the two receipts do not arrive in
 * order. The recipient reports `delivered` and `seen` as two independent
 * WorkManager jobs which run concurrently, and on hardware `seen` was observed
 * arriving 54ms *before* the `delivered` for the same send. Written in arrival
 * order, that late `delivered` undid the `seen` — the sender's screen stuck on
 * "it buzzed her phone" and never reached gold again.
 *
 * Resets are deliberately not expressed here: returning a tile to IDLE, or
 * starting a new send, replaces the record outright rather than advancing it.
 */
fun WidgetState.advancesTo(next: WidgetState): Boolean = next.ladderRank > ladderRank
