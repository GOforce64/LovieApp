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
