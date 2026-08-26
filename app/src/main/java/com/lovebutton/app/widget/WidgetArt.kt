package com.lovebutton.app.widget

import androidx.annotation.DrawableRes
import com.lovebutton.app.R

/**
 * The one place the ladder's colours are written down.
 *
 * Spec §6.1: these are shared tokens. The pixel art, the app's focal heart and
 * the guide all read them from here, which is what lets the app be redesigned
 * freely without the guide starting to describe colours that no longer exist.
 * ARGB ints rather than Compose Color so Glance, Canvas and the guide can all
 * use them without one of them dragging in the others' dependencies.
 */
object PixelPalette {
    val Border = 0xFFD1447E.toInt()
    val Shine = 0xFFFFD9E8.toInt()
    val Sent = 0xFFD81B60.toInt()
    val Delivered = 0xFFFF6FA5.toInt()
    val Gold = 0xFFFFC64B.toInt()
    val Idle = 0xFFC98BA8.toInt()
    val Failed = 0xFFA9A2AD.toInt()
}

/**
 * Which ASCII grid a message is drawn from.
 *
 * Falls back rather than throwing: Glance's per-widget store outlives
 * reinstalls, so an id written by a different build can come back, and throwing
 * inside a widget update leaves a blank tile no user action can fix.
 */
fun gridNameFor(msgId: Int): String = when (msgId) {
    2 -> "bubble"
    3 -> "paw"
    4 -> "call"
    else -> "heart"
}

/**
 * The drawable for one message in one state.
 *
 * Shared deliberately by `MessageWidget` and the guide. The guide's entire
 * purpose is explaining what the widget draws, so it must be structurally
 * incapable of showing something the widget does not.
 */
@DrawableRes
fun iconFor(msgId: Int, state: WidgetState): Int = when (state) {
    WidgetState.IDLE, WidgetState.FAILED -> when (msgId) {
        2 -> R.drawable.ic_bubble_outline
        3 -> R.drawable.ic_paw_outline
        4 -> R.drawable.ic_call_outline
        else -> R.drawable.ic_heart_outline
    }
    WidgetState.SENDING -> when (msgId) {
        2 -> R.drawable.ic_bubble_half
        3 -> R.drawable.ic_paw_half
        4 -> R.drawable.ic_call_half
        else -> R.drawable.ic_heart_half
    }
    WidgetState.SENT -> when (msgId) {
        2 -> R.drawable.ic_bubble_filled
        3 -> R.drawable.ic_paw_filled
        4 -> R.drawable.ic_call_filled
        else -> R.drawable.ic_heart_filled
    }
    WidgetState.DELIVERED -> when (msgId) {
        2 -> R.drawable.ic_bubble_delivered
        3 -> R.drawable.ic_paw_delivered
        4 -> R.drawable.ic_call_delivered
        else -> R.drawable.ic_heart_delivered
    }
    WidgetState.SEEN -> when (msgId) {
        2 -> R.drawable.ic_bubble_seen
        3 -> R.drawable.ic_paw_seen
        4 -> R.drawable.ic_call_seen
        else -> R.drawable.ic_heart_seen
    }
}

/**
 * The tint for the one state with no fill stage of its own, or null.
 *
 * Nothing else is tinted: a tint flattens every path to one colour and would
 * throw away the border and the shine that make the filled stage read as landed.
 */
fun tintColorFor(state: WidgetState): Int? =
    if (state == WidgetState.FAILED) PixelPalette.Failed else null
