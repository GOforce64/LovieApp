package com.lovebutton.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EnrolRequest(
    val code: String,
    @SerialName("fcm_token") val fcmToken: String,
    val label: String,
)

@Serializable
data class EnrolResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("auth_token") val authToken: String,
    val person: Int,
    @SerialName("partner_name") val partnerName: String,
)

@Serializable
data class DeviceRequest(
    @SerialName("fcm_token") val fcmToken: String,
)

/** No recipient field, deliberately. The server derives it (spec section 4). */
@Serializable
data class SendRequest(
    @SerialName("msg_id") val msgId: Int,
)

@Serializable
data class SendResponse(
    @SerialName("send_id") val sendId: String,
    val delivered: Int,
)

@Serializable
data class ApiError(
    val error: String = "unknown",
    val message: String = "",
)

/** What the caller of [LoveButtonApi.send] actually needs. */
data class SendResult(val sendId: String, val delivered: Int)

/**
 * Enrolment has three outcomes worth telling apart on screen: it worked, the code
 * was wrong, or you have tried too many times. Everything else is lumped into
 * Failed with a message, because there is nothing useful for the user to do about
 * it beyond try again later.
 */
sealed interface EnrolResult {
    data class Ok(
        val deviceId: String,
        val authToken: String,
        val person: Int,
        val partnerName: String,
    ) : EnrolResult

    data object InvalidCode : EnrolResult
    data object RateLimited : EnrolResult
    data class Failed(val message: String) : EnrolResult
}
