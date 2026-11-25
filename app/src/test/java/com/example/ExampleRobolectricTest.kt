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
import com.android.abdullah.voicerecorder.mp3.data.GitHubUpdateRepository
import com.android.abdullah.voicerecorder.mp3.data.InstalledVersionInfo
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.UpdateUiState
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.ui.VoiceRecorderApp
import com.android.abdullah.voicerecorder.mp3.ui.screens.RecordScreen
import com.android.abdullah.voicerecorder.mp3.ui.screens.UpdateScreen
import com.android.abdullah.voicerecorder.mp3.ui.theme.VoiceRecorderTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `app version is 1_0_3 with versionCode 4`() {
        assertEquals(4, BuildConfig.VERSION_CODE)
        assertEquals("1.0.3", BuildConfig.VERSION_NAME)
    }

    @Test
    fun `manifest declares required audio and update install permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(Manifest.permission.INTERNET))
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(perms.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(perms.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
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

    @Test
    fun `github release json parser extracts version notes and apk asset`() {
        val releaseJson = JSONObject().apply {
            put("tag_name", "v1.0.3-build.18")
            put("name", "Voice Recorder v1.0.3 (Build #18)")
            put("body", "## Voice Recorder v1.0.3\n- Added faster export\nversionCode=4")
            put("published_at", "2026-11-25T10:00:00Z")
            put("draft", false)
            put(
                "assets",
                JSONArray().put(
                    JSONObject().apply {
                        put("name", "voice-recorder-v1.0.3-release.apk")
                        put("browser_download_url", "https://github.com/example/repo/releases/download/v1.0.3/voice-recorder-v1.0.3-release.apk")
                        put("size", 24_000_000L)
                    }
                )
            )
        }

        val parsed = GitHubUpdateRepository.parseReleaseJson(releaseJson)
        assertNotNull(parsed)
        assertEquals("1.0.3", parsed!!.versionName)
        assertEquals(4L, parsed.versionCode)
        assertTrue(parsed.releaseNotes.contains("Added faster export"))
        assertTrue(
            GitHubUpdateRepository.isRemoteNewer(
                parsed,
                InstalledVersionInfo(versionName = "1.0.2", versionCode = 3L)
            )
        )
        assertFalse(
            GitHubUpdateRepository.isRemoteNewer(
                parsed,
                InstalledVersionInfo(versionName = "1.0.3", versionCode = 4L)
            )
        )
    }

    @Test
    fun `update screen morphs primary button across download install and permission states`() {
        val sampleRelease = GitHubReleaseInfo(
            tagName = "v1.0.3",
            releaseName = "Voice Recorder v1.0.3",
            versionName = "1.0.3",
            versionCode = 4L,
            releaseNotes = "Line 1: Release note details\nLine 2: Signed with release key",
            publishedAt = "2026-11-25T10:00:00Z",
            apkAssetName = "voice-recorder-v1.0.3-release.apk",
            apkDownloadUrl = "https://example.com/app.apk",
            apkSizeBytes = 10_485_760L
        )
        val uiState = androidx.compose.runtime.mutableStateOf<UpdateUiState>(
            UpdateUiState.Available("1.0.2", sampleRelease)
        )

        composeRule.setContent {
            VoiceRecorderTheme {
                UpdateScreen(
                    updateState = uiState.value,
                    configuredRepo = "example/repo",
                    onBack = {},
                    onCheckAgain = {},
                    onSaveRepoAndCheck = {},
                    onDownloadUpdate = {},
                    onCancelDownload = {},
                    onInstallNow = {},
                    onRequestUnknownSourcePermission = {},
                    onRefreshInstallPermission = {}
                )
            }
        }

        composeRule.onNodeWithText("Line 1: Release note details", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("update_primary_action_button").assertIsDisplayed()
        composeRule.onNodeWithText("Download Update").assertIsDisplayed()

        uiState.value = UpdateUiState.Downloading("1.0.2", sampleRelease, 5_242_880L, 10_485_760L, 0.5f)
        composeRule.onNodeWithTag("update_progress_bar").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading… (50%)").assertIsDisplayed()

        uiState.value = UpdateUiState.ReadyToInstall("1.0.2", sampleRelease, "/tmp/app.apk", needsUnknownSourcePermission = false)
        composeRule.onNodeWithText("Install Now").assertIsDisplayed()

        uiState.value = UpdateUiState.ReadyToInstall("1.0.2", sampleRelease, "/tmp/app.apk", needsUnknownSourcePermission = true)
        composeRule.onNodeWithText("Allow Unknown App Permission").assertIsDisplayed()

        uiState.value = UpdateUiState.ReadyToInstall("1.0.2", sampleRelease, "/tmp/app.apk", needsUnknownSourcePermission = false)
        composeRule.onNodeWithText("Install Now").assertIsDisplayed()

        uiState.value = UpdateUiState.Installing("1.0.2", sampleRelease, "/tmp/app.apk")
        composeRule.onNodeWithText("Installing Update…").assertIsDisplayed()
    }

    @Test
    fun `default github repo resource and url normalization handle webapandroid-oss repo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("webapandroid-oss/Voice-recorder-MP3", context.getString(R.string.github_repo))

        assertEquals(
            "webapandroid-oss/Voice-recorder-MP3",
            GitHubUpdateRepository.normalizeRepoSlug("github.com/webapandroid-oss/Voice-recorder-MP3")
        )
        assertEquals(
            "webapandroid-oss/Voice-recorder-MP3",
            GitHubUpdateRepository.normalizeRepoSlug("https://github.com/webapandroid-oss/Voice-recorder-MP3")
        )
        assertEquals(
            "webapandroid-oss/Voice-recorder-MP3",
            GitHubUpdateRepository.normalizeRepoSlug("https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/latest")
        )
        assertEquals(
            "webapandroid-oss/Voice-recorder-MP3",
            GitHubUpdateRepository.normalizeRepoSlug("webapandroid-oss/Voice-recorder-MP3")
        )
    }
}
