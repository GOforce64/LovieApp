package com.lovebutton.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import com.lovebutton.app.work.beginSend

class LoveWidget : MessageWidget(msgId = 1)

class LoveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoveWidget()
}

/**
 * Handles a tap.
 *
 * Sets SENDING immediately so the tile acknowledges the touch, then hands the
 * network call to WorkManager. Nothing blocking happens here: an ActionCallback
 * runs on the host's clock, and a slow request would freeze the launcher.
 */
class SendAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val msgId = parameters[KEY_MSG_ID] ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)

        setWidgetState(context, appWidgetId, WidgetState.SENDING)
        beginSend(context, msgId, appWidgetId)
    }
}
