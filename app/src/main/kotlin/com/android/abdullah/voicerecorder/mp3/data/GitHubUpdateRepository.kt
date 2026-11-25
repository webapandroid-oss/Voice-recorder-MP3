package com.android.abdullah.voicerecorder.mp3.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

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
            val pkgInfo = getPackageInfoCompat(context.packageName, 0)
            InstalledVersionInfo(
                versionName = pkgInfo?.versionName ?: "1.0.3",
                versionCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 4L
            )
        } catch (_: Exception) {
            InstalledVersionInfo(versionName = "1.0.3", versionCode = 4L)
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
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    suspend fun installApkViaPackageInstaller(apkFile: File) = withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        // Abandon any stale unclosed sessions owned by this app
        runCatching {
            packageInstaller.mySessions.forEach { info ->
                runCatching { packageInstaller.abandonSession(info.sessionId) }
            }
        }

        val apkSize = apkFile.length()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            session = packageInstaller.openSession(sessionId)
            session.openWrite("voice-recorder-update.apk", 0, apkSize).use { out ->
                apkFile.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
                session.fsync(out)
            }

            val callbackIntent = Intent(context, ApkInstallReceiver::class.java)
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                pendingFlags
            )
            session.commit(pendingIntent.intentSender)
        } catch (t: Throwable) {
            runCatching { session?.abandon() }
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw t
        } finally {
            runCatching { session?.close() }
        }
    }

    fun findValidDownloadedApk(release: GitHubReleaseInfo): File? {
        val updatesDir = File(context.filesDir, UPDATES_DIR_NAME)
        if (!updatesDir.exists()) return null

        val targetFile = File(updatesDir, resolveApkFileName(release))
        if (!targetFile.isFile || targetFile.length() <= 0L) return null

        if (release.apkSizeBytes > 0L && targetFile.length() != release.apkSizeBytes) {
            return null
        }

        return if (isValidUpdateApk(targetFile)) targetFile else null
    }

    fun isValidUpdateApk(apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() <= 0L) return false
        val archiveInfo = getPackageArchiveInfoCompat(apkFile.absolutePath, 0) ?: return false
        return archiveInfo.packageName == context.packageName
    }

    fun hasCompatibleSigningCertificate(apkFile: File): Boolean {
        return try {
            val installedCerts = extractSigningCerts(
                getPackageInfoCompat(context.packageName, signingFlags())
            )
            val archiveCerts = extractSigningCerts(
                getPackageArchiveInfoCompat(apkFile.absolutePath, signingFlags())
            )
            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
                true
            } else {
                installedCerts.intersect(archiveCerts).isNotEmpty()
            }
        } catch (_: Exception) {
            true
        }
    }

    suspend fun checkLatestRelease(rawRepo: String): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val repoSlug = normalizeRepoSlug(rawRepo)
            ?: throw IllegalArgumentException("Invalid GitHub repository format. Use owner/repo or a GitHub URL.")

        val installed = getInstalledVersion()
        val latestRelease = try {
            fetchLatestGitHubRelease(repoSlug)
        } catch (e: UnknownHostException) {
            throw IllegalStateException("No internet connection. Check your network and try again.", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Connection to GitHub timed out. Please try again.", e)
        } catch (e: IOException) {
            throw IllegalStateException("Network error while contacting GitHub: ${e.message ?: "I/O error"}", e)
        } ?: return@withContext ReleaseCheckResult.UpToDate(latestVersion = installed.versionName)

        if (isRemoteNewer(latestRelease, installed)) {
            ReleaseCheckResult.UpdateAvailable(latestRelease)
        } else {
            ReleaseCheckResult.UpToDate(latestVersion = latestRelease.versionName)
        }
    }

    suspend fun downloadApk(
        release: GitHubReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = downloadApk(
        downloadUrl = release.apkDownloadUrl,
        assetFileName = resolveApkFileName(release),
        expectedSizeBytes = release.apkSizeBytes,
        onProgress = onProgress
    )

    suspend fun downloadApk(
        downloadUrl: String,
        assetFileName: String,
        expectedSizeBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.filesDir, UPDATES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

        val safeName = sanitizeApkFileName(assetFileName)

        // Remove older APKs to keep storage clean
        updatesDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name != safeName) {
                existing.delete()
            }
        }

        val finalFile = File(updatesDir, safeName)
        if (finalFile.isFile &&
            finalFile.length() > 0L &&
            (expectedSizeBytes <= 0L || finalFile.length() == expectedSizeBytes) &&
            isValidUpdateApk(finalFile)
        ) {
            onProgress(finalFile.length(), finalFile.length())
            return@withContext finalFile
        }

        val tempFile = File(updatesDir, "$safeName.tmp")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(downloadUrl, acceptHeader = "application/octet-stream")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException(formatHttpError("Download failed", responseCode, connection))
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
                    val buffer = ByteArray(16 * 1024)
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
                throw IllegalStateException("Downloaded APK file is empty.")
            }

            if (expectedSizeBytes > 0L && tempFile.length() != expectedSizeBytes) {
                throw IllegalStateException("Downloaded APK size mismatch (expected $expectedSizeBytes bytes, got ${tempFile.length()}).")
            }

            val archiveInfo = getPackageArchiveInfoCompat(tempFile.absolutePath, 0)
                ?: throw IllegalStateException("Downloaded file is not a valid Android APK package.")
            if (archiveInfo.packageName != context.packageName) {
                throw IllegalStateException("Downloaded APK package (${archiveInfo.packageName}) does not match this app.")
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
        } catch (e: UnknownHostException) {
            tempFile.delete()
            throw IllegalStateException("Internet connection lost while downloading update.", e)
        } catch (e: SocketTimeoutException) {
            tempFile.delete()
            throw IllegalStateException("Download timed out. Please try again.", e)
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
                throw IllegalStateException(formatHttpError("GitHub API error", code, latestConn))
            }
        } finally {
            latestConn.disconnect()
        }

        // Fallback to /releases list (covers prereleases or latest release still uploading assets)
        val listUrl = "https://api.github.com/repos/$repoSlug/releases?per_page=10"
        val listConn = openFollowingRedirects(listUrl, acceptHeader = "application/vnd.github+json")
        try {
            val code = listConn.responseCode
            if (code !in 200..299) {
                if (code == 404) {
                    throw IllegalStateException("Repository '$repoSlug' or its releases were not found on GitHub.")
                }
                throw IllegalStateException(formatHttpError("GitHub API error", code, listConn))
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

    private fun formatHttpError(prefix: String, code: Int, conn: HttpURLConnection): String {
        val rateRemaining = conn.getHeaderField("X-RateLimit-Remaining")
        if ((code == 403 || code == 429) && rateRemaining == "0") {
            val resetEpochSec = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
            val waitMins = resetEpochSec?.let {
                ((it * 1000L - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
            }
            return if (waitMins != null) {
                "GitHub API rate limit reached. Try again in about $waitMins min."
            } else {
                "GitHub API rate limit reached. Please try again later."
            }
        }
        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val msg = extractApiMessage(errBody)
        return "$prefix ($code)${if (msg.isNotBlank()) ": $msg" else ""}"
    }

    private fun openFollowingRedirects(
        urlString: String,
        acceptHeader: String,
        maxRedirects: Int = 8
    ): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (true) {
            val parsedUrl = URL(currentUrl)
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", "VoiceRecorder-Android-Updater/1.1")
                if (parsedUrl.host.equals("api.github.com", ignoreCase = true)) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }

            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= maxRedirects) {
                    throw IllegalStateException("Too many redirects while contacting GitHub.")
                }
                currentUrl = URL(parsedUrl, location).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    private fun extractApiMessage(rawJson: String): String {
        return try {
            JSONObject(rawJson).optString("message", "").take(140)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getPackageInfoCompat(packageName: String, flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, flags)
        }
    }

    private fun getPackageArchiveInfoCompat(archiveFilePath: String, flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                archiveFilePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(archiveFilePath, flags)
        }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun extractSigningCerts(pkgInfo: PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptySet()
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
            }
            signers.orEmpty().map { it.toCharsString() }.toSet()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
    }

    private fun resolveApkFileName(release: GitHubReleaseInfo): String {
        val base = sanitizeApkFileName(release.apkAssetName)
        return if (base.contains(release.versionName, ignoreCase = true)) {
            base
        } else {
            val tagPrefix = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { release.versionName }
            "${tagPrefix}_$base"
        }
    }

    private fun sanitizeApkFileName(rawName: String): String {
        return rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "voice-recorder-update.apk" }
    }

    companion object {
        private const val UPDATES_DIR_NAME = "updates"
        private val SEMVER_REGEX = Regex("""(\d+(?:\.\d+)+)""")
        private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val REPO_URL_REGEX = Regex(
            """^(?:(?:https?://)?(?:www\.)?github\.com/)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git|/(?:releases|tags|tree|blob|issues|actions).*)?/?$""",
            RegexOption.IGNORE_CASE
        )

        fun normalizeRepoSlug(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val match = REPO_URL_REGEX.matchEntire(trimmed) ?: return null
            return match.groupValues[1].removeSuffix(".git")
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

            val supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList()
            val chosenAsset = apkAssets.firstOrNull { asset ->
                val name = asset.optString("name", "")
                supportedAbis.any { abi -> name.contains(abi, ignoreCase = true) } &&
                    name.contains("release", ignoreCase = true)
            } ?: apkAssets.firstOrNull { asset ->
                val name = asset.optString("name", "")
                supportedAbis.any { abi -> name.contains(abi, ignoreCase = true) }
            } ?: apkAssets.firstOrNull {
                it.optString("name", "").contains("release", ignoreCase = true)
            } ?: apkAssets.first()

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", "").trim().ifEmpty { tagName }
            val body = json.optString("body", "").replace("\r\n", "\n").trim()
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", "").trim()
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
                apkSizeBytes = sizeBytes,
                htmlUrl = htmlUrl
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
