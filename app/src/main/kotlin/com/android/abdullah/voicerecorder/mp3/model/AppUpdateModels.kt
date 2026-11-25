package com.android.abdullah.voicerecorder.mp3.model

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val versionName: String,
    val versionCode: Long?,
    val releaseNotes: String,
    val publishedAt: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data class RepoNotConfigured(
        val currentVersion: String
    ) : UpdateUiState

    data class Checking(
        val currentVersion: String
    ) : UpdateUiState

    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String?
    ) : UpdateUiState

    data class Available(
        val currentVersion: String,
        val release: GitHubReleaseInfo
    ) : UpdateUiState

    data class Downloading(
        val currentVersion: String,
        val release: GitHubReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float?
    ) : UpdateUiState

    data class ReadyToInstall(
        val currentVersion: String,
        val release: GitHubReleaseInfo,
        val apkFilePath: String,
        val needsUnknownSourcePermission: Boolean
    ) : UpdateUiState

    data class Error(
        val currentVersion: String,
        val message: String,
        val release: GitHubReleaseInfo? = null
    ) : UpdateUiState
}
