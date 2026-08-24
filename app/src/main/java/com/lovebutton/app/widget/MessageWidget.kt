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
import com.lovebutton.app.R
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
        val icon = when (state) {
            WidgetState.IDLE, WidgetState.FAILED -> outlineIconFor(msgId)
            WidgetState.SENDING -> halfIconFor(msgId)
            WidgetState.SENT -> filledIconFor(msgId)
        }

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
                colorFilter = tintFor(state),
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The one state with no fill stage of its own.
 *
 * IDLE, SENDING and SENT are three stages of the same icon filling up, which
 * Android 12+ tweens between, so the tile reads as filling rather than as three
 * unrelated pictures. FAILED is not a point on that scale — it is the sequence
 * abandoned — so it reuses the outline greyed out. Nothing else is tinted: a tint
 * flattens every path to one colour and would throw away the border and the shine
 * that make the filled stage read as "landed".
 */
private fun tintFor(state: WidgetState) = when (state) {
    WidgetState.FAILED -> ColorFilter.tint(ColorProvider(Color(0xFFA9A2AD)))
    else -> null
}

private fun outlineIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_outline
    else -> R.drawable.ic_heart_outline
}

private fun halfIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_half
    else -> R.drawable.ic_heart_half
}

private fun filledIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_filled
    else -> R.drawable.ic_heart_filled
}
