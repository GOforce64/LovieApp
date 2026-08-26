package com.lovebutton.app.ui

import com.lovebutton.app.widget.WidgetState

/**
 * The words for each state, in two registers.
 *
 * The guide is read a handful of times ever, so it is playful. The focal lines
 * are read hundreds of times, so they are warm and plain — loud copy wears out,
 * and this is the copy you will still be reading in a year. The split is
 * deliberate (spec §5); do not harmonise them.
 */
fun focalLine(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "tap one below"
    WidgetState.SENDING -> "sending…"
    WidgetState.SENT -> "on its way to $partnerName"
    WidgetState.DELIVERED -> "it buzzed her phone"
    WidgetState.SEEN -> "$partnerName saw it ♡"
    WidgetState.FAILED -> "didn't get through :("
}

fun guideLine(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "click the button!"
    WidgetState.SENDING -> "on its way to $partnerName 0o0"
    WidgetState.SENT -> "traveling in the interwebs (• ε •)"
    WidgetState.DELIVERED -> "it buzzed $partnerName's phone :3"
    WidgetState.SEEN -> "$partnerName looked at it (>^o^)>"
    WidgetState.FAILED -> "didn't get through （◞‸◟）"
}

/**
 * What the focal area says on a cold open, when nothing was just sent.
 *
 * The app remembers the last send where the widget forgets it (spec §4.3),
 * because remembering is the app screen's job and a permanently lit button on
 * the home screen would only be noise.
 */
fun coldOpenLine(partnerName: String, messageText: String, ageMillis: Long): String {
    val age = when {
        ageMillis < 60_000L -> "just now"
        ageMillis < 3_600_000L -> "${ageMillis / 60_000L}m ago"
        ageMillis < 86_400_000L -> "${ageMillis / 3_600_000L}h ago"
        else -> "${ageMillis / 86_400_000L}d ago"
    }
    return "$partnerName saw your \"$messageText\" · $age"
}
