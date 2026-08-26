package com.lovebutton.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

/**
 * Writes a state into one widget's own store and redraws it.
 *
 * The widget is addressed by platform `appWidgetId` rather than `GlanceId`
 * because this crosses a process boundary: a `GlanceId` cannot be put into
 * WorkManager input data, and the worker may well run after the host that
 * created it has been killed. On MIUI that is the normal case, not the edge one.
 *
 * A widget the user has since removed resolves to null; that is not an error and
 * must not fail the send that triggered it.
 */
suspend fun setWidgetState(context: Context, appWidgetId: Int, state: WidgetState) =
    writeWidgetState(context, appWidgetId, state, onlyForward = false)

/**
 * Writes a state only if it is further along the ladder than what is showing.
 *
 * For the receipt path, where the two receipts for one send are reported as
 * concurrent jobs and arrive in either order — a `delivered` trailing its own
 * `seen` would otherwise repaint the gold tile pink. See [advancesTo].
 *
 * Deliberately not the behaviour of [setWidgetState]: returning a tile to IDLE
 * is a reset, not a step backwards, and a guard here would strand every tile on
 * the last thing it showed.
 */
suspend fun advanceWidgetState(context: Context, appWidgetId: Int, state: WidgetState) =
    writeWidgetState(context, appWidgetId, state, onlyForward = true)

private suspend fun writeWidgetState(
    context: Context,
    appWidgetId: Int,
    state: WidgetState,
    onlyForward: Boolean,
) {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

    val manager = GlanceAppWidgetManager(context)
    val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return

    var wrote = false
    updateAppWidgetState(context, glanceId) { prefs ->
        if (onlyForward && !fromName(prefs[KEY_STATE]).advancesTo(state)) {
            return@updateAppWidgetState
        }
        prefs[KEY_STATE] = state.name
        wrote = true
    }

    // Nothing changed, so nothing to redraw — and redrawing anyway would cost a
    // RemoteViews round trip per dropped receipt.
    if (!wrote) return

    // Each widget class owns its own instances, so the redraw has to go through
    // the class that actually holds this id. Asking the wrong one writes the
    // state and then quietly draws nothing.
    listOf(LoveWidget(), ThinkingWidget(), MissWidget(), CallWidget()).forEach { widget ->
        if (glanceId in manager.getGlanceIds(widget.javaClass)) {
            widget.update(context, glanceId)
        }
    }
}

/**
 * Returns one widget to IDLE, but only if it is still showing `expected`.
 *
 * Two receipts for the same send each carry their own hold timer (see
 * `PushService`): `delivered` arrives, holds the tile for 4s, then resets it;
 * `seen` can arrive inside that window, holds the tile for its own 4s, then
 * resets it too. An unconditional reset from either timer would race the
 * other — concretely, a `delivered` at t=0 and a `seen` at t=1000 would have
 * the delivered coroutine's reset fire at t=4000 and unconditionally wipe the
 * gold "seen" tile a full second early, since it has no idea a newer receipt
 * already overwrote what it wrote. Comparing against `expected` first means
 * only the coroutine whose own write is still on screen gets to clear it; a
 * later write already stomped the current one's `expected` value and this
 * becomes a no-op.
 *
 * The compare and the write happen inside one `updateAppWidgetState` block
 * rather than as a separate read then write, so a concurrent writer cannot
 * land in between and get silently overwritten by this call.
 */
suspend fun clearWidgetStateIf(context: Context, appWidgetId: Int, expected: WidgetState) {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

    val manager = GlanceAppWidgetManager(context)
    val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return

    var cleared = false
    updateAppWidgetState(context, glanceId) { prefs ->
        if (prefs[KEY_STATE] == expected.name) {
            prefs[KEY_STATE] = WidgetState.IDLE.name
            cleared = true
        }
    }
    if (!cleared) return

    // Same fan-out as setWidgetState, and skipped entirely when this call was
    // a no-op — redrawing a tile that did not change is wasted IPC.
    listOf(LoveWidget(), ThinkingWidget(), MissWidget(), CallWidget()).forEach { widget ->
        if (glanceId in manager.getGlanceIds(widget.javaClass)) {
            widget.update(context, glanceId)
        }
    }
}
