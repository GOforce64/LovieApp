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
fun guideWords(state: WidgetState, partnerName: String): String = when (state) {
    WidgetState.IDLE -> "click the button!"
    WidgetState.SENDING -> "on its way to $partnerName"
    WidgetState.SENT -> "traveling in the interwebs"
    WidgetState.DELIVERED -> "it buzzed $partnerName's phone"
    WidgetState.SEEN -> "$partnerName looked at it"
    WidgetState.FAILED -> "didn't get through"
}

/**
 * The face at the end of every line, kept separate so the focal area can move it.
 *
 * Idle has one where it used to have none. It is the state the screen rests in,
 * so a face that only appeared once something was in flight would leave the app
 * perfectly still exactly when a reader is asking themselves whether it works.
 *
 * Every state must have one — a blank here is a state that sits frozen, which
 * `every state has a face to sway` in StateCopyTest exists to catch.
 */
fun guideFace(state: WidgetState): String = when (state) {
    WidgetState.IDLE -> "(・ω・)"
    WidgetState.SENDING -> "0o0"
    WidgetState.SENT -> "(• ε •)"
    WidgetState.DELIVERED -> ":3"
    WidgetState.SEEN -> "(>^o^)>"
    WidgetState.FAILED -> "（◞‸◟）"
}

/**
 * The whole line, words then face.
 *
 * The guide renders this; the focal area renders the two halves separately so it
 * can animate the face. Both must say the same thing, which
 * `the line is its words and its face, in that order` pins.
 */
fun guideLine(state: WidgetState, partnerName: String): String =
    "${guideWords(state, partnerName)} ${guideFace(state)}"

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
