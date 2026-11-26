package com.android.abdullah.voicerecorder.mp3

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.android.abdullah.voicerecorder.mp3.service.UpdateCheckJobService
import com.android.abdullah.voicerecorder.mp3.ui.VoiceRecorderApp
import com.android.abdullah.voicerecorder.mp3.ui.theme.VoiceRecorderTheme

class MainActivity : ComponentActivity() {

    private var openUpdatesSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateCheckJobService.schedulePeriodicChecks(applicationContext)
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true) {
            openUpdatesSignal++
        }
        setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp(openUpdatesSignal = openUpdatesSignal)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            openUpdatesSignal++
        }
    }

    companion object {
        const val EXTRA_OPEN_UPDATES = "extra_open_updates"
    }
}
