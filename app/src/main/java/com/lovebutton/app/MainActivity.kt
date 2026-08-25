package com.lovebutton.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
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
import com.lovebutton.app.push.EXTRA_SEND_ID
import com.lovebutton.app.ui.EnrolScreen
import com.lovebutton.app.ui.HomeScreen
import com.lovebutton.app.ui.SetupScreen
import com.lovebutton.app.ui.LoveButtonTheme
import com.lovebutton.app.work.ReceiptWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportSeenFrom(intent)
        setContent {
            LoveButtonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The notification intent sets FLAG_ACTIVITY_SINGLE_TOP (with CLEAR_TOP),
        // which routes a notification tap to this callback when this activity is
        // already on top, instead of destroying and recreating it. This ensures
        // the seen report is delivered and avoids unnecessarily rebuilding the UI.
        setIntent(intent)
        reportSeenFrom(intent)
    }

    private fun reportSeenFrom(intent: Intent?) {
        val sendId = intent?.getStringExtra(EXTRA_SEND_ID) ?: return
        ReceiptWorker.enqueue(this, sendId, "seen")
        // Clear it so a configuration change does not report the same tap twice.
        intent.removeExtra(EXTRA_SEND_ID)
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
    var showSetup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        prefs.current()
        loaded = true
    }

    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        enrolment == null -> EnrolScreen(onEnrolled = { /* state flow re-emits */ })
        showSetup -> SetupScreen(onDone = { showSetup = false })
        else -> HomeScreen(
            partnerName = enrolment!!.partnerName,
            onOpenSetup = { showSetup = true },
        )
    }
}
