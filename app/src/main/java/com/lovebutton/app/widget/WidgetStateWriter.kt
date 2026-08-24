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
suspend fun setWidgetState(context: Context, appWidgetId: Int, state: WidgetState) {
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

    val manager = GlanceAppWidgetManager(context)
    val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return

    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[KEY_STATE] = state.name
    }
    LoveWidget().update(context, glanceId)
}
