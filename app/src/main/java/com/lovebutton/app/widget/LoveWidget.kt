package com.lovebutton.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import com.lovebutton.app.work.SendWorker

class LoveWidget : MessageWidget(msgId = 1)

class LoveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LoveWidget()
}

/**
 * Handles a tap.
 *
 * The state ladder is wired in the next task; this version only dispatches the
 * send, so a tap already does the thing that matters.
 */
class SendAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val msgId = parameters[KEY_MSG_ID] ?: return
        SendWorker.enqueue(context, msgId)
    }
}
