package com.lovebutton.app.work

import android.appwidget.AppWidgetManager
import android.content.Context
import com.lovebutton.app.data.CurrentSend
import java.util.UUID

/**
 * Everything a tap does before the network is involved.
 *
 * The send id and the moment of the tap are minted **here**, not inside
 * [SendWorker], and that is the fix for the case where this phone has no signal.
 * The worker is held back by a connectivity constraint, so anything it owned did
 * not happen at all: no record was written, the focal area kept showing the last
 * send, and a tap on the app's own button produced nothing whatsoever. Writing
 * the record at the tap means the screen acknowledges the touch immediately and
 * the twenty-second timeout is measured from when you actually pressed it.
 *
 * Minting the id here also makes a retry honest. The worker used to generate a
 * fresh id on every attempt and call `start` again with it, so each retry reset
 * the record's clock; now every attempt carries the one id and the one timestamp
 * the tap created.
 *
 * Both tap paths go through this — the app's buttons and the widget's
 * `SendAction` — so there is one answer to what a tap means.
 */
suspend fun beginSend(
    context: Context,
    msgId: Int,
    appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
) {
    val sendId = UUID.randomUUID().toString()
    val tappedAt = System.currentTimeMillis()

    CurrentSend(context).start(sendId, msgId, tappedAt)
    SendWorker.enqueue(context, msgId, sendId, tappedAt, appWidgetId)
    TimeoutWorker.enqueue(context, appWidgetId)
}
