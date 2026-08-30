package com.lovebutton.app.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lovebutton.app.widget.WidgetState

/** How far the face travels either side of where it would otherwise sit. */
private val SwayAmplitude = 3.dp

/** One full there-and-back. Half of it is the tween; the reverse is the other half. */
private const val SwayPeriodMillis = 1800

private const val FaceTag = "face"

/**
 * Where the face sits this frame, in pixels from its resting place.
 *
 * Pulled out of the composable so the arithmetic can be tested: this module has
 * no Compose test runtime, and a sway that silently stopped swaying — or that
 * ignored a phone with animations turned off — would otherwise only be catchable
 * by eye.
 *
 * The clamp matters. The inline placeholder is sized for exactly one amplitude
 * either side, so a phase outside the range would push the face past the edge of
 * the box reserved for it.
 */
fun swayOffsetPx(phase: Float, amplitudePx: Float, animationsOn: Boolean): Float =
    if (!animationsOn) 0f else phase.coerceIn(-1f, 1f) * amplitudePx

/**
 * The focal area's state line, with its face drifting side to side forever.
 *
 * The face is inline content rather than a second Text in a Row. At the display
 * size these lines are close to wrapping inside the focal card, and a sibling
 * composable would break the sentence at the seam between the words and the
 * face, leaving the face stranded beside a two-line block. As inline content it
 * takes part in the paragraph's own line-breaking, exactly as the characters it
 * replaces did.
 *
 * The motion is small and slow on purpose: it exists to prove the app is alive
 * while nothing is happening, and anything faster reads as a fault rather than a
 * breath. It respects the same animations-off setting as the ladder — a phone
 * that has asked for stillness gets a face that is simply centred.
 */
@Composable
fun SwayingStateLine(
    state: WidgetState,
    partnerName: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.titleMedium
    val face = guideFace(state)
    val context = LocalContext.current
    val density = LocalDensity.current

    // Read once, the way the ladder reads it.
    val animationsOn = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

    val transition = rememberInfiniteTransition(label = "sway")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SwayPeriodMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    // The box reserved for the face is one amplitude wider on each side than the
    // face itself, and the face is centred in it. Sizing it to the glyphs alone
    // would put the face's own travel outside its placeholder.
    val measurer = rememberTextMeasurer()
    val measured = remember(face, style, density) {
        measurer.measure(AnnotatedString(face), style)
    }
    val slotWidth = with(density) { (measured.size.width.toDp() + SwayAmplitude * 2).toSp() }
    val slotHeight = with(density) { measured.size.height.toDp().toSp() }
    val amplitudePx = with(density) { SwayAmplitude.toPx() }

    val line = buildAnnotatedString {
        append(guideWords(state, partnerName))
        append(' ')
        appendInlineContent(FaceTag, face)
    }

    val inline = mapOf(
        FaceTag to InlineTextContent(
            Placeholder(
                width = slotWidth,
                height = slotHeight,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            )
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = face,
                    style = style,
                    modifier = Modifier.graphicsLayer {
                        translationX = swayOffsetPx(phase, amplitudePx, animationsOn)
                    },
                )
            }
        }
    )

    Text(
        text = line,
        style = style,
        textAlign = TextAlign.Center,
        inlineContent = inline,
        modifier = modifier,
    )
}
