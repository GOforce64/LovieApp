# Love Button — Android Buzz Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap a button in the app on one phone and hear the other phone buzz, verified on two real Xiaomi devices and still working after a night in Doze.

**Architecture:** A minimal Compose app that enrols once with a code, stores the returned bearer token in DataStore, registers its FCM token with the Worker, sends message ids through WorkManager, and receives data-only pushes in a `FirebaseMessagingService` that posts a notification on its own channel. A setup screen deep-links into MIUI's Autostart and battery pages and re-verifies on every launch.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp + kotlinx.serialization, DataStore Preferences, WorkManager, Firebase Cloud Messaging. `minSdk 26`.

**Spec:** `love-button-spec.md` (sections 6, 8, 9, 10, 11)

**Depends on:** `docs/superpowers/plans/2026-08-21-worker-core.md`. That plan's Task 0 and Task 10 Steps 1-9 (create the Firebase project and Cloudflare resources, apply the remote migration, set the secrets, deploy) **must be complete before Task 6 of this plan**, because from Task 6 onward the app talks to a live Worker. Tasks 1-5 can be built and unit-tested without it.

## Global Constraints

- **`minSdk 26`**, package `com.lovebutton.app`. Notification channels require API 26.
- **Never commit secrets.** `app/google-services.json` is gitignored; commit `app/google-services.json.example` with placeholder values.
- **The device bearer token lives in DataStore and is never logged.** Not in Logcat, not in a crash report, not in an error message shown on screen.
- **Every network call goes through WorkManager, never inline.** Android can kill the app or the widget host mid-request; WorkManager retries when connectivity returns. On MIUI this is not optional.
- **Notification channel sounds are frozen at creation** (spec §6.3). This plan must NOT create the final `msg_1`…`msg_4` channels, because their sounds are not chosen until milestone 4. It creates one deliberately temporary channel, `dev_buzz_v1`, which Plan 3 deletes and replaces.
- **Pushes are data-only.** The payload carries `msg_id`, never text. The app maps id → text locally.
- **Request `POST_NOTIFICATIONS` on Android 13+** and handle refusal gracefully.
- **Testing follows spec §11:** JVM unit tests for pure logic (message mapping, API client request/response shapes); notification, UI and MIUI behaviour verified by hand on real hardware. **The overnight test in Task 10 is a required gate, not optional.**
- **No message text on the wire.** The Worker never learns what message 3 says.

---

## A note on dependency versions

Task 1 pins concrete versions in `gradle/libs.versions.toml`. If any version fails to resolve, take the latest stable release of that artifact, use it, and **report the substitution in your task report** — do not downgrade Kotlin or AGP to make an old version fit, and do not drop a dependency to avoid the problem.

**The Gradle wrapper version is load-bearing and was found the hard way.** On a machine
whose only JDK is 26, the window is narrow: Gradle 8.13 will not start at all under
JDK 26, and Gradle 9.6+ removes an internal API that AGP 8.13.0 still calls. **Gradle
9.5.0 is the version that satisfies both.** If you change the AGP or JDK version, expect
to move this too — and note that the failure at each end looks nothing like a version
problem (a startup crash on one side, a missing-class error deep in AGP on the other).

## Environment prerequisite

**An Android SDK must be installed before any task in this plan can be verified.**
Gradle's Android plugin requires it even to run JVM unit tests, so Tasks 2 and 3 cannot
be checked without it either. Install Android Studio (which bundles the SDK) or the
command-line tools, and ensure `ANDROID_HOME` is set or `local.properties` contains
`sdk.dir`. `local.properties` is machine-specific and gitignored — never commit it.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts` | Root Gradle config, plugin versions |
| `gradle/libs.versions.toml` | Version catalog — one place for every dependency version |
| `app/build.gradle.kts` | Module config, Compose, dependencies |
| `app/src/main/AndroidManifest.xml` | Permissions, activity, FCM service |
| `app/src/main/java/.../LoveButtonApp.kt` | `Application`; creates the notification channel once |
| `app/src/main/java/.../MainActivity.kt` | Single activity; routes between the three screens |
| `app/src/main/java/.../data/Messages.kt` | The local message catalogue — id → text, channel |
| `app/src/main/java/.../data/ApiModels.kt` | Request/response data classes |
| `app/src/main/java/.../data/LoveButtonApi.kt` | OkHttp client; enrol, register device, send |
| `app/src/main/java/.../data/Prefs.kt` | DataStore — bearer token, person, partner name |
| `app/src/main/java/.../push/Notifications.kt` | Channel creation and notification posting |
| `app/src/main/java/.../push/PushService.kt` | `FirebaseMessagingService` |
| `app/src/main/java/.../work/RegisterTokenWorker.kt` | Sends a rotated FCM token to the Worker |
| `app/src/main/java/.../work/SendWorker.kt` | Performs `POST /v1/send` off the UI thread |
| `app/src/main/java/.../device/DeviceSetup.kt` | Battery/Autostart checks and MIUI deep links |
| `app/src/main/java/.../ui/*.kt` | Enrol, Home and Setup screens; theme |
| `app/src/test/java/.../*Test.kt` | JVM unit tests |

Split by responsibility rather than layer: everything about talking to the Worker lives in `data/`, everything about receiving a push lives in `push/`.

---

## Task 0: Prerequisites (human, not agent)

**Done by Giorgos, not by an implementing agent.**

- [ ] **Step 1: Finish the Worker's outstanding items**

From `docs/superpowers/plans/2026-08-21-worker-core.md`: Task 0 in full, then Task 10 Steps 1-3 (set the three secrets, replace the placeholder ids in `wrangler.toml`, deploy), and `cd server && npm run migrate:remote`.

Confirm with:

```bash
curl https://love-button.<subdomain>.workers.dev/health
```

Expected: `{"ok":true}`

- [ ] **Step 2: Put `google-services.json` in place**

From the Firebase console, download it for package name `com.lovebutton.app` into `app/google-services.json`. It is gitignored.

- [ ] **Step 3: Install Android Studio and prepare two devices**

Two physical Android phones with USB debugging enabled. The emulator's Play Services are unreliable for FCM. Confirm both run a Global/EEA ROM, not a China ROM — China ROMs have no Play Services and FCM cannot work there at all.

**Verification:** `/health` returns `{"ok":true}`, `app/google-services.json` exists, and `adb devices` lists two devices.

---

## Task 1: Gradle scaffold and a blank app that installs

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/com/lovebutton/app/MainActivity.kt`, `app/src/main/java/com/lovebutton/app/ui/Theme.kt`
- Create: `app/google-services.json.example`
- Modify: `.gitignore` (repo root)

**Interfaces:**
- Consumes: nothing
- Produces: a Gradle project that assembles and installs; `LoveButtonTheme` composable

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LoveButton"
include(":app")
```

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.0"
googleServices = "4.4.4"
coreKtx = "1.17.0"
lifecycle = "2.9.4"
activityCompose = "1.11.0"
composeBom = "2026.08.00"
firebaseBom = "34.4.0"
okhttp = "5.2.0"
serialization = "1.9.0"
datastore = "1.1.7"
workManager = "2.11.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging-ktx" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

- [ ] **Step 3: Write the root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.lovebutton.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lovebutton.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // The Worker's base URL. Replaced with the real deployment URL in Task 6.
        buildConfigField("String", "API_BASE_URL", "\"https://example.invalid\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 6: Write `app/proguard-rules.pro`**

```
# kotlinx.serialization keeps its generated serializers via annotations.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.lovebutton.app.data.** {
    *** Companion;
}
```

- [ ] **Step 7: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Android 13+ requires the user to grant notification posting explicitly. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@android:drawable/ic_dialog_email"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.LoveButton">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.LoveButton">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

- [ ] **Step 8: Write `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Love Button</string>
</resources>
```

- [ ] **Step 9: Write `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LoveButton" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 10: Write `app/src/main/java/com/lovebutton/app/ui/Theme.kt`**

```kotlin
package com.lovebutton.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pink = Color(0xFFD81B60)
private val PalePink = Color(0xFFF8BBD0)

private val Scheme = lightColorScheme(
    primary = Pink,
    secondary = PalePink,
)

@Composable
fun LoveButtonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
```

- [ ] **Step 11: Write `app/src/main/java/com/lovebutton/app/MainActivity.kt`**

```kotlin
package com.lovebutton.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lovebutton.app.ui.LoveButtonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LoveButtonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Love Button")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 12: Write `app/google-services.json.example`**

```json
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "your-firebase-project-id",
    "storage_bucket": "your-firebase-project-id.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000000000",
        "android_client_info": { "package_name": "com.lovebutton.app" }
      },
      "api_key": [{ "current_key": "REPLACE_WITH_YOUR_ANDROID_API_KEY" }],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
```

- [ ] **Step 13: Extend the repo-root `.gitignore`**

Append to the existing `.gitignore`:

```
# android
*.iml
.idea/
local.properties
captures/
.externalNativeBuild/
.cxx/
app/build/
```

- [ ] **Step 14: Build and install**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If a dependency version does not resolve, substitute the latest stable and report it.

Then, with a device attached: `./gradlew :app:installDebug` and open the app.
Expected: a white screen reading "Love Button".

- [ ] **Step 15: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ app/ .gitignore
git commit -m "feat(app): scaffold Compose app that builds and installs"
```

---

## Task 2: The message catalogue

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/Messages.kt`
- Create: `app/src/test/java/com/lovebutton/app/MessagesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class LoveMessage(val id: Int, val text: String, val channelId: String)`
  - `val MESSAGES: List<LoveMessage>`
  - `fun messageForId(id: Int): LoveMessage?`
  - `const val DEV_CHANNEL_ID = "dev_buzz_v1"`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/MessagesTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.data.messageForId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {

    @Test
    fun `catalogue has the four spec messages with ids 1 to 4`() {
        assertEquals(4, MESSAGES.size)
        assertEquals(listOf(1, 2, 3, 4), MESSAGES.map { it.id })
    }

    @Test
    fun `each message has non-blank text`() {
        MESSAGES.forEach { message ->
            assert(message.text.isNotBlank()) { "message ${message.id} has blank text" }
        }
    }

    @Test
    fun `messageForId returns the matching message`() {
        assertEquals("I love you", messageForId(1)?.text)
        assertEquals("Call me when you can", messageForId(4)?.text)
    }

    @Test
    fun `messageForId returns null for an unknown id`() {
        // The server validates msg_id too, but a push could still carry an id this
        // build does not know about — an older app receiving a newer message. The
        // receiving code must be able to detect that rather than crash.
        assertNull(messageForId(0))
        assertNull(messageForId(5))
        assertNull(messageForId(-1))
    }

    @Test
    fun `every message uses the temporary dev channel for now`() {
        // Channel sounds are frozen at creation (spec 6.3), so the real per-message
        // channels are not created until their sounds are final in milestone 4.
        MESSAGES.forEach { message ->
            assertEquals(DEV_CHANNEL_ID, message.channelId)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"`
Expected: FAIL — unresolved reference `com.lovebutton.app.data`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/lovebutton/app/data/Messages.kt`:

```kotlin
package com.lovebutton.app.data

/**
 * The message catalogue lives in the app, not on the server.
 *
 * A push carries `msg_id: 3`, never the words. Two consequences: the text never
 * transits Google's servers, and adding a fifth message is an app-only change.
 * The server keeps its own allowlist of valid ids and nothing else.
 */
data class LoveMessage(
    val id: Int,
    val text: String,
    val channelId: String,
)

/**
 * A deliberately temporary notification channel.
 *
 * Android freezes a channel's sound when the channel is created and will not let
 * you change it afterwards (spec 6.3). The four real sounds are not chosen until
 * milestone 4, so creating `msg_1`..`msg_4` now would burn those channel ids with
 * the default sound permanently. This throwaway id is deleted and replaced when
 * the real channels arrive.
 */
const val DEV_CHANNEL_ID = "dev_buzz_v1"

val MESSAGES: List<LoveMessage> = listOf(
    LoveMessage(1, "I love you", DEV_CHANNEL_ID),
    LoveMessage(2, "Thinking of you", DEV_CHANNEL_ID),
    LoveMessage(3, "Miss you", DEV_CHANNEL_ID),
    LoveMessage(4, "Call me when you can", DEV_CHANNEL_ID),
)

/** Null when this build does not know the id — an older app, a newer message. */
fun messageForId(id: Int): LoveMessage? = MESSAGES.firstOrNull { it.id == id }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"`
Expected: all five tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/Messages.kt app/src/test/java/com/lovebutton/app/MessagesTest.kt
git commit -m "feat(app): add local message catalogue"
```

---

## Task 3: API models and client

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`
- Create: `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`
- Create: `app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class LoveButtonApi(baseUrl: String, client: OkHttpClient = OkHttpClient())`
  - `suspend fun enrol(code: String, fcmToken: String, label: String): EnrolResult`
  - `suspend fun registerDevice(authToken: String, fcmToken: String): Boolean`
  - `suspend fun send(authToken: String, msgId: Int): SendResult`
  - `sealed interface EnrolResult { data class Ok(...); data object InvalidCode; data object RateLimited; data class Failed(val message: String) }`
  - `data class SendResult(val sendId: String, val delivered: Int)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt`:

```kotlin
package com.lovebutton.app

import com.lovebutton.app.data.EnrolResult
import com.lovebutton.app.data.LoveButtonApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoveButtonApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LoveButtonApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = LoveButtonApi(server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    @Test
    fun `enrol posts the code and parses the token`() = runBlocking {
        server.enqueue(
            json("""{"device_id":"d1","auth_token":"tok","person":2,"partner_name":"Giorgos"}""")
        )

        val result = api.enrol("secret-code", "fcm-1", "her phone")

        assertTrue(result is EnrolResult.Ok)
        result as EnrolResult.Ok
        assertEquals("tok", result.authToken)
        assertEquals(2, result.person)
        assertEquals("Giorgos", result.partnerName)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/enroll", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))

        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"code\":\"secret-code\""))
        assertTrue(sent.contains("\"fcm_token\":\"fcm-1\""))
    }

    @Test
    fun `enrol maps 403 to InvalidCode`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_code","message":"nope"}""", 403))

        assertEquals(EnrolResult.InvalidCode, api.enrol("wrong", "fcm-1", "phone"))
    }

    @Test
    fun `enrol maps 429 to RateLimited`() = runBlocking {
        server.enqueue(json("""{"error":"rate_limited","message":"slow down"}""", 429))

        assertEquals(EnrolResult.RateLimited, api.enrol("code", "fcm-1", "phone"))
    }

    @Test
    fun `enrol maps an unexpected status to Failed`() = runBlocking {
        server.enqueue(json("""{"error":"boom","message":"server exploded"}""", 500))

        val result = api.enrol("code", "fcm-1", "phone")

        assertTrue(result is EnrolResult.Failed)
    }

    @Test
    fun `send posts only the message id and never a recipient`() = runBlocking {
        server.enqueue(json("""{"send_id":"s1","delivered":1}"""))

        val result = api.send("tok", 3)

        assertEquals("s1", result.sendId)
        assertEquals(1, result.delivered)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/send", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))

        // Invariant 2 lives on the server, but the client must not even try to
        // name a recipient — if this body ever grows a to_person field, the
        // server ignores it and this test is the reminder of why.
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"msg_id\":3"))
        assertFalse(sent.contains("to_person"))
        assertFalse(sent.contains("from_person"))
    }

    @Test
    fun `send reports delivered zero without throwing`() = runBlocking {
        // The server returns 200 with delivered 0 when her phone has no active
        // device. That is information, not a failure, and must not raise.
        server.enqueue(json("""{"send_id":"s2","delivered":0}"""))

        assertEquals(0, api.send("tok", 1).delivered)
    }

    @Test
    fun `registerDevice sends the bearer token and reports success`() = runBlocking {
        server.enqueue(json("""{"ok":true}"""))

        assertTrue(api.registerDevice("tok", "fcm-new"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/devices", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"fcm_token\":\"fcm-new\""))
    }

    @Test
    fun `registerDevice reports failure on 401`() = runBlocking {
        server.enqueue(json("""{"error":"unauthorized","message":"no"}""", 401))

        assertFalse(api.registerDevice("stale-token", "fcm-new"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.LoveButtonApiTest"`
Expected: FAIL — unresolved reference `LoveButtonApi`

- [ ] **Step 3: Write `app/src/main/java/com/lovebutton/app/data/ApiModels.kt`**

```kotlin
package com.lovebutton.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EnrolRequest(
    val code: String,
    @SerialName("fcm_token") val fcmToken: String,
    val label: String,
)

@Serializable
data class EnrolResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("auth_token") val authToken: String,
    val person: Int,
    @SerialName("partner_name") val partnerName: String,
)

@Serializable
data class DeviceRequest(
    @SerialName("fcm_token") val fcmToken: String,
)

/** No recipient field, deliberately. The server derives it (spec section 4). */
@Serializable
data class SendRequest(
    @SerialName("msg_id") val msgId: Int,
)

@Serializable
data class SendResponse(
    @SerialName("send_id") val sendId: String,
    val delivered: Int,
)

@Serializable
data class ApiError(
    val error: String = "unknown",
    val message: String = "",
)

/** What the caller of [LoveButtonApi.send] actually needs. */
data class SendResult(val sendId: String, val delivered: Int)

/**
 * Enrolment has three outcomes worth telling apart on screen: it worked, the code
 * was wrong, or you have tried too many times. Everything else is lumped into
 * Failed with a message, because there is nothing useful for the user to do about
 * it beyond try again later.
 */
sealed interface EnrolResult {
    data class Ok(
        val deviceId: String,
        val authToken: String,
        val person: Int,
        val partnerName: String,
    ) : EnrolResult

    data object InvalidCode : EnrolResult
    data object RateLimited : EnrolResult
    data class Failed(val message: String) : EnrolResult
}
```

- [ ] **Step 4: Write `app/src/main/java/com/lovebutton/app/data/LoveButtonApi.kt`**

```kotlin
package com.lovebutton.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Every call the app makes to the Worker.
 *
 * Deliberately small and dependency-light: OkHttp plus kotlinx.serialization, no
 * Retrofit. There are three endpoints, and being able to read the whole client in
 * one sitting is worth more here than the boilerplate a framework would save.
 *
 * Nothing in this class ever logs `authToken`. It is the only credential the app
 * holds, and Logcat is readable by anyone with adb.
 */
class LoveButtonApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun post(path: String, body: String, authToken: String?): Request {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")

        if (authToken != null) {
            builder.header("Authorization", "Bearer $authToken")
        }
        return builder.build()
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    /** Trades an enrolment code for a device bearer token. Called once per phone. */
    suspend fun enrol(code: String, fcmToken: String, label: String): EnrolResult {
        val body = json.encodeToString(EnrolRequest(code, fcmToken, label))

        return try {
            execute(post("/v1/enroll", body, authToken = null)).use { response ->
                val text = response.body?.string().orEmpty()

                when (response.code) {
                    200 -> {
                        val parsed = json.decodeFromString<EnrolResponse>(text)
                        EnrolResult.Ok(
                            deviceId = parsed.deviceId,
                            authToken = parsed.authToken,
                            person = parsed.person,
                            partnerName = parsed.partnerName,
                        )
                    }
                    403 -> EnrolResult.InvalidCode
                    429 -> EnrolResult.RateLimited
                    else -> EnrolResult.Failed(errorMessage(text, response.code))
                }
            }
        } catch (e: IOException) {
            EnrolResult.Failed("Could not reach the server. Check your connection.")
        }
    }

    /**
     * Refreshes the FCM token the server pushes to. Returns false when the server
     * rejects the bearer token, which means this device was deregistered and must
     * enrol again.
     */
    suspend fun registerDevice(authToken: String, fcmToken: String): Boolean {
        val body = json.encodeToString(DeviceRequest(fcmToken))

        return try {
            execute(post("/v1/devices", body, authToken)).use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Sends one message. The body carries a message id and nothing else — there is
     * no field naming a recipient, because the server derives it.
     *
     * Throws on failure so the calling WorkManager job can retry. A `delivered` of
     * zero is NOT a failure: it means her phone has no active device, which the UI
     * reports differently from a network error.
     */
    suspend fun send(authToken: String, msgId: Int): SendResult {
        val body = json.encodeToString(SendRequest(msgId))

        execute(post("/v1/send", body, authToken)).use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("send failed: ${errorMessage(text, response.code)}")
            }

            val parsed = json.decodeFromString<SendResponse>(text)
            return SendResult(parsed.sendId, parsed.delivered)
        }
    }

    private fun errorMessage(text: String, code: Int): String = try {
        json.decodeFromString<ApiError>(text).message.ifBlank { "HTTP $code" }
    } catch (e: Exception) {
        "HTTP $code"
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.LoveButtonApiTest"`
Expected: all eight tests PASS

- [ ] **Step 6: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all 13 tests PASS (8 new + 5 from Task 2)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/ app/src/test/java/com/lovebutton/app/LoveButtonApiTest.kt
git commit -m "feat(app): add API models and Worker client"
```

---

## Task 4: Local state in DataStore

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/data/Prefs.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class Prefs(context: Context)`
  - `val enrolment: Flow<Enrolment?>`
  - `suspend fun saveEnrolment(authToken: String, person: Int, partnerName: String)`
  - `suspend fun clearEnrolment()`
  - `suspend fun current(): Enrolment?`
  - `data class Enrolment(val authToken: String, val person: Int, val partnerName: String)`

- [ ] **Step 1: Write the implementation**

There is no unit test for this task. DataStore needs an Android `Context`, so testing it requires either Robolectric or an instrumented test — a dependency and a device round-trip out of proportion to a class that reads and writes three keys. Spec §11 puts the testing effort on message mapping and the API client instead, both of which are covered. It is exercised end to end from Task 5 onward: if enrolment did not persist, the app would ask for the code on every launch, which Task 5's verification checks explicitly.

`app/src/main/java/com/lovebutton/app/data/Prefs.kt`:

```kotlin
package com.lovebutton.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "love_button")

/** Everything this phone knows about itself once enrolled. */
data class Enrolment(
    val authToken: String,
    val person: Int,
    val partnerName: String,
)

/**
 * The app's local state: three values, written once at enrolment.
 *
 * The bearer token lives here rather than in EncryptedSharedPreferences, which is
 * deprecated. On a non-rooted device, app-private storage is already the boundary
 * that matters — another app cannot read this file. The token is never logged and
 * never shown on screen.
 */
class Prefs(private val context: Context) {

    private object Keys {
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val PERSON = intPreferencesKey("person")
        val PARTNER_NAME = stringPreferencesKey("partner_name")
    }

    /** Null until this phone has enrolled. */
    val enrolment: Flow<Enrolment?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.AUTH_TOKEN]
        val person = prefs[Keys.PERSON]
        val partner = prefs[Keys.PARTNER_NAME]

        if (token != null && person != null && partner != null) {
            Enrolment(token, person, partner)
        } else {
            null
        }
    }

    suspend fun current(): Enrolment? = enrolment.first()

    suspend fun saveEnrolment(authToken: String, person: Int, partnerName: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_TOKEN] = authToken
            prefs[Keys.PERSON] = person
            prefs[Keys.PARTNER_NAME] = partnerName
        }
    }

    /** Used on sign-out, and when the server rejects our token as unknown. */
    suspend fun clearEnrolment() {
        context.dataStore.edit { it.clear() }
    }
}
```

- [ ] **Step 2: Confirm it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/data/Prefs.kt
git commit -m "feat(app): store enrolment in DataStore"
```

---

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

## Task 6: Receiving pushes and posting the notification — the buzz

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/push/Notifications.kt`
- Create: `app/src/main/java/com/lovebutton/app/push/PushService.kt`
- Create: `app/src/main/java/com/lovebutton/app/work/RegisterTokenWorker.kt`
- Create: `app/src/main/java/com/lovebutton/app/LoveButtonApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `messageForId`, `DEV_CHANNEL_ID` (Task 2); `LoveButtonApi` (Task 3); `Prefs` (Task 4)
- Produces: `fun ensureChannel(context: Context)`, `fun postMessageNotification(context: Context, msgId: Int, fromName: String)`

- [ ] **Step 1: Write `push/Notifications.kt`**

```kotlin
package com.lovebutton.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lovebutton.app.MainActivity
import com.lovebutton.app.data.DEV_CHANNEL_ID
import com.lovebutton.app.data.messageForId
import java.util.concurrent.atomic.AtomicInteger

private val notificationCounter = AtomicInteger(1)

/**
 * Creates the temporary development channel.
 *
 * Android freezes a channel's sound at creation and will not let you change it
 * afterwards. The four real sounds are not chosen until milestone 4, so this uses
 * a throwaway channel id that milestone 4 deletes — creating `msg_1` now would
 * permanently weld the default sound to it.
 */
fun ensureChannel(context: Context) {
    val channel = NotificationChannel(
        DEV_CHANNEL_ID,
        "Messages (temporary)",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Provisional channel used until the real sounds are chosen."
        enableVibration(true)
    }

    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

/**
 * Posts one notification for a received message.
 *
 * Each gets a unique id so rapid sends stack rather than replacing one another —
 * four taps should feel like four messages, not one that keeps changing.
 */
fun postMessageNotification(context: Context, msgId: Int, fromName: String) {
    val message = messageForId(msgId)
    val text = message?.text ?: "New message"
    val channelId = message?.channelId ?: DEV_CHANNEL_ID

    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(fromName)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openApp)
        .build()

    // On Android 13+ this silently does nothing without POST_NOTIFICATIONS.
    // areNotificationsEnabled() is what the Setup screen checks in Task 8.
    NotificationManagerCompat.from(context)
        .notify(notificationCounter.getAndIncrement(), notification)
}
```

- [ ] **Step 2: Add the androidx.core dependency for NotificationCompat**

Already present via `androidx-core-ktx`. No change needed.

- [ ] **Step 3: Write `work/RegisterTokenWorker.kt`**

```kotlin
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
```

- [ ] **Step 4: Write `push/PushService.kt`**

```kotlin
package com.lovebutton.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lovebutton.app.work.RegisterTokenWorker

/**
 * Receives data-only pushes.
 *
 * Because the Worker never sends a `notification` block, every message lands here
 * rather than being rendered by the system tray — which is what lets the app
 * choose the channel, and therefore the sound.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // FCM tokens rotate. If the server still has the old one, pushes go
        // nowhere silently, so this has to be reliable rather than best-effort.
        RegisterTokenWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        when (data["type"]) {
            "msg" -> {
                val msgId = data["msg_id"]?.toIntOrNull() ?: return
                val fromName = data["from_name"] ?: "Someone"
                postMessageNotification(applicationContext, msgId, fromName)
            }
            // "receipt" arrives in a later plan and must never post a notification.
            else -> Unit
        }
    }
}
```

- [ ] **Step 5: Write `LoveButtonApp.kt`**

```kotlin
package com.lovebutton.app

import android.app.Application
import com.lovebutton.app.push.ensureChannel

class LoveButtonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Creating a channel that already exists is a no-op, so this is safe on
        // every launch and guarantees the channel exists before the first push.
        ensureChannel(this)
    }
}
```

- [ ] **Step 6: Register the application class and the service in the manifest**

In `app/src/main/AndroidManifest.xml`, add `android:name=".LoveButtonApp"` to the `<application>` tag, and add this inside `<application>`:

```xml
        <service
            android:name=".push.PushService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
```

- [ ] **Step 7: Set the real API base URL**

In `app/build.gradle.kts`, replace the placeholder:

```kotlin
        buildConfigField("String", "API_BASE_URL", "\"https://love-button.<subdomain>.workers.dev\"")
```

Use the actual URL from the Worker deployment. No trailing slash.

- [ ] **Step 8: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: THE MILESTONE GATE — verify the buzz on two real phones**

1. Install on both phones. Enrol phone A with `ENROLL_CODE_1`, phone B with `ENROLL_CODE_2`.
2. Grant the notification permission on both when prompted (Android 13+).
3. From your laptop, send to phone B using phone A's token:

```bash
curl -X POST https://love-button.<subdomain>.workers.dev/v1/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <phone A's auth_token>" \
  -d '{"msg_id":1}'
```

Expected: `{"send_id":"...","delivered":1}` **and phone B buzzes with a notification reading "I love you".**

4. Lock phone B, wait two minutes for it to sleep, send again. It must still buzz.
5. Tap the notification → the app opens.

**This is milestone 2 of the spec. If it does not work, do not proceed — debug it.** The most common causes on MIUI are the ones Task 8 addresses; the second most common is a stale FCM token on the server.

- [ ] **Step 10: Commit**

```bash
git add app/
git commit -m "feat(app): receive data-only pushes and post notifications"
```

---

## Task 7: Home screen with a send button

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/work/SendWorker.kt`
- Create: `app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/lovebutton/app/MainActivity.kt`

**Interfaces:**
- Consumes: `MESSAGES` (Task 2); `LoveButtonApi` (Task 3); `Prefs` (Task 4)
- Produces: `SendWorker.enqueue(context, msgId)`; `@Composable fun HomeScreen()`

- [ ] **Step 1: Write `work/SendWorker.kt`**

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
```

- [ ] **Step 2: Write `ui/HomeScreen.kt`**

```kotlin
package com.lovebutton.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.lovebutton.app.data.MESSAGES
import com.lovebutton.app.work.SendWorker

@Composable
fun HomeScreen(partnerName: String, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(partnerName, style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tap to send",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        MESSAGES.forEach { message ->
            Button(
                onClick = {
                    // Haptic first, before the network call even starts. It lands
                    // immediately, which is what makes the tap feel responsive
                    // regardless of how long the request takes.
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    SendWorker.enqueue(context, message.id)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(message.text)
            }
        }

        Button(
            onClick = onOpenSetup,
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text("Delivery setup")
        }
    }
}
```

- [ ] **Step 3: Wire `HomeScreen` into `MainActivity`**

Replace the `else ->` branch of `Root()` with:

```kotlin
        else -> HomeScreen(
            partnerName = enrolment!!.partnerName,
            // Wired to the real Setup screen in Task 8, which creates it. This task
            // must compile and run on its own, so the button does nothing for now.
            onOpenSetup = {},
        )
```

Add the import `com.lovebutton.app.ui.HomeScreen`.

This task is self-contained: it builds, installs and sends without `SetupScreen` existing. Task 8 creates that screen and wires this button to it.

- [ ] **Step 4: Commit (after Task 8 compiles)**

```bash
git add app/src/main/java/com/lovebutton/app/work/SendWorker.kt app/src/main/java/com/lovebutton/app/ui/HomeScreen.kt app/src/main/java/com/lovebutton/app/MainActivity.kt
git commit -m "feat(app): add home screen and WorkManager send"
```

---

## Task 8: MIUI delivery setup screen

**Files:**
- Create: `app/src/main/java/com/lovebutton/app/device/DeviceSetup.kt`
- Create: `app/src/main/java/com/lovebutton/app/ui/SetupScreen.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `fun isIgnoringBatteryOptimisations(context: Context): Boolean`
  - `fun areNotificationsEnabled(context: Context): Boolean`
  - `fun isProbablyXiaomi(): Boolean`
  - `fun openBatteryOptimisationSettings(context: Context)`
  - `fun openMiuiAutostart(context: Context)`
  - `fun openMiuiBatterySaver(context: Context)`
  - `fun openAppSettings(context: Context)`

- [ ] **Step 1: Write `device/DeviceSetup.kt`**

```kotlin
package com.lovebutton.app.device

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

fun isIgnoringBatteryOptimisations(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java)
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

fun areNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

fun isProbablyXiaomi(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

/**
 * Opens an OEM settings screen, falling back to this app's own settings page.
 *
 * MIUI's component names differ between versions and are not part of any public
 * API — they throw ActivityNotFoundException on builds that renamed or removed
 * them. A missing OEM activity must never crash the app, so every one of these
 * goes through here.
 */
private fun startOrFallBack(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        openAppSettings(context)
    } catch (e: SecurityException) {
        // Some MIUI builds export the activity but refuse external launches.
        openAppSettings(context)
    }
}

fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** Standard Android — asks to exempt the app from Doze battery optimisation. */
fun openBatteryOptimisationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    startOrFallBack(context, intent)
}

/** MIUI Autostart — off by default for sideloaded apps, which silently kills FCM. */
fun openMiuiAutostart(context: Context) {
    val intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    )
    startOrFallBack(context, intent)
}

/** MIUI battery saver — must be set to "No restrictions" for this app. */
fun openMiuiBatterySaver(context: Context) {
    val intent = Intent().setComponent(
        ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        )
    ).putExtra("package_name", context.packageName)
        .putExtra("package_label", "Love Button")
    startOrFallBack(context, intent)
}
```

- [ ] **Step 2: Write `ui/SetupScreen.kt`**

```kotlin
package com.lovebutton.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lovebutton.app.device.areNotificationsEnabled
import com.lovebutton.app.device.isIgnoringBatteryOptimisations
import com.lovebutton.app.device.isProbablyXiaomi
import com.lovebutton.app.device.openAppSettings
import com.lovebutton.app.device.openBatteryOptimisationSettings
import com.lovebutton.app.device.openMiuiAutostart
import com.lovebutton.app.device.openMiuiBatterySaver

/**
 * The delivery setup checklist.
 *
 * On MIUI three separate mechanisms will each independently stop pushes:
 * Autostart is off by default for sideloaded apps, battery saver defaults to
 * restricted, and unlocked apps get purged under memory pressure. None of this
 * can be fixed in code — the most an app can do is take you straight to each
 * setting and then re-check whether it stuck.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    // Re-read the live state whenever the screen recomposes after returning
    // from a settings page, so a change you just made shows up immediately.
    val notificationsOn = remember(refresh) { areNotificationsEnabled(context) }
    val batteryExempt = remember(refresh) { isIgnoringBatteryOptimisations(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Delivery setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Android will quietly stop delivering messages unless these are set. " +
                "Check them again after a system update.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CheckItem(
            title = "Notifications allowed",
            done = notificationsOn,
            action = "Grant",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Before Android 13 there is no runtime permission to request —
                    // notifications are on unless the user turned them off, so the
                    // only useful action is to open the app's own settings page.
                    openAppSettings(context)
                }
            },
        )

        CheckItem(
            title = "Battery optimisation off",
            done = batteryExempt,
            action = "Open",
            onClick = {
                openBatteryOptimisationSettings(context)
                refresh++
            },
        )

        if (isProbablyXiaomi()) {
            Text(
                "Xiaomi / MIUI",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            // These two cannot be read back — MIUI exposes no API for either, so
            // the app cannot show a tick. You have to confirm them by eye.
            CheckItem(
                title = "Autostart enabled",
                done = null,
                action = "Open",
                onClick = { openMiuiAutostart(context) },
            )
            CheckItem(
                title = "Battery saver: No restrictions",
                done = null,
                action = "Open",
                onClick = { openMiuiBatterySaver(context) },
            )
            CheckItem(
                title = "Locked in recents",
                done = null,
                action = null,
                onClick = {},
                detail = "Open the recent apps view, swipe down on Love Button " +
                    "(or long-press it) and tap the padlock.",
            )
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text("Done")
        }
    }
}

/** `done = null` means the state cannot be read back and must be checked by eye. */
@Composable
private fun CheckItem(
    title: String,
    done: Boolean?,
    action: String?,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val marker = when (done) {
                true -> "✓ "
                false -> "✗ "
                null -> "• "
            }
            Text("$marker$title", style = MaterialTheme.typography.titleSmall)

            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (action != null && done != true) {
                Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(action)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Task 7's `MainActivity` change now compiles)

- [ ] **Step 4: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all 13 tests PASS

- [ ] **Step 5: Manual verification on a Xiaomi phone**

1. Open Delivery setup → the Xiaomi section appears.
2. Tap each button → the correct MIUI settings page opens. **If any throws instead of opening, the fallback must land you on the app's own settings page, not crash.**
3. Turn battery optimisation off, return → the item shows a tick.
4. Do the same on the second phone.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/lovebutton/app/device/ app/src/main/java/com/lovebutton/app/ui/SetupScreen.kt
git commit -m "feat(app): add MIUI delivery setup checklist"
```

---

## Task 9: End-to-end from the app

**Files:** none — this is verification.

- [ ] **Step 1: Install the current build on both phones**

Run: `./gradlew :app:installDebug` with each device attached.

- [ ] **Step 2: Complete Delivery setup on both phones**

Every item, including the three MIUI ones. This is the setup that Task 10's overnight test is really testing.

- [ ] **Step 3: Send in both directions**

From phone A, tap each of the four messages. Confirm phone B buzzes four times, with the right text, and that the notifications stack rather than replacing one another. Then reverse.

- [ ] **Step 4: Send with the recipient's screen off**

Lock phone B, wait five minutes, send from A. It must still arrive within a few seconds.

- [ ] **Step 5: Send with the recipient's app swiped away**

Swipe Love Button out of recents on phone B (do not force-stop it), send from A. It must still arrive — this is what Autostart and the recents lock buy you.

- [ ] **Step 6: Confirm force-stop breaks it, and document that it does**

Force-stop the app from Settings on phone B, send from A. **Nothing arrives — this is expected and unfixable in code.** Reopen the app on B and confirm delivery resumes. Knowing this failure mode by sight is worth more than any amount of debugging later.

- [ ] **Step 7: Commit nothing; record results in the task report**

---

## Task 10: The overnight test — required gate

**Files:** none — this is verification. Spec §11 makes this mandatory, not optional.

- [ ] **Step 1: Leave both phones alone overnight**

Both phones idle, screens off, on their normal chargers or not, for at least six hours. Do not touch them.

- [ ] **Step 2: Send first thing in the morning**

From phone A, send one message before opening any app on either phone.

Expected: phone B buzzes within seconds.

- [ ] **Step 3: If it fails, diagnose in this order**

1. Open the app on phone B and send again. If it works now, the app was killed overnight → the MIUI setup did not hold. Re-check Autostart, battery restrictions and the recents lock.
2. Check the server recorded the send:

```bash
wrangler d1 execute love-button --remote \
  --command "SELECT id, from_person, to_person, msg_id, sent_at FROM sends ORDER BY sent_at DESC LIMIT 5"
```

If the row exists, the Worker did its job and the problem is on the phone.

3. Check the device rows are still present:

```bash
wrangler d1 execute love-button --remote \
  --command "SELECT id, person, label, updated_at FROM devices"
```

If a row vanished, FCM reported the token dead and the Worker deleted it — that phone must enrol again.

- [ ] **Step 4: Repeat until two consecutive nights pass**

One good night can be luck. Two is evidence.

- [ ] **Step 5: Record the result**

Note the outcome in the report, including which MIUI settings were needed. That list is what you will follow when installing on a replacement phone a year from now.

---

## Self-Review

**1. Spec coverage:**

| Spec requirement | Task |
|---|---|
| §6.1 Enrol screen | 5 |
| §6.1 Home screen with tap-to-send rows | 7 |
| §6.1 Setup checklist screen | 8 |
| §6.2 Four messages, ids 1-4, text local to the app | 2 |
| §6.3 Channel with `IMPORTANCE_HIGH`, unique notification id per send | 6 |
| §6.3 Sounds frozen at creation — real channels deferred | 2, 6 (temporary `dev_buzz_v1`) |
| §6.3 `POST_NOTIFICATIONS` on 13+, handle refusal | 1 (manifest), 8 (request + state) |
| §6.4 Branch on `data.type`, receipts post nothing | 6 |
| §6.5 All network calls via WorkManager | 6, 7 |
| §8 Battery optimisation prompt | 8 |
| §8 MIUI Autostart and battery deep links, wrapped in try/catch | 8 |
| §8 Re-verify on launch | 8 |
| §8 Force-stop documented | 9 Step 6 |
| §9 Human setup | 0 |
| §10 Milestone 2 — the buzz | 6 Step 9 |
| §10 Milestone 3 — reliability and overnight test | 8, 9, 10 |
| §11 JVM unit tests for message mapping | 2 |
| §11 Manual hardware verification | 5, 6, 8, 9, 10 |

Deliberately absent, belonging to later plans: sounds and the four real channels (Plan 3), widgets (Plan 3), receipts and the state ladder (Plan 4), the read-receipt toggle (Plan 4).

**2. Placeholder scan:** No "TBD" or "handle errors appropriately". Every code step carries complete code. Two values the human supplies — the Worker URL in Task 6 Step 7 and the enrolment codes — come from Plan 1's Task 0 and are named as such.

**3. Type consistency:** `messageForId` and `DEV_CHANNEL_ID` (Task 2) are used under those names in Task 6. `LoveButtonApi`'s three method signatures (Task 3) match every call site in Tasks 5, 6 and 7. `Prefs.current()` and `Enrolment`'s three fields (Task 4) match their uses in Tasks 5, 6 and 7. `SendWorker.enqueue(context, msgId)` and `RegisterTokenWorker.enqueue(context, fcmToken)` match their call sites.

**4. Known risk:** Task 7 modifies `MainActivity` to reference `SetupScreen`, which Task 8 creates. The plan states this explicitly and tells the implementer not to commit a non-compiling build. If executing tasks strictly independently, do Task 8 before building Task 7.
