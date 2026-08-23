package com.lovebutton.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.Data
import com.lovebutton.app.BuildConfig
import com.lovebutton.app.data.LoveButtonApi
import com.lovebutton.app.data.Prefs

/**
 * Tells the Worker about a rotated FCM token.
 *
 * Runs through WorkManager rather than inline because FCM can hand us a new token
 * at any moment, including while the app is being killed. WorkManager retries when
 * connectivity returns, which matters more on MIUI than anywhere else.
 */
class RegisterTokenWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fcmToken = inputData.getString(KEY_FCM_TOKEN) ?: return Result.failure()
        val enrolment = Prefs(applicationContext).current() ?: return Result.success()

        val api = LoveButtonApi(BuildConfig.API_BASE_URL)
        val ok = api.registerDevice(enrolment.authToken, fcmToken)

        // A false here means either a network problem (worth retrying) or a
        // rejected bearer token (not worth retrying, but harmless to). Retry is
        // the safe default; the Setup screen surfaces a persistently dead token.
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_FCM_TOKEN = "fcm_token"

        fun enqueue(context: Context, fcmToken: String) {
            val request = OneTimeWorkRequestBuilder<RegisterTokenWorker>()
                .setInputData(Data.Builder().putString(KEY_FCM_TOKEN, fcmToken).build())
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
