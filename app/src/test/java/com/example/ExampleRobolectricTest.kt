package com.android.abdullah.voicerecorder.mp3

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.ui.VoiceRecorderApp
import com.android.abdullah.voicerecorder.mp3.ui.screens.RecordScreen
import com.android.abdullah.voicerecorder.mp3.ui.theme.VoiceRecorderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voice Recorder", appName)
    }

    @Test
    fun `app version is 1_0_1 with versionCode 2`() {
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("1.0.1", BuildConfig.VERSION_NAME)
    }

    @Test
    fun `manifest declares required audio recording permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(perms.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `default mp3 filename matches Bengali timestamp format`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = RecordingsRepository(context)
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 14, 5, 9)
        }
        val file = repo.createNewMp3File(customTitle = null, timestampDate = cal.time)
        assertEquals("রেকর্ডিং_23_11_2026_14_05_09.mp3", file.name)
    }

    @Test
    fun `record screen is minimal and renders material transport buttons when recording`() {
        composeRule.setContent {
            VoiceRecorderTheme {
                RecordScreen(
                    liveState = LiveRecordingState(status = RecorderStatus.RECORDING),
                    draftTitle = "",
                    onDraftTitleChange = {},
                    onStartRecording = {},
                    onPauseRecording = {},
                    onResumeRecording = {},
                    onStopAndSave = {},
                    onDiscard = {}
                )
            }
        }
        composeRule.onNodeWithTag("primary_record_button").assertIsDisplayed()
        composeRule.onNodeWithTag("stop_save_button").assertIsDisplayed()
        composeRule.onNodeWithTag("discard_recording_button").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_pill_button").assertDoesNotExist()
        composeRule.onNodeWithText("libffmpeg.so", substring = true).assertDoesNotExist()
    }

    @Test
    fun `top right more options menu opens quality settings bottom sheet`() {
        composeRule.setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp()
            }
        }

        composeRule.onNodeWithTag("more_options_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("menu_quality_settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bitrate_192").assertIsDisplayed()
    }
}
