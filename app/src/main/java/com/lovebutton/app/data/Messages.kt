package com.lovebutton.app.data

import androidx.annotation.RawRes
import com.lovebutton.app.R

/**
 * The message catalogue lives in the app, not on the server.
 *
 * A push carries `msg_id: 3`, never the words. Two consequences: the text never
 * transits Google's servers, and adding a fifth message is an app-only change.
 * The server keeps its own allowlist of valid ids and nothing else.
 */
data class LoveMessage(
    val id: Int,
    val text: String,
    val channelId: String,
    @RawRes val soundRes: Int,
)

/**
 * The retired development channel.
 *
 * Kept only so startup can delete it. It existed because a channel's sound is
 * frozen at creation (spec 6.3), so the real `msg_N` ids had to stay unused
 * until the four sounds were final — which they now are.
 */
const val DEV_CHANNEL_ID = "dev_buzz_v1"

val MESSAGES: List<LoveMessage> = listOf(
    LoveMessage(1, "I love you", "msg_1", R.raw.love),
    LoveMessage(2, "Thinking of you", "msg_2", R.raw.thinking),
    LoveMessage(3, "Miss you", "msg_3", R.raw.miss),
    LoveMessage(4, "Call me when you can", "msg_4", R.raw.call),
)

/** Null when this build does not know the id — an older app, a newer message. */
fun messageForId(id: Int): LoveMessage? = MESSAGES.firstOrNull { it.id == id }
