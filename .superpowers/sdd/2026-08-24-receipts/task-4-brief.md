## Task 4: Her phone reports delivered and seen

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/work/ReceiptWorker.kt`
- Modify: `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`
- Modify: `app/src/main/java/com/lovebutton/app/push/PushService.kt`
- Modify: `app/src/main/java/com/lovebutton/app/push/Notifications.kt`
- Modify: `app/src/main/java/com/lovebutton/app/MainActivity.kt`

**Interfaces:**
- Consumes: `Prefs.current()`, `postMessageNotification`
- Produces: `LoveButtonApi.receipt(authToken: String, sendId: String, state: String): Boolean`; `ReceiptWorker.enqueue(context: Context, sendId: String, state: String)`; `const val EXTRA_SEND_ID = "send_id"`

- [ ] **Step 1: Add the receipt call to the API client**

In `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`, add beside `registerDevice`:

```kotlin
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
```

And in `ApiModels.kt`:

```kotlin
@Serializable
data class ReceiptRequest(
    @SerialName("send_id") val sendId: String,
    val state: String,
)
```

- [ ] **Step 2: Write `work/ReceiptWorker.kt`**

```kotlin
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
            val ok = LoveButtonApi(BuildConfig.API_BASE_URL)
                .receipt(enrolment.authToken, sendId, state)
            // A rejected receipt is not worth retrying: it means this device is
            // not the recipient, or the send has been purged. Only a thrown
            // network error is.
            Result.success().takeIf { ok } ?: Result.success()
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
```

- [ ] **Step 3: Report delivered when a message arrives**

In `app/src/main/java/com/lovebutton/app/push/PushService.kt`, replace the `"msg"` branch:

```kotlin
            "msg" -> {
                val msgId = data["msg_id"]?.toIntOrNull() ?: return
                val fromName = data["from_name"] ?: "Someone"
                val sendId = data["send_id"]
                postMessageNotification(applicationContext, msgId, fromName, sendId)
                // After posting, not before: the receipt says "it arrived and she
                // can see it", and a receipt for a notification that failed to
                // post would be a lie.
                if (sendId != null) {
                    ReceiptWorker.enqueue(applicationContext, sendId, "delivered")
                }
            }
```

Add the import:

```kotlin
import com.lovebutton.app.work.ReceiptWorker
```

- [ ] **Step 4: Carry the send id into the notification's tap intent**

In `app/src/main/java/com/lovebutton/app/push/Notifications.kt`, change the signature and the intent:

```kotlin
/** Extra carrying the send id from a notification tap into MainActivity. */
const val EXTRA_SEND_ID = "send_id"

fun postMessageNotification(
    context: Context,
    msgId: Int,
    fromName: String,
    sendId: String? = null,
) {
```

and replace the `openApp` PendingIntent:

```kotlin
    val openApp = PendingIntent.getActivity(
        context,
        // A per-send request code: with a constant one, FLAG_UPDATE_CURRENT would
        // rewrite every earlier notification's intent to carry the newest send id,
        // so tapping an older notification would report "seen" for the wrong send.
        sendId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SEND_ID, sendId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
```

- [ ] **Step 5: Report seen when the notification is tapped**

In `app/src/main/java/com/lovebutton/app/MainActivity.kt`, add to `onCreate` before `setContent`:

```kotlin
        reportSeenFrom(intent)
```

and add these to the class:

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is single-instance in practice: a second tap arrives here
        // rather than through onCreate, and without this it would never report.
        setIntent(intent)
        reportSeenFrom(intent)
    }

    private fun reportSeenFrom(intent: Intent?) {
        val sendId = intent?.getStringExtra(EXTRA_SEND_ID) ?: return
        ReceiptWorker.enqueue(this, sendId, "seen")
        // Clear it so a configuration change does not report the same tap twice.
        intent.removeExtra(EXTRA_SEND_ID)
    }
```

with imports:

```kotlin
import android.content.Intent
import com.lovebutton.app.push.EXTRA_SEND_ID
import com.lovebutton.app.work.ReceiptWorker
```

- [ ] **Step 6: Build and run the suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 21 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app
git commit -m "feat(app): report delivered on arrival and seen on tap"
```

---

