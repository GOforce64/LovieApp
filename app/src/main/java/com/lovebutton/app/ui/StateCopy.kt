package com.lovebutton.app.ui

import com.lovebutton.app.widget.WidgetState

/**
 * The words for each state. One set, used everywhere a state is named.
 *
 * The focal area and the guide originally had their own registers — playful for
 * the guide, plain for the focal line. Two vocabularies for six states made the
 * guide read as an explanation of a different screen, so the plain set was
 * dropped and this is what both show. See the design doc, §5.
 */
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
