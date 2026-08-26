package com.lovebutton.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.iconFor
import com.lovebutton.app.widget.tintColorFor

/** The ladder in the order it actually happens. */
private val LADDER = listOf(
    WidgetState.IDLE to "Waiting",
    WidgetState.SENDING to "Sending",
    WidgetState.SENT to "Sent",
    WidgetState.DELIVERED to "Delivered",
    WidgetState.SEEN to "Seen",
    WidgetState.FAILED to "Didn't send",
)

/**
 * What the colours on your home screen mean.
 *
 * Every picture here is `iconFor(...)` — the widget's own drawable, unmodified.
 * That is the entire point: a guide that drew its own approximation would be
 * teaching you about a picture that does not exist on your phone. Because both
 * this screen and `MessageWidget` call the same function, it cannot drift.
 */
@Composable
fun GuideScreen(partnerName: String, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Sticker.Ground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("What the colours mean", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            "The heart on your home screen changes as your message travels.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(18.dp))

        LADDER.forEach { (state, title) ->
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .offset(x = StickerShadow, y = StickerShadow)
                        .clip(shape)
                        .background(Sticker.Ink)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Sticker.Surface)
                        .border(StickerKeyline, Sticker.Ink, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(iconFor(msgId = 1, state = state)),
                        contentDescription = title,
                        colorFilter = tintColorFor(state)?.let { ColorFilter.tint(Color(it)) },
                        modifier = Modifier.size(46.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            guideLine(state, partnerName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        val shape = RoundedCornerShape(13.dp)
        Box(Modifier.fillMaxWidth().clickable { onDone() }) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = StickerShadow, y = StickerShadow)
                    .clip(shape)
                    .background(Sticker.Ink)
            )
            Text(
                "Got it",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Sticker.Blossom)
                    .border(StickerKeyline, Sticker.Ink, shape)
                    .padding(vertical = 12.dp),
            )
        }
    }
}
