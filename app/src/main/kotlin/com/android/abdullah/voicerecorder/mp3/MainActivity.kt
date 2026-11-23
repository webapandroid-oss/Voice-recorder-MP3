package com.android.abdullah.voicerecorder.mp3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.android.abdullah.voicerecorder.mp3.ui.VoiceRecorderApp
import com.android.abdullah.voicerecorder.mp3.ui.theme.VoiceRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp()
            }
        }
    }
}
