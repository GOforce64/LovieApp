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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lovebutton.app.data.Prefs
import com.lovebutton.app.ui.EnrolScreen
import com.lovebutton.app.ui.GuideScreen
import com.lovebutton.app.ui.HomeScreen
import com.lovebutton.app.ui.SetupScreen
import com.lovebutton.app.ui.LoveButtonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the splash is installed by swapping the
        // window theme, and after that call the window is already showing.
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
        // which resumes this activity in place on a notification tap instead of
        // destroying and rebuilding the whole Compose tree.
        setIntent(intent)
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
    var showGuide by remember { mutableStateOf(false) }

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
        showGuide -> GuideScreen(partnerName = enrolment!!.partnerName, onDone = { showGuide = false })
        else -> HomeScreen(
            partnerName = enrolment!!.partnerName,
            onOpenSetup = { showSetup = true },
            onOpenGuide = { showGuide = true },
        )
    }
}
