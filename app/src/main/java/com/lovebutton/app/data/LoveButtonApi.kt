package com.lovebutton.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Every call the app makes to the Worker.
 *
 * Deliberately small and dependency-light: OkHttp plus kotlinx.serialization, no
 * Retrofit. There are three endpoints, and being able to read the whole client in
 * one sitting is worth more here than the boilerplate a framework would save.
 *
 * Nothing in this class ever logs `authToken`. It is the only credential the app
 * holds, and Logcat is readable by anyone with adb.
 */
class LoveButtonApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun post(path: String, body: String, authToken: String?): Request {
        // No explicit Content-Type header: the body carries its media type, and
        // OkHttp's BridgeInterceptor derives the header from it — appending
        // "; charset=utf-8". Setting the header by hand as well is not merely
        // redundant, it loses: the interceptor overwrites whatever you set.
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))

        if (authToken != null) {
            builder.header("Authorization", "Bearer $authToken")
        }
        return builder.build()
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    /** Trades an enrolment code for a device bearer token. Called once per phone. */
    suspend fun enrol(code: String, fcmToken: String, label: String): EnrolResult {
        val body = json.encodeToString(EnrolRequest(code, fcmToken, label))

        return try {
            execute(post("/v1/enroll", body, authToken = null)).use { response ->
                val text = response.body.string()

                when (response.code) {
                    200 -> {
                        val parsed = json.decodeFromString<EnrolResponse>(text)
                        EnrolResult.Ok(
                            deviceId = parsed.deviceId,
                            authToken = parsed.authToken,
                            person = parsed.person,
                            partnerName = parsed.partnerName,
                        )
                    }
                    403 -> EnrolResult.InvalidCode
                    429 -> EnrolResult.RateLimited
                    else -> EnrolResult.Failed(errorMessage(text, response.code))
                }
            }
        } catch (e: IOException) {
            EnrolResult.Failed("Could not reach the server. Check your connection.")
        }
    }

    /**
     * Refreshes the FCM token the server pushes to. Returns false when the server
     * rejects the bearer token, which means this device was deregistered and must
     * enrol again.
     */
    suspend fun registerDevice(authToken: String, fcmToken: String): Boolean {
        val body = json.encodeToString(DeviceRequest(fcmToken))

        return try {
            execute(post("/v1/devices", body, authToken)).use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Sends one message. The body carries a message id and nothing else — there is
     * no field naming a recipient, because the server derives it.
     *
     * Throws on failure so the calling WorkManager job can retry. A `delivered` of
     * zero is NOT a failure: it means her phone has no active device, which the UI
     * reports differently from a network error.
     */
    suspend fun send(authToken: String, msgId: Int, sendId: String): SendResult {
        val body = json.encodeToString(SendRequest(msgId, sendId))

        execute(post("/v1/send", body, authToken)).use { response ->
            val text = response.body.string()

            if (!response.isSuccessful) {
                throw IOException("send failed: ${errorMessage(text, response.code)}")
            }

            val parsed = json.decodeFromString<SendResponse>(text)
            return SendResult(parsed.sendId, parsed.delivered)
        }
    }

    /**
     * Reports that a message arrived, or that she opened it.
     *
     * Returns false rather than throwing on a rejected receipt: the caller is a
     * Worker whose only options are retry or give up, and a 403 or 404 is not
     * worth retrying — it means this device is not the recipient, or the send is
     * gone.
     */
    suspend fun receipt(authToken: String, sendId: String, state: String): Boolean {
        val body = json.encodeToString(ReceiptRequest(sendId, state))
        execute(post("/v1/receipts", body, authToken)).use { response ->
            response.body.string()
            return response.isSuccessful
        }
    }

    private fun errorMessage(text: String, code: Int): String = try {
        json.decodeFromString<ApiError>(text).message.ifBlank { "HTTP $code" }
    } catch (e: Exception) {
        "HTTP $code"
    }
}
