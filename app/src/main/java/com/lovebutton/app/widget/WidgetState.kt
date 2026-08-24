package com.lovebutton.app.widget

/**
 * What a widget is currently showing.
 *
 * Spec 7.1 defines six states; DELIVERED and SEEN need receipts from the server,
 * which do not exist yet, so they arrive with Plan 4. The four here are the ones
 * this app can prove. A widget that claims "delivered" without a receipt is
 * lying, and knowing she got it is the entire product.
 */
enum class WidgetState { IDLE, SENDING, SENT, FAILED }

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
        WidgetState.SENT -> 4_000L
        WidgetState.FAILED -> 3_000L
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
