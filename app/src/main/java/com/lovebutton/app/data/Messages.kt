package com.lovebutton.app.data

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
)

/**
 * A deliberately temporary notification channel.
 *
 * Android freezes a channel's sound when the channel is created and will not let
 * you change it afterwards (spec 6.3). The four real sounds are not chosen until
 * milestone 4, so creating `msg_1`..`msg_4` now would burn those channel ids with
 * the default sound permanently. This throwaway id is deleted and replaced when
 * the real channels arrive.
 */
const val DEV_CHANNEL_ID = "dev_buzz_v1"

val MESSAGES: List<LoveMessage> = listOf(
    LoveMessage(1, "I love you", DEV_CHANNEL_ID),
    LoveMessage(2, "Thinking of you", DEV_CHANNEL_ID),
    LoveMessage(3, "Miss you", DEV_CHANNEL_ID),
    LoveMessage(4, "Call me when you can", DEV_CHANNEL_ID),
)

/** Null when this build does not know the id — an older app, a newer message. */
fun messageForId(id: Int): LoveMessage? = MESSAGES.firstOrNull { it.id == id }
