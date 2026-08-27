package com.lovebutton.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A sticker: flat colour, hard keyline, and a shallow offset shadow drawn as a
 * second box behind the first. Compose's elevation shadow is soft and would not
 * read as a sticker at all.
 *
 * This lived inside HomeScreen while the home screen was the only place that
 * spoke this language. Enrol and Delivery setup now do too, so it moved out
 * rather than being copied — three drifting copies of the shadow offset is
 * exactly how a house style stops being one.
 */
@Composable
fun StickerBox(
    fill: Color,
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

/**
 * A sticker you press.
 *
 * `enabled` dims the fill rather than the text: a greyed label on a bright
 * sticker reads as a rendering fault, where a muted sticker reads as waiting.
 *
 * `height` pins the button instead of letting its padding decide. A caller that
 * has to budget the screen by hand needs the number it is budgeting to be the
 * number the button actually takes.
 */
@Composable
fun StickerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Sticker.Surface,
    enabled: Boolean = true,
    radius: Int = 13,
    height: Dp? = null,
) {
    StickerBox(
        fill = if (enabled) fill else Sticker.Ground,
        radius = radius,
        modifier = if (enabled) modifier.clickable { onClick() } else modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier)
                .padding(horizontal = 10.dp, vertical = if (height == null) 11.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = if (enabled) Sticker.Ink else Sticker.Ink.copy(alpha = 0.45f),
            )
        }
    }
}
