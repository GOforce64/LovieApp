package com.lovebutton.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lovebutton.app.R

/**
 * "Sticker Book" — one theme, not two skins.
 *
 * Every element is a sticker: a hard ink keyline, flat colour, and a shallow
 * offset shadow. The shadow is deliberately small; a deeper one was tried on
 * hardware and read as too poppy.
 *
 * These are the app's chrome only. The ladder's three state colours live in
 * `PixelPalette` and are not repeated here — if they were, the guide could
 * eventually describe a colour the widget no longer draws.
 */
object Sticker {
    val Ground = Color(0xFFFFF0F5)
    val Ink = Color(0xFF2E2430)
    val Surface = Color(0xFFFFFFFF)
    val Mint = Color(0xFF7FD6C2)
    val Blossom = Color(0xFFFFB8CE)
    val Butter = Color(0xFFFFCF7A)
}

/** Shallow on purpose. See the note above. */
val StickerShadow = 2.dp
val StickerKeyline = 2.dp

/** Each message keeps one colour, so the four are told apart by hue before text. */
fun stickerColorFor(msgId: Int): Color = when (msgId) {
    2 -> Sticker.Mint
    3 -> Sticker.Blossom
    4 -> Sticker.Butter
    else -> Sticker.Surface
}

internal val Fredoka = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))
internal val Quicksand = FontFamily(
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_bold, FontWeight.Bold),
)

/**
 * The four message buttons, and only those.
 *
 * A role of its own rather than a change to `bodyLarge`, which is what Material
 * hands to any Text that names no style — including the enrolment code field.
 * Repainting that role would have moved four labels and one text field, and only
 * the four were meant to move.
 *
 * Fredoka rather than Quicksand because the buttons are the loudest thing on the
 * screen after the pandas, and the body voice was making them read as a settings
 * list. One point smaller than the display role below it, so the focal line stays
 * the largest text on the screen.
 */
val StickerLabel = TextStyle(fontFamily = Fredoka, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

internal val StickerType = Typography(
    headlineMedium = TextStyle(fontFamily = Fredoka, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Fredoka, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Quicksand, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = Quicksand, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Quicksand, fontSize = 12.sp, fontWeight = FontWeight.Bold),
)

private val Scheme = lightColorScheme(
    primary = Sticker.Ink,
    onPrimary = Sticker.Surface,
    background = Sticker.Ground,
    onBackground = Sticker.Ink,
    surface = Sticker.Surface,
    onSurface = Sticker.Ink,
)

@Composable
fun LoveButtonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = StickerType, content = content)
}
