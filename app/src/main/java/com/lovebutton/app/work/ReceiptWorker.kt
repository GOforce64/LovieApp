package com.lovebutton.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lovebutton.app.BuildConfig
import com.lovebutton.app.data.LoveButtonApi
import com.lovebutton.app.data.Prefs

/**
 * Reports a receipt back to the server.
 *
 * Through WorkManager like every other call (spec §6.5). A receipt sent inline
 * from the push handler would be lost whenever the process is killed between
 * arriving and reporting — which on MIUI is routine.
 */
class ReceiptWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sendId = inputData.getString(KEY_SEND_ID) ?: return Result.failure()
        val state = inputData.getString(KEY_STATE) ?: return Result.failure()
        val enrolment = Prefs(applicationContext).current() ?: return Result.failure()

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).receipt(enrolment.authToken, sendId, state)
            // Success either way. A rejected receipt (403 not_recipient, 404
            // unknown_send) must NOT be retried: this device is not the recipient,
            // or the send has been purged, and neither improves by trying again.
            // Only a thrown network error is worth a retry, which the catch handles.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_SEND_ID = "send_id"
        private const val KEY_STATE = "state"

        fun enqueue(context: Context, sendId: String, state: String) {
            val request = OneTimeWorkRequestBuilder<ReceiptWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SEND_ID, sendId)
                        .putString(KEY_STATE, state)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
