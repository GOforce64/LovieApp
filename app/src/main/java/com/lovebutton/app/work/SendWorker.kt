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
import com.lovebutton.app.data.CurrentSend
import com.lovebutton.app.data.LoveButtonApi
import com.lovebutton.app.data.PENDING_WINDOW_MS
import com.lovebutton.app.data.PendingSends
import com.lovebutton.app.data.Prefs
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.clearWidgetStateIf
import com.lovebutton.app.widget.holdMillis
import com.lovebutton.app.widget.setWidgetState
import kotlinx.coroutines.delay
import java.util.UUID

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

    /** The id minted for this run, needed by settle() to check for a receipt. */
    private var mintedSendId: String = ""

    override suspend fun doWork(): Result {
        val msgId = inputData.getInt(KEY_MSG_ID, -1)
        val appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        if (msgId < 0) return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        val enrolment = Prefs(applicationContext).current()
            ?: return settle(appWidgetId, WidgetState.FAILED, Result.failure())

        val pending = PendingSends(applicationContext)
        pending.forgetExpired()

        // Minted and recorded BEFORE the request. A receipt can beat the send
        // response, and a mapping written afterwards would miss it.
        val sendId = UUID.randomUUID().toString()
        mintedSendId = sendId
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            pending.remember(sendId, appWidgetId)
        }

        // Written for EVERY send, widget or app. Spec §4.2: tapping a widget and
        // then opening the app shows that send laddering, and this is what makes
        // it free rather than a second code path.
        val currentSend = CurrentSend(applicationContext)
        currentSend.start(sendId, msgId)

        return try {
            LoveButtonApi(BuildConfig.API_BASE_URL).send(enrolment.authToken, msgId, sendId)
            currentSend.update(sendId, WidgetState.SENT)
            // A delivered count of zero still counts as success: the send was
            // recorded, her phone just has no active device right now.
            settle(appWidgetId, WidgetState.SENT, Result.success())
        } catch (e: Exception) {
            // The tile says failed while WorkManager retries underneath. Showing
            // SENDING indefinitely would be worse: a tile that never resolves reads
            // as a broken app rather than a failed send.
            pending.forget(sendId)
            currentSend.update(sendId, WidgetState.FAILED)
            settle(appWidgetId, WidgetState.FAILED, Result.retry())
        }
    }

    /**
     * Shows a state for its hold time, then returns the tile to idle.
     *
     * SENT is held for the whole pending window rather than four seconds: it is
     * waiting for a receipt, and dropping to idle sooner would hide a delivered
     * that was about to arrive.
     *
     * The clear is guarded on the tile still SHOWING sent, not on the pending
     * entry still being live. Those look interchangeable and are not: the entry
     * is written before the request and `widgetFor` expires it at exactly
     * PENDING_WINDOW_MS, so after waiting that same window the lookup could
     * never return anything but null and the tile was never returned to idle.
     * Asking the tile what it is displaying has no such expiry, and still
     * protects the crimson or gold a receipt may have painted in the meantime.
     */
    private suspend fun settle(appWidgetId: Int, state: WidgetState, result: Result): Result {
        setWidgetState(applicationContext, appWidgetId, state)

        if (state == WidgetState.SENT) {
            delay(PENDING_WINDOW_MS)
            PendingSends(applicationContext).forget(mintedSendId)
            clearWidgetStateIf(applicationContext, appWidgetId, WidgetState.SENT)
            return result
        }

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
