package com.android.abdullah.voicerecorder.mp3.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class InstalledVersionInfo(
    val versionName: String,
    val versionCode: Long
)

sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : ReleaseCheckResult
    data class UpToDate(val latestVersion: String?) : ReleaseCheckResult
}

class GitHubUpdateRepository(private val context: Context) {

    fun getInstalledVersion(): InstalledVersionInfo {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            InstalledVersionInfo(
                versionName = pkgInfo.versionName ?: "1.0.2",
                versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
            )
        } catch (_: Exception) {
            InstalledVersionInfo(versionName = "1.0.2", versionCode = 3L)
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun checkLatestRelease(rawRepo: String): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val repoSlug = normalizeRepoSlug(rawRepo)
            ?: throw IllegalArgumentException("Invalid GitHub repository format. Use owner/repo.")

        val installed = getInstalledVersion()
        val latestRelease = fetchLatestGitHubRelease(repoSlug)
            ?: return@withContext ReleaseCheckResult.UpToDate(latestVersion = installed.versionName)

        if (isRemoteNewer(latestRelease, installed)) {
            ReleaseCheckResult.UpdateAvailable(latestRelease)
        } else {
            ReleaseCheckResult.UpToDate(latestVersion = latestRelease.versionName)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        assetFileName: String,
        expectedSizeBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.filesDir, "updates").apply {
            if (!exists()) mkdirs()
        }

        // Remove older APKs to keep storage clean
        updatesDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name != assetFileName) {
                existing.delete()
            }
        }

        val safeName = assetFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "voice-recorder-update.apk" }
        val finalFile = File(updatesDir, safeName)
        val tempFile = File(updatesDir, "$safeName.tmp")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(downloadUrl, acceptHeader = "application/octet-stream")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Download failed (HTTP $responseCode)")
            }

            val headerLength = connection.contentLengthLong
            val totalBytes = when {
                headerLength > 0L -> headerLength
                expectedSizeBytes > 0L -> expectedSizeBytes
                else -> -1L
            }

            var downloaded = 0L
            onProgress(0L, totalBytes)

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReportTime = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= 60L || (totalBytes > 0L && downloaded >= totalBytes)) {
                            lastReportTime = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Downloaded APK file is empty")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(finalFile.length(), finalFile.length())
            finalFile
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchLatestGitHubRelease(repoSlug: String): GitHubReleaseInfo? {
        val latestUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
        val latestConn = openFollowingRedirects(latestUrl, acceptHeader = "application/vnd.github+json")
        try {
            val code = latestConn.responseCode
            if (code in 200..299) {
                val payload = latestConn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(payload)
                parseReleaseJson(json)?.let { return it }
            } else if (code != 404) {
                val errBody = latestConn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("GitHub API error ($code)${if (errBody.isNotBlank()) ": ${extractApiMessage(errBody)}" else ""}")
            }
        } finally {
            latestConn.disconnect()
        }

        // Fallback to /releases list (covers prereleases or repositories without a stable tag yet)
        val listUrl = "https://api.github.com/repos/$repoSlug/releases?per_page=10"
        val listConn = openFollowingRedirects(listUrl, acceptHeader = "application/vnd.github+json")
        try {
            val code = listConn.responseCode
            if (code !in 200..299) {
                if (code == 404) {
                    throw IllegalStateException("Repository '$repoSlug' or its releases were not found on GitHub.")
                }
                val errBody = listConn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("GitHub API error ($code)${if (errBody.isNotBlank()) ": ${extractApiMessage(errBody)}" else ""}")
            }
            val payload = listConn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(payload)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                parseReleaseJson(obj)?.let { return it }
            }
            return null
        } finally {
            listConn.disconnect()
        }
    }

    private fun openFollowingRedirects(
        urlString: String,
        acceptHeader: String,
        maxRedirects: Int = 5
    ): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", "VoiceRecorder-Android-Updater/1.0")
                if (acceptHeader.contains("github")) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }

            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= maxRedirects) {
                    throw IllegalStateException("Too many redirects while contacting GitHub")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    private fun extractApiMessage(rawJson: String): String {
        return try {
            JSONObject(rawJson).optString("message", "").take(120)
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private val SEMVER_REGEX = Regex("""(\d+(?:\.\d+)+)""")
        private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val REPO_URL_REGEX = Regex("""^(?:https?://github\.com/)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?$""")

        fun normalizeRepoSlug(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val match = REPO_URL_REGEX.matchEntire(trimmed) ?: return null
            return match.groupValues[1]
        }

        fun parseReleaseJson(json: JSONObject): GitHubReleaseInfo? {
            if (json.optBoolean("draft", false)) return null
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkAssets = mutableListOf<JSONObject>()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkAssets.add(asset)
                }
            }
            if (apkAssets.isEmpty()) return null

            val chosenAsset = apkAssets.firstOrNull {
                it.optString("name", "").contains("release", ignoreCase = true)
            } ?: apkAssets.first()

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", "").trim().ifEmpty { tagName }
            val body = json.optString("body", "").replace("\r\n", "\n").trim()
            val publishedAt = json.optString("published_at", "")
            val assetName = chosenAsset.optString("name", "update.apk")
            val downloadUrl = chosenAsset.optString("browser_download_url", "")
            val sizeBytes = chosenAsset.optLong("size", -1L)

            val versionName = extractVersionName(tagName, releaseName, assetName)
                ?: tagName.removePrefix("v").ifBlank { "0.0.0" }
            val versionCode = extractVersionCode(body)

            return GitHubReleaseInfo(
                tagName = tagName,
                releaseName = releaseName,
                versionName = versionName,
                versionCode = versionCode,
                releaseNotes = body,
                publishedAt = publishedAt,
                apkAssetName = assetName,
                apkDownloadUrl = downloadUrl,
                apkSizeBytes = sizeBytes
            )
        }

        fun extractVersionName(tagName: String, releaseName: String, assetName: String): String? {
            return SEMVER_REGEX.find(tagName)?.groupValues?.get(1)
                ?: SEMVER_REGEX.find(releaseName)?.groupValues?.get(1)
                ?: SEMVER_REGEX.find(assetName)?.groupValues?.get(1)
        }

        fun extractVersionCode(body: String): Long? {
            return VERSION_CODE_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()
        }

        fun compareVersions(remote: String, local: String): Int {
            val remoteParts = SEMVER_REGEX.find(remote)?.groupValues?.get(1)
                ?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return 0
            val localParts = SEMVER_REGEX.find(local)?.groupValues?.get(1)
                ?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return 0
            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r != l) return r.compareTo(l)
            }
            return 0
        }

        fun isRemoteNewer(remote: GitHubReleaseInfo, installed: InstalledVersionInfo): Boolean {
            val cmp = compareVersions(remote.versionName, installed.versionName)
            if (cmp > 0) return true
            if (cmp < 0) return false
            val remoteCode = remote.versionCode
            return remoteCode != null && remoteCode > installed.versionCode
        }
    }
}
