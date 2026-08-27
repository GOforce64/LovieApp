package com.lovebutton.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
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
        modifier = Modifier
            .fillMaxSize()
            .background(Sticker.Ground)
            // This screen had no inset handling either, so its heading sat under
            // the status bar on an edge-to-edge window.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The one card on the screen, so the code entry reads as the single
        // thing being asked for rather than as a form on a blank page.
        StickerBox(fill = Sticker.Surface, radius = 22, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Enter your code", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "You only do this once on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text("Enrolment code") },
                    singleLine = true,
                    enabled = !busy,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sticker.Ink,
                        unfocusedBorderColor = Sticker.Ink,
                        focusedLabelColor = Sticker.Ink,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        if (busy) {
            CircularProgressIndicator(color = Sticker.Ink, modifier = Modifier.padding(top = 8.dp))
        } else {
            StickerButton(
                label = "Enrol this phone",
                fill = Sticker.Mint,
                radius = 14,
                enabled = code.length >= 8,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            // The FCM token is required at enrolment so the server
                            // can push to this phone immediately, without waiting
                            // for a separate registration call.
                            //
                            // This fetch gets its own try so that ONLY it produces
                            // the Play Services message. Wrapping the whole flow in
                            // one catch would blame Google for a malformed response
                            // or a disk failure while saving — sending you to debug
                            // entirely the wrong thing.
                            val fcmToken = try {
                                FirebaseMessaging.getInstance().token.await()
                            } catch (e: Exception) {
                                error = "Could not get a push token from Google. " +
                                    "Check that Play Services is available."
                                return@launch
                            }
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
                            // Anything the specific handlers did not claim.
                            error = "Something went wrong enrolling this phone. " +
                                "Please try again."
                        } finally {
                            // Runs on every exit path, including the return@launch
                            // above, so the spinner can never stick.
                            busy = false
                        }
                    }
                },
            )
        }
    }
}
