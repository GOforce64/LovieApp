package com.lovebutton.app.work

import android.appwidget.AppWidgetManager
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
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.holdMillis
import com.lovebutton.app.widget.setWidgetState
import kotlinx.coroutines.delay

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
        val appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        if (msgId < 0) return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        val enrolment = Prefs(applicationContext).current()
            ?: return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId)
            // A delivered count of zero still counts as success: the send was
            // recorded, her phone just has no active device right now.
            settle(appWidgetId, WidgetState.SENT, Result.success())
        } catch (e: Exception) {
            // The tile says failed while WorkManager retries underneath. Showing
            // SENDING indefinitely would be worse: a tile that never resolves reads
            // as a broken app rather than a failed send.
            settle(appWidgetId, WidgetState.FAILED, Result.retry())
        }
    }

    /**
     * Shows a terminal state for its hold time, then returns the tile to idle.
     *
     * The delay runs inside the worker rather than as a second scheduled job: a
     * follow-up WorkRequest could be deferred by Doze for minutes, stranding a
     * tile on "Sent" long after the moment has passed.
     */
    private suspend fun settle(appWidgetId: Int, state: WidgetState, result: Result): Result {
        setWidgetState(applicationContext, appWidgetId, state)
        state.holdMillis?.let { hold ->
            delay(hold)
            setWidgetState(applicationContext, appWidgetId, WidgetState.IDLE)
        }
        return result
    }

    companion object {
        private const val KEY_MSG_ID = "msg_id"
        private const val KEY_APP_WIDGET_ID = "app_widget_id"

        /**
         * @param appWidgetId the tile to report back to, or INVALID_APPWIDGET_ID
         *   when the send came from the app's own home screen rather than a widget.
         */
        fun enqueue(
            context: Context,
            msgId: Int,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(
                    Data.Builder()
                        .putInt(KEY_MSG_ID, msgId)
                        .putInt(KEY_APP_WIDGET_ID, appWidgetId)
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
