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
        context.dataStore.edit { prefs -> prefs.clear() }
    }
}
