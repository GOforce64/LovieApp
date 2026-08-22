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
