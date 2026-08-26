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
