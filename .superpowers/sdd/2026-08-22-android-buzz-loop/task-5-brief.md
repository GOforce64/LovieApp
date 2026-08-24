## Task 5: Enrolment screen

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/ui/EnrolScreen.kt`
- Modify: `app/src/main/java/com/lovebutton/app/MainActivity.kt`

**Interfaces:**
- Consumes: `LoveButtonApi`, `EnrolResult`, `Prefs` (Tasks 3, 4)
- Produces: `@Composable fun EnrolScreen(onEnrolled: () -> Unit)`

**Note:** this screen calls the Worker directly rather than through WorkManager. That is the one deliberate exception to the WorkManager rule: enrolment is a foreground action whose result the user is watching, and a deferred retry would be worse than an error message. Every background call still goes through WorkManager.

- [ ] **Step 1: Write `app/src/main/java/com/lovebutton/app/ui/EnrolScreen.kt`**

```kotlin
package com.lovebutton.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.lovebutton.app.BuildConfig
import com.lovebutton.app.data.EnrolResult
import com.lovebutton.app.data.LoveButtonApi
import com.lovebutton.app.data.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun EnrolScreen(onEnrolled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val api = remember { LoveButtonApi(BuildConfig.API_BASE_URL) }

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Enter your code", style = MaterialTheme.typography.headlineMedium)
        Text(
            "You only do this once on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim() },
            label = { Text("Enrolment code") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        } else {
            Button(
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            // The FCM token is required at enrolment so the server
                            // can push to this phone immediately, without waiting
                            // for a separate registration call.
                            val fcmToken = FirebaseMessaging.getInstance().token.await()
                            val label = "${Build.MANUFACTURER} ${Build.MODEL}"

                            when (val result = api.enrol(code, fcmToken, label)) {
                                is EnrolResult.Ok -> {
                                    prefs.saveEnrolment(
                                        result.authToken,
                                        result.person,
                                        result.partnerName,
                                    )
                                    onEnrolled()
                                }
                                EnrolResult.InvalidCode ->
                                    error = "That code is not valid."
                                EnrolResult.RateLimited ->
                                    error = "Too many attempts. Try again in an hour."
                                is EnrolResult.Failed ->
                                    error = result.message
                            }
                        } catch (e: Exception) {
                            error = "Could not get a push token from Google. " +
                                "Check that Play Services is available."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = code.length >= 8,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Enrol this phone")
            }
        }
    }
}
```

- [ ] **Step 2: Add the coroutines-play-services dependency**

The `.await()` above needs it. In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
coroutinesPlayServices = "1.10.2"
```

and under `[libraries]`:

```toml
kotlinx-coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutinesPlayServices" }
```

In `app/build.gradle.kts`, add to `dependencies`:

```kotlin
    implementation(libs.kotlinx.coroutines.play.services)
```

- [ ] **Step 3: Rewrite `MainActivity` to route between screens**

```kotlin
package com.lovebutton.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lovebutton.app.data.Prefs
import com.lovebutton.app.ui.EnrolScreen
import com.lovebutton.app.ui.LoveButtonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LoveButtonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }
}

/**
 * The whole navigation model: enrolled or not.
 *
 * `null` means DataStore has not answered yet, which is different from "not
 * enrolled" — showing the enrol screen during that gap would make an enrolled
 * phone flash the code prompt on every launch.
 */
@Composable
private fun Root() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val enrolment by prefs.enrolment.collectAsState(initial = null)
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        prefs.current()
        loaded = true
    }

    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        enrolment == null -> EnrolScreen(onEnrolled = { /* state flow re-emits */ })
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Paired with ${enrolment!!.partnerName}")
        }
    }
}
```

- [ ] **Step 4: Build and install**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Manual verification (requires the deployed Worker from Task 0)**

Set `API_BASE_URL` in `app/build.gradle.kts` to the real Worker URL first, then install and check each:

1. Launch on a clean install → the code prompt appears.
2. Enter a wrong code → "That code is not valid." and you stay on the screen.
3. Enter a wrong code six times → "Too many attempts."
4. Enter the real code → the screen changes to "Paired with &lt;name&gt;".
5. **Force-quit and relaunch → it goes straight to "Paired with", not back to the code prompt.** This is what proves DataStore persisted.
6. `adb logcat | grep -i "auth_token\|Bearer"` while doing all of the above → **no hits.** The token must never appear in logs.

- [ ] **Step 6: Commit**

```bash
git add app/ gradle/
git commit -m "feat(app): add enrolment screen and persistence gate"
```

---

