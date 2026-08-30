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
import com.lovebutton.app.data.windowClosed
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

        // Both minted at the tap, by beginSend. The id has to outlive a retry —
        // a fresh one per attempt would reset the record's clock — and the
        // timestamp has to be when the user actually pressed it, not when the
        // connection finally showed up.
        val sendId = inputData.getString(KEY_SEND_ID) ?: return Result.failure()
        val tappedAt = inputData.getLong(KEY_TAPPED_AT, 0L)
        mintedSendId = sendId

        // The window closed while this was queued behind a connection that took
        // too long. The screen gave up on this send twenty seconds ago and went
        // grey, so sending it now would buzz her phone for something the sender
        // has already been told did not happen. Abandoned, not retried: a tap is
        // only worth honouring while the person who made it is still expecting it.
        if (windowClosed(tappedAt)) return Result.success()

        // Recorded BEFORE the request. A receipt can beat the send response, and
        // a mapping written afterwards would miss it.
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            pending.remember(sendId, appWidgetId)
        }

        val currentSend = CurrentSend(applicationContext)

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
     * What happens to the tile at the *end* of that window is no longer decided
     * here — [TimeoutWorker] owns it, and greys the tile out rather than
     * returning it to idle. This waits the window out only to forget the pending
     * entry afterwards. The guard that used to live here, asking the tile what it
     * is displaying rather than whether the pending entry is still live, moved
     * with the decision: the entry is written before the request and `widgetFor`
     * expires it at exactly PENDING_WINDOW_MS, so after waiting that same window
     * a lookup could never answer anything but "gone", and a guard built on it
     * would never fire.
     */
    private suspend fun settle(appWidgetId: Int, state: WidgetState, result: Result): Result {
        setWidgetState(applicationContext, appWidgetId, state)

        if (state == WidgetState.SENT) {
            delay(PENDING_WINDOW_MS)
            PendingSends(applicationContext).forget(mintedSendId)
            // The tile is deliberately NOT cleared here any more. TimeoutWorker
            // fires at this same moment and owns what a tile does when its window
            // closes — and the two disagree: this used to return it to idle,
            // where a send nothing ever acknowledged must now go grey. Two
            // writers racing on one tile at the same millisecond is a coin toss,
            // so there is one writer. A receipt that landed in the meantime makes
            // both of them a no-op regardless.
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
        private const val KEY_SEND_ID = "send_id"
        private const val KEY_TAPPED_AT = "tapped_at"

        /**
         * Not called directly from a tap — go through [beginSend], which mints
         * the id and the timestamp this needs and writes the record they belong to.
         *
         * @param appWidgetId the tile to report back to, or INVALID_APPWIDGET_ID
         *   when the send came from the app's own home screen rather than a widget.
         */
        fun enqueue(
            context: Context,
            msgId: Int,
            sendId: String,
            tappedAt: Long,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(
                    Data.Builder()
                        .putInt(KEY_MSG_ID, msgId)
                        .putInt(KEY_APP_WIDGET_ID, appWidgetId)
                        .putString(KEY_SEND_ID, sendId)
                        .putLong(KEY_TAPPED_AT, tappedAt)
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
