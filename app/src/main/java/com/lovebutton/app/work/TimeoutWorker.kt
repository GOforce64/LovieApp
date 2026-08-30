package com.lovebutton.app.work

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lovebutton.app.data.PENDING_WINDOW_MS
import com.lovebutton.app.widget.WidgetState
import com.lovebutton.app.widget.clearWidgetStateIf
import com.lovebutton.app.widget.failWidgetStateIfWaiting
import com.lovebutton.app.widget.holdMillis
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Unsticks a tile that is still waiting when the window closes.
 *
 * The app screen needs nothing like this — it derives its own timeout on every
 * read (see `SendSnapshot.displayState`). A widget cannot: it repaints only when
 * something pushes an update to it, so a tile left waiting stays waiting on
 * screen no matter how much time passes.
 *
 * Deliberately carries **no network constraint**, which is the whole point. The
 * send it shadows is held back until this phone has a connection, so a timeout
 * that waited for the same thing would never fire in precisely the case it exists
 * for — a tap made with no signal, which used to leave the tile crimson and
 * part-filled forever.
 *
 * Enqueued as delayed work rather than run as a `delay()` inside the send,
 * because the send may not have started, and because WorkManager persists
 * delayed work across the process death MIUI hands out as a matter of routine.
 */
class TimeoutWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        // A no-op when a receipt already moved the tile on, or when the tile is
        // gone. Only the run whose write is still on screen owns the clear.
        if (!failWidgetStateIfWaiting(applicationContext, appWidgetId)) return Result.success()

        WidgetState.FAILED.holdMillis?.let { hold ->
            delay(hold)
            clearWidgetStateIf(applicationContext, appWidgetId, WidgetState.FAILED)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_APP_WIDGET_ID = "app_widget_id"

        /**
         * Schedules the tile's giving-up point, one window from now.
         *
         * Only for sends that came from a tile. An app send has no tile to unstick
         * and derives its own timeout instead, so scheduling one would be work
         * that does nothing.
         */
        fun enqueue(context: Context, appWidgetId: Int) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<TimeoutWorker>()
                    .setInitialDelay(PENDING_WINDOW_MS, TimeUnit.MILLISECONDS)
                    .setInputData(
                        Data.Builder().putInt(KEY_APP_WIDGET_ID, appWidgetId).build()
                    )
                    .build()
            )
        }
    }
}
