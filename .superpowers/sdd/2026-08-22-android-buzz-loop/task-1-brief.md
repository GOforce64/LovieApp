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

