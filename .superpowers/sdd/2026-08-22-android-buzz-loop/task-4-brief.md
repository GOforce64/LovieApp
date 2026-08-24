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

