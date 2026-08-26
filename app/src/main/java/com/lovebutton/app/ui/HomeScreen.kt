package com.lovebutton.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lovebutton.app.R
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.work.SendWorker

private fun pandaFor(msgId: Int): Int = when (msgId) {
    2 -> R.drawable.panda_thinking
    3 -> R.drawable.panda_miss
    4 -> R.drawable.panda_call
    else -> R.drawable.panda_love
}

/**
 * A sticker: flat colour, hard keyline, and a shallow offset shadow drawn as a
 * second box behind the first. Compose's elevation shadow is soft and would not
 * read as a sticker at all.
 */
@Composable
private fun StickerBox(
    fill: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    radius: Int = 16,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = StickerShadow, y = StickerShadow)
                .clip(shape)
                .background(Sticker.Ink)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(StickerKeyline, Sticker.Ink, shape)
        ) { content() }
    }
}

@Composable
fun HomeScreen(
    partnerName: String,
    onOpenSetup: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember { CurrentSend(context) }
    val snapshot by store.flow.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Sticker.Ground)
            // targetSdk 36 draws edge to edge whether or not it is asked to, so
            // without this the focal card sits under the status bar and the two
            // buttons sit under the gesture pill. The ground colour is painted
            // before the inset so it still fills the whole screen.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
    ) {
        // ---- focal area ----
        StickerBox(fill = Sticker.Surface, radius = 22, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val state = snapshot?.state ?: WidgetState.IDLE
                AnimatedPixelIcon(
                    msgId = snapshot?.msgId ?: 1,
                    state = state,
                    modifier = Modifier.size(132.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = focalLine(state, partnerName),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // Only once the send has settled: while it is in flight the
                // state line already says everything, and two lines competing
                // makes the moment busy rather than warm.
                val settled = snapshot?.takeIf { it.state == WidgetState.SEEN }
                if (settled != null) {
                    Text(
                        text = coldOpenLine(
                            partnerName,
                            messageForId(settled.msgId)?.text ?: "",
                            System.currentTimeMillis() - settled.at,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        // ---- the four messages ----
        MESSAGES.forEach { message ->
            StickerBox(
                fill = stickerColorFor(message.id),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable {
                        // Haptic first, before the network call even starts. It
                        // lands immediately, which is what makes the tap feel
                        // responsive regardless of how long the request takes.
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        SendWorker.enqueue(context, message.id)
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(pandaFor(message.id)),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.size(11.dp))
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("What the colours mean" to onOpenGuide, "Delivery setup" to onOpenSetup)
                .forEach { (label, action) ->
                    StickerBox(
                        fill = Sticker.Surface,
                        radius = 13,
                        modifier = Modifier.weight(1f).clickable { action() },
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
        }
    }
}
