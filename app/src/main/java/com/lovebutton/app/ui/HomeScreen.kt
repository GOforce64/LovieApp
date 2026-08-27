package com.lovebutton.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
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
 * The message button, and the panda standing on it.
 *
 * The panda is taller than the button on purpose: it stands on the bottom edge
 * and its head breaks out over the top one. The difference between the two is
 * the overhang, and it is real layout height — a sticker clips its own content,
 * so the panda has to be drawn as a sibling above it, and an overhang that no
 * one reserved space for would land on the button above.
 */
/**
 * The focal card's height, fixed rather than wrapped.
 *
 * Its content changes as a send climbs the ladder — the settled line appears
 * only on `seen` — and a card that grows by a line pushes everything under it
 * down, which was shunting the two footer buttons off the bottom of the screen.
 * Pinning the height means the four stickers and the footer never move.
 */
private val FocalHeight = 196.dp

/** Reserved for the settled line, so its arrival moves nothing above it. */
private val SettledLineHeight = 20.dp

private val MessageButtonHeight = 66.dp
private val PandaHeight = 92.dp
private val PandaOverhang = PandaHeight - MessageButtonHeight

/**
 * Air between one panda's head and the sticker above it.
 *
 * Spacing is measured from the head rather than from the sticker's top edge on
 * purpose. The head is the topmost thing drawn, so it is what the eye reads the
 * gap from, and pinning this keeps the rhythm steady when the button height
 * changes — the button grows into the overhang, and the row's total height,
 * which is the panda plus this, does not move at all.
 */
private val PandaClearance = 30.dp

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
                Modifier
                    .fillMaxWidth()
                    .height(FocalHeight)
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val state = snapshot?.state ?: WidgetState.IDLE
                AnimatedPixelIcon(
                    msgId = snapshot?.msgId ?: 1,
                    state = state,
                    modifier = Modifier.size(112.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    // The guide's words, deliberately: one voice for a state
                    // wherever it appears, so the guide reads as an explanation
                    // of this screen rather than a second vocabulary for it.
                    text = guideLine(state, partnerName),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // Only once the send has settled: while it is in flight the
                // state line already says everything, and two lines competing
                // makes the moment busy rather than warm. The slot is always
                // there, occupied or not, so the line's arrival does not nudge
                // the icon above it.
                val settled = snapshot?.takeIf { it.state == WidgetState.SEEN }
                Box(
                    Modifier.fillMaxWidth().height(SettledLineHeight).padding(top = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (settled != null) {
                        Text(
                            text = coldOpenLine(
                                partnerName,
                                messageForId(settled.msgId)?.text ?: "",
                                System.currentTimeMillis() - settled.at,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // The first panda's head reaches up into this, so it plays the part
        // PandaClearance plays between the stickers — a little tighter, because
        // the focal card above is a wall of white rather than another sticker.
        Spacer(Modifier.size(24.dp))

        // ---- the four messages ----
        MESSAGES.forEach { message ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The next panda's head hangs up into this, so what is left
                    // over is the clearance above it. The four have to read as
                    // four separate stickers rather than one block.
                    .padding(bottom = PandaClearance),
                // The sticker sits at the bottom of the box, so the space the
                // overhang reserves opens above it, where the head goes.
                contentAlignment = Alignment.BottomCenter,
            ) {
                StickerBox(
                    fill = stickerColorFor(message.id),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Haptic first, before the network call even starts.
                            // It lands immediately, which is what makes the tap
                            // feel responsive regardless of how long the request
                            // takes.
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            SendWorker.enqueue(context, message.id)
                        },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(MessageButtonHeight)
                            // The start padding is the panda's own width plus
                            // its margin, so the line begins beside the panda
                            // rather than underneath it. The art is very nearly
                            // square, so its height stands in for its width.
                            .padding(start = PandaHeight + 26.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                // Decorative, and deliberately not clickable, so taps fall through
                // to the sticker underneath: the body of the panda still sends,
                // and only the head poking out above the button is inert.
                Image(
                    painter = painterResource(pandaFor(message.id)),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp)
                        .height(PandaHeight),
                )
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
