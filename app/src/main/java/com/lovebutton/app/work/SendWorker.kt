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
 * Performs one send.
 *
 * Never called inline from a tap handler: the widget host process (and the app
 * itself) can be killed mid-request, and WorkManager gives retry-on-reconnect for
 * free. On MIUI, where processes are killed aggressively, this is the difference
 * between a tap that eventually lands and one that vanishes.
 */
class SendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val msgId = inputData.getInt(KEY_MSG_ID, -1)
        if (msgId < 0) return Result.failure()

        val enrolment = Prefs(applicationContext).current() ?: return Result.failure()

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId)
            // A delivered count of zero still counts as success: the send was
            // recorded, her phone just has no active device right now.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_MSG_ID = "msg_id"

        fun enqueue(context: Context, msgId: Int) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(Data.Builder().putInt(KEY_MSG_ID, msgId).build())
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
