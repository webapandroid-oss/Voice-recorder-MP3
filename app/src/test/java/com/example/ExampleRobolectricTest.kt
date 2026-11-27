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
    fun `app version is 1_0_6 with versionCode 7`() {
        assertEquals(7, BuildConfig.VERSION_CODE)
        assertEquals("1.0.6", BuildConfig.VERSION_NAME)
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
    fun `default mp3 filename matches Recording timestamp format in Music Voice Recorder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = RecordingsRepository(context)
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 14, 5, 9)
        }
        val file = repo.createNewMp3File(customTitle = null, timestampDate = cal.time)
        assertEquals("Recording_23_11_2026_14_05_09.mp3", file.name)
        assertTrue(file.absolutePath.contains("Music/Voice Recorder"))
    }

    @Test
    fun `sd card prompt and storage switch strings match specification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Save recordings to SD cards?", context.getString(R.string.sd_card_prompt_title))
        assertEquals("Cancel", context.getString(R.string.action_cancel))
        assertEquals("Change", context.getString(R.string.action_change))
        assertEquals(
            "Change storage location to Internal Storage",
            context.getString(R.string.action_switch_to_internal)
        )
        assertEquals(
            "Change storage location to SD card",
            context.getString(R.string.action_switch_to_sd_card)
        )
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
    fun `more options menu shows settings and updates and settings opens quality sheet`() {
        composeRule.setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp()
            }
        }

        composeRule.onNodeWithTag("more_options_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("menu_quality_settings").assertDoesNotExist()
        composeRule.onNodeWithTag("menu_check_updates").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_settings").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("settings_theme_option").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_quality_option").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_github_option").assertIsDisplayed()

        // Quality Settings opens the existing bitrate/sample-rate/channel bottom sheet
        composeRule.onNodeWithTag("settings_quality_option").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bitrate_192").assertIsDisplayed()
    }

    @Test
    fun `settings theme option switches between light and dark theme`() {
        composeRule.setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp()
            }
        }

        composeRule.onNodeWithTag("more_options_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("menu_settings").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("settings_theme_option").performClick()
        composeRule.onNodeWithTag("theme_picker_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_option_light").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Light Theme").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_theme_option").performClick()
        composeRule.onNodeWithTag("theme_option_dark").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Dark Theme").assertIsDisplayed()
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
                    onBack = {},
                    onCheckAgain = {},
                    onDownloadUpdate = {},
                    onCancelDownload = {},
                    onInstallNow = {},
                    onRequestUnknownSourcePermission = {},
                    onRefreshInstallPermission = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("update_repo_input").assertDoesNotExist()
        composeRule.onNodeWithTag("update_change_repo_button").assertDoesNotExist()
        composeRule.onNodeWithText(GitHubUpdateRepository.TRUSTED_GITHUB_REPO, substring = true).assertDoesNotExist()
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

        uiState.value = UpdateUiState.Checking("1.0.2")
        composeRule.onNodeWithText(GitHubUpdateRepository.TRUSTED_GITHUB_REPO, substring = true).assertDoesNotExist()

        uiState.value = UpdateUiState.UpToDate("1.0.2", "1.0.2")
        composeRule.onNodeWithText(GitHubUpdateRepository.TRUSTED_GITHUB_REPO, substring = true).assertDoesNotExist()

        uiState.value = UpdateUiState.Error("1.0.2", "Network timeout")
        composeRule.onNodeWithText(GitHubUpdateRepository.TRUSTED_GITHUB_REPO, substring = true).assertDoesNotExist()
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

    @Test
    fun `atom feed and expanded assets parsers extract release without github api`() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/999/v1.0.5-build.30</id>
                <updated>2026-11-26T06:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/tag/v1.0.5-build.30"/>
                <title>Voice Recorder v1.0.5 (Build #30)</title>
                <content type="html">&lt;h2&gt;Voice Recorder v1.0.5&lt;/h2&gt;&lt;ul&gt;&lt;li&gt;Unlimited update check&lt;/li&gt;&lt;li&gt;&lt;code&gt;versionCode=6&lt;/code&gt;&lt;/li&gt;&lt;/ul&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val entries = GitHubUpdateRepository.parseAtomFeedEntries(atomXml)
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("v1.0.5-build.30", entry.tagName)
        assertEquals("Voice Recorder v1.0.5 (Build #30)", entry.title)
        assertTrue(entry.releaseNotes.contains("Unlimited update check"))
        assertEquals(6L, GitHubUpdateRepository.extractVersionCode(entry.releaseNotes))

        val expandedHtml = """
            <ul>
              <li>
                <a href="/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.5-build.30/voice-recorder-v1.0.5-release.apk">
                  voice-recorder-v1.0.5-release.apk
                </a>
                <span>19.5 MB</span>
              </li>
            </ul>
        """.trimIndent()

        val assets = GitHubUpdateRepository.parseExpandedAssetsHtml(expandedHtml)
        assertEquals(1, assets.size)
        assertEquals("voice-recorder-v1.0.5-release.apk", assets.first().name)
        assertEquals(
            "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.5-build.30/voice-recorder-v1.0.5-release.apk",
            assets.first().downloadUrl
        )
        assertTrue(assets.first().sizeBytes > 19_000_000L)
    }

    @Test
    fun `update security blocks untrusted repos http links and third party download urls`() {
        assertTrue(
            GitHubUpdateRepository.isTrustedReleaseDownloadUrl(
                "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.4/voice-recorder-v1.0.4-release.apk"
            )
        )
        assertFalse(
            GitHubUpdateRepository.isTrustedReleaseDownloadUrl(
                "http://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.4/app.apk"
            )
        )
        assertFalse(
            GitHubUpdateRepository.isTrustedReleaseDownloadUrl(
                "https://github.com/evil-hacker/Voice-recorder-MP3/releases/download/v1.0.4/app.apk"
            )
        )
        assertFalse(
            GitHubUpdateRepository.isTrustedReleaseDownloadUrl(
                "https://evil.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.4/app.apk"
            )
        )
        assertFalse(
            GitHubUpdateRepository.isTrustedReleaseDownloadUrl(
                "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.4/readme.txt"
            )
        )

        assertTrue(
            GitHubUpdateRepository.isAllowedGitHubUrl(
                java.net.URL("https://github.com/webapandroid-oss/Voice-recorder-MP3/releases.atom"),
                allowAssetCdnHosts = false
            )
        )
        assertFalse(
            GitHubUpdateRepository.isAllowedGitHubUrl(
                java.net.URL("http://github.com/webapandroid-oss/Voice-recorder-MP3/releases.atom"),
                allowAssetCdnHosts = false
            )
        )
        assertFalse(
            GitHubUpdateRepository.isAllowedGitHubUrl(
                java.net.URL("https://objects.githubusercontent.com/some-asset.apk"),
                allowAssetCdnHosts = false
            )
        )
        assertTrue(
            GitHubUpdateRepository.isAllowedGitHubUrl(
                java.net.URL("https://objects.githubusercontent.com/some-asset.apk"),
                allowAssetCdnHosts = true
            )
        )
        assertFalse(
            GitHubUpdateRepository.isAllowedGitHubUrl(
                java.net.URL("https://attacker.example.com/payload.apk"),
                allowAssetCdnHosts = true
            )
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = GitHubUpdateRepository(context)
        var blocked = false
        try {
            kotlinx.coroutines.runBlocking {
                repo.checkLatestRelease("evil-hacker/malware-clone")
            }
        } catch (_: SecurityException) {
            blocked = true
        }
        assertTrue(blocked)
    }

    @Test
    fun `workflow update manifest parser extracts version size and sha256`() {
        val sampleSha = "a".repeat(64)
        val manifestJson = """
            {
              "schema_version": 1,
              "repository": "webapandroid-oss/Voice-recorder-MP3",
              "tag_name": "v1.0.5-build.42",
              "release_name": "Voice Recorder v1.0.5 (Build #42)",
              "version_name": "1.0.5",
              "version_code": 6,
              "release_notes": "Faster background update check",
              "published_at": "2026-11-26T06:00:00Z",
              "apk_asset_name": "voice-recorder-v1.0.5-release.apk",
              "apk_download_url": "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.5-build.42/voice-recorder-v1.0.5-release.apk",
              "apk_size_bytes": 21500000,
              "apk_sha256": "$sampleSha",
              "html_url": "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/tag/v1.0.5-build.42"
            }
        """.trimIndent()

        val parsed = GitHubUpdateRepository.parseUpdateManifestJson(manifestJson)
        assertNotNull(parsed)
        assertEquals("v1.0.5-build.42", parsed!!.tagName)
        assertEquals("1.0.5", parsed.versionName)
        assertEquals(6L, parsed.versionCode)
        assertEquals(21500000L, parsed.apkSizeBytes)
        assertEquals(sampleSha, parsed.apkSha256)
        assertTrue(GitHubUpdateRepository.isTrustedReleaseDownloadUrl(parsed.apkDownloadUrl))
        assertTrue(
            GitHubUpdateRepository.isRemoteNewer(
                parsed,
                InstalledVersionInfo(versionName = "1.0.4", versionCode = 5L)
            )
        )
    }

    @Test
    fun `update check bus emission immediately surfaces update available inside app`() {
        composeRule.setContent {
            VoiceRecorderTheme {
                VoiceRecorderApp()
            }
        }
        composeRule.waitForIdle()

        val newRelease = GitHubReleaseInfo(
            tagName = "v1.0.9-build.99",
            releaseName = "Voice Recorder v1.0.9",
            versionName = "1.0.9",
            versionCode = 10L,
            releaseNotes = "Instant update available verification",
            publishedAt = "2026-11-26T06:00:00Z",
            apkAssetName = "voice-recorder-v1.0.9-release.apk",
            apkDownloadUrl = "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/v1.0.9-build.99/voice-recorder-v1.0.9-release.apk",
            apkSizeBytes = 20_000_000L,
            apkSha256 = "b".repeat(64)
        )
        com.android.abdullah.voicerecorder.mp3.service.UpdateCheckEventBus.emit(newRelease)
        composeRule.waitForIdle()

        // Immediately opens Update Available screen when idle
        composeRule.onNodeWithTag("update_release_title").assertIsDisplayed()
        composeRule.onNodeWithTag("update_back_button").performClick()
        composeRule.waitForIdle()

        // After dismissing, in-app Update Available banner stays visible inside main screen
        composeRule.onNodeWithTag("in_app_update_banner").assertIsDisplayed()
        composeRule.onNodeWithText("Update Available • v1.0.9").assertIsDisplayed()
        composeRule.onNodeWithTag("in_app_update_banner_button").assertIsDisplayed()
    }
}
