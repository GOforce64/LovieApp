package com.lovebutton.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.unit.ColorProvider
import com.lovebutton.app.data.messageForId

/** Where a widget's current state is stored, per widget instance. */
val KEY_STATE = stringPreferencesKey("state")

/** Which message a tap should send, passed to the action callback. */
val KEY_MSG_ID = ActionParameters.Key<Int>("msg_id")

/**
 * One widget, parameterised by message id.
 *
 * Spec 7 requires four separate *registrations* so the launcher lists four
 * entries with their own preview and label. It says nothing about four
 * implementations, and four copies of the state ladder would be four places to
 * fix every future change. The subclasses in the other files carry only an id.
 */
abstract class MessageWidget(private val msgId: Int) : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = fromName(currentState(KEY_STATE))
            Tile(msgId, state)
        }
    }

    @Composable
    private fun Tile(msgId: Int, state: WidgetState) {
        val message = messageForId(msgId)
        val icon = iconFor(msgId, state)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(
                    onClick = actionRunCallback<SendAction>(
                        actionParametersOf(KEY_MSG_ID to msgId)
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = message?.text,
                // No background and no label, so the icon carries the whole tile.
                // State therefore lives in the artwork: three fill stages, plus a
                // tint for the one state that has no stage of its own.
                colorFilter = tintColorFor(state)?.let { ColorFilter.tint(ColorProvider(Color(it))) },
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}
