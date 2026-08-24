package com.lovebutton.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
            WidgetState.IDLE, WidgetState.SENDING -> outlineIconFor(msgId)
            WidgetState.SENT, WidgetState.FAILED -> filledIconFor(msgId)
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(backgroundFor(state))
                .cornerRadius(16.dp)
                .padding(8.dp)
                .clickable(
                    onClick = actionRunCallback<SendAction>(
                        actionParametersOf(KEY_MSG_ID to msgId)
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = message?.text,
                modifier = GlanceModifier.size(64.dp),
            )
            Text(
                text = if (state == WidgetState.FAILED) "Not sent" else (message?.text ?: ""),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF6B4453)),
                    fontSize = 11.sp,
                ),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}

/** Spec 7.1's colour column, minus the states that need receipts. */
private fun backgroundFor(state: WidgetState) = ColorProvider(
    when (state) {
        WidgetState.IDLE -> Color(0xFFF6E7EC)     // pale grey-pink
        WidgetState.SENDING -> Color(0xFFF7D6E2)  // pale pink
        WidgetState.SENT -> Color(0xFFFF6FA5)     // pink
        WidgetState.FAILED -> Color(0xFFDEDCE0)   // grey
    }
)

private fun outlineIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_outline
    else -> R.drawable.ic_heart_outline
}

private fun filledIconFor(msgId: Int): Int = when (msgId) {
    1 -> R.drawable.ic_heart_filled
    else -> R.drawable.ic_heart_filled
}
