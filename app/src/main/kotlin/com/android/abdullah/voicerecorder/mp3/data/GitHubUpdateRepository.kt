package com.android.abdullah.voicerecorder.mp3.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Xml
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.HtmlCompat
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale

data class InstalledVersionInfo(
    val versionName: String,
    val versionCode: Long
)

sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : ReleaseCheckResult
    data class UpToDate(val latestVersion: String?) : ReleaseCheckResult
}

data class AtomReleaseEntry(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val updatedAt: String,
    val htmlUrl: String
)

data class ParsedApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

class GitHubUpdateRepository(private val context: Context) {

    private val cachePrefs by lazy {
        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getInstalledVersion(): InstalledVersionInfo {
        return try {
            val pkgInfo = getPackageInfoCompat(context.packageName, 0)
            InstalledVersionInfo(
                versionName = pkgInfo?.versionName ?: "1.0.5",
                versionCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 6L
            )
        } catch (_: Exception) {
            InstalledVersionInfo(versionName = "1.0.5", versionCode = 6L)
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
        if (!hasMatchingSha256(targetFile, release.apkSha256)) {
            return null
        }

        return if (isValidUpdateApk(targetFile)) targetFile else null
    }

    fun isValidUpdateApk(apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() <= 0L) return false
        val archiveInfo = getPackageArchiveInfoCompat(apkFile.absolutePath, 0) ?: return false
        if (archiveInfo.packageName != context.packageName) return false
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedVersionCode = getInstalledVersion().versionCode
        if (archiveVersionCode < installedVersionCode) return false
        if (isReleaseBuild() && !hasCompatibleSigningCertificate(apkFile)) return false
        return true
    }

    private fun hasMatchingSha256(file: File, expectedSha256: String?): Boolean {
        val expected = expectedSha256?.trim()?.lowercase(Locale.US)
        if (expected.isNullOrBlank()) return true
        return computeSha256Hex(file).equals(expected, ignoreCase = true)
    }

    fun isReleaseBuild(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
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

    suspend fun checkLatestRelease(rawRepo: String = TRUSTED_GITHUB_REPO): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val repoSlug = normalizeRepoSlug(rawRepo)
        if (repoSlug == null || !repoSlug.equals(TRUSTED_GITHUB_REPO, ignoreCase = true)) {
            throw SecurityException("Updates are restricted to the official repository ($TRUSTED_GITHUB_REPO).")
        }

        val installed = getInstalledVersion()
        val latestRelease = try {
            fetchLatestReleaseUnlimited(TRUSTED_GITHUB_REPO)
        } catch (e: UnknownHostException) {
            throw IllegalStateException("No internet connection. Check your network and try again.", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Connection to GitHub timed out. Please try again.", e)
        } catch (e: IOException) {
            throw IllegalStateException("Network error while contacting GitHub: ${e.message ?: "I/O error"}", e)
        } ?: return@withContext ReleaseCheckResult.UpToDate(latestVersion = installed.versionName)

        if (!isTrustedReleaseDownloadUrl(latestRelease.apkDownloadUrl)) {
            throw SecurityException("Blocked untrusted update download URL.")
        }

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
        expectedSha256 = release.apkSha256,
        onProgress = onProgress
    )

    suspend fun downloadApk(
        downloadUrl: String,
        assetFileName: String,
        expectedSizeBytes: Long,
        expectedSha256: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!isTrustedReleaseDownloadUrl(downloadUrl)) {
            throw SecurityException("Blocked untrusted download URL. Only official releases from $TRUSTED_GITHUB_REPO are permitted.")
        }

        val updatesDir = File(context.filesDir, UPDATES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

        val safeName = sanitizeApkFileName(assetFileName)

        updatesDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name != safeName) {
                existing.delete()
            }
        }

        val finalFile = File(updatesDir, safeName)
        if (finalFile.isFile &&
            finalFile.length() > 0L &&
            (expectedSizeBytes <= 0L || finalFile.length() == expectedSizeBytes) &&
            hasMatchingSha256(finalFile, expectedSha256) &&
            isValidUpdateApk(finalFile)
        ) {
            onProgress(finalFile.length(), finalFile.length())
            return@withContext finalFile
        }

        val tempFile = File(updatesDir, "$safeName.tmp")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(
                urlString = downloadUrl,
                acceptHeader = "application/octet-stream",
                allowAssetCdnHosts = true
            )
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
            if (!hasMatchingSha256(tempFile, expectedSha256)) {
                throw SecurityException("Downloaded APK checksum verification failed.")
            }

            val archiveInfo = getPackageArchiveInfoCompat(tempFile.absolutePath, 0)
                ?: throw IllegalStateException("Downloaded file is not a valid Android APK package.")
            if (archiveInfo.packageName != context.packageName) {
                throw SecurityException("Downloaded APK package (${archiveInfo.packageName}) does not match this app.")
            }
            val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
            val installedVersionCode = getInstalledVersion().versionCode
            if (archiveVersionCode < installedVersionCode) {
                throw SecurityException("Blocked downgrade APK (versionCode=$archiveVersionCode < $installedVersionCode).")
            }
            if (isReleaseBuild() && !hasCompatibleSigningCertificate(tempFile)) {
                throw SecurityException("Blocked APK with untrusted signing certificate.")
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

    /**
     * Fetches the latest GitHub Release using rate-limit-free workflow manifest endpoints
     * (raw CDN feed + release asset download) and releases.atom fallback, with ETag /
     * Last-Modified conditional caching. Never touches api.github.com's 60 req/hr quota.
     */
    private fun fetchLatestReleaseUnlimited(repoSlug: String): GitHubReleaseInfo? {
        val cachedRelease = loadCachedRelease(repoSlug)
        var lastError: Throwable? = null

        // 1) Primary: Static update-manifest.json published by build-apk.yml workflow (CDN-backed, 0 API limit)
        try {
            val manifestResult = fetchFromWorkflowManifest(repoSlug, cachedRelease)
            if (manifestResult != null) {
                saveCachedRelease(repoSlug, manifestResult)
                return manifestResult
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            lastError = t
        }

        // 2) Secondary: github.com/{owner}/{repo}/releases.atom (rate-limit-free + ETag 304)
        try {
            val atomResult = fetchFromAtomFeed(repoSlug, cachedRelease)
            if (atomResult != null) {
                saveCachedRelease(repoSlug, atomResult)
                return atomResult
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (lastError == null) lastError = t
        }

        // 3) Tertiary rate-limit-free path: /releases/latest redirect + expanded_assets
        return try {
            val redirectTag = resolveLatestTagViaRedirect(repoSlug)
            if (redirectTag != null) {
                if (cachedRelease != null &&
                    cachedRelease.tagName == redirectTag &&
                    cachedRelease.apkDownloadUrl.isNotBlank()
                ) {
                    return cachedRelease
                }
                val entry = AtomReleaseEntry(
                    tagName = redirectTag,
                    title = redirectTag,
                    releaseNotes = "",
                    updatedAt = "",
                    htmlUrl = "https://github.com/$repoSlug/releases/tag/${Uri.encode(redirectTag)}"
                )
                val resolved = resolveReleaseAssetsForEntry(repoSlug, entry)
                if (resolved != null) {
                    saveCachedRelease(repoSlug, resolved)
                    return resolved
                }
            }
            if (cachedRelease != null) cachedRelease else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (cachedRelease != null) {
                cachedRelease
            } else {
                throw (lastError ?: t)
            }
        }
    }

    private fun fetchFromWorkflowManifest(
        repoSlug: String,
        cachedRelease: GitHubReleaseInfo?
    ): GitHubReleaseInfo? {
        // 1A) Fastly CDN raw endpoint on update-feed branch with ETag 304 support
        val rawManifestUrl = "https://raw.githubusercontent.com/$repoSlug/$UPDATE_FEED_BRANCH/$UPDATE_MANIFEST_FILE"
        val prevRepo = cachePrefs.getString(KEY_CACHED_REPO, null)
        val sameRepo = prevRepo == repoSlug
        val prevEtag = if (sameRepo) cachePrefs.getString(KEY_MANIFEST_ETAG, null) else null
        val prevModified = if (sameRepo) cachePrefs.getString(KEY_MANIFEST_LAST_MODIFIED, null) else null

        val extraHeaders = buildMap {
            if (!prevEtag.isNullOrBlank() && cachedRelease != null) {
                put("If-None-Match", prevEtag)
            }
            if (!prevModified.isNullOrBlank() && cachedRelease != null) {
                put("If-Modified-Since", prevModified)
            }
        }

        var rawError: Throwable? = null
        var rawConn: HttpURLConnection? = null
        try {
            rawConn = openFollowingRedirects(
                urlString = rawManifestUrl,
                acceptHeader = "application/json",
                extraHeaders = extraHeaders,
                allowAssetCdnHosts = true
            )
            val code = rawConn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED &&
                cachedRelease != null &&
                isTrustedReleaseDownloadUrl(cachedRelease.apkDownloadUrl)
            ) {
                return cachedRelease
            }
            if (code in 200..299) {
                val newEtag = rawConn.getHeaderField("ETag")?.trim()
                val newLastModified = rawConn.getHeaderField("Last-Modified")?.trim()
                val body = rawConn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseUpdateManifestJson(body)
                if (parsed != null && isTrustedReleaseDownloadUrl(parsed.apkDownloadUrl)) {
                    saveManifestHeaders(repoSlug, newEtag, newLastModified)
                    return parsed
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            rawError = t
        } finally {
            rawConn?.disconnect()
        }

        // 1B) Fallback to GitHub Release asset manifest (releases/latest/download/update-manifest.json)
        val releaseManifestUrl = "https://github.com/$repoSlug/releases/latest/download/$UPDATE_MANIFEST_FILE"
        var probeConn: HttpURLConnection? = null
        try {
            val probeUrl = URL(releaseManifestUrl)
            if (isAllowedGitHubUrl(probeUrl, allowAssetCdnHosts = false)) {
                probeConn = (probeUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                val status = probeConn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val loc = probeConn.getHeaderField("Location").orEmpty()
                    val redirectedTag = loc.substringAfter("/releases/download/", "")
                        .substringBefore('/')
                        .trim()
                    if (redirectedTag.isNotEmpty() &&
                        cachedRelease != null &&
                        cachedRelease.tagName == Uri.decode(redirectedTag) &&
                        !cachedRelease.apkSha256.isNullOrBlank() &&
                        isTrustedReleaseDownloadUrl(cachedRelease.apkDownloadUrl)
                    ) {
                        return cachedRelease
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Continue to full fetch below
        } finally {
            probeConn?.disconnect()
        }

        var assetConn: HttpURLConnection? = null
        try {
            assetConn = openFollowingRedirects(
                urlString = releaseManifestUrl,
                acceptHeader = "application/json",
                allowAssetCdnHosts = true
            )
            val code = assetConn.responseCode
            if (code in 200..299) {
                val body = assetConn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseUpdateManifestJson(body)
                if (parsed != null && isTrustedReleaseDownloadUrl(parsed.apkDownloadUrl)) {
                    return parsed
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (rawError == null) rawError = t
        } finally {
            assetConn?.disconnect()
        }

        if (rawError != null) throw rawError
        return null
    }

    private fun fetchFromAtomFeed(
        repoSlug: String,
        cachedRelease: GitHubReleaseInfo?
    ): GitHubReleaseInfo? {
        val atomUrl = "https://github.com/$repoSlug/releases.atom"
        val prevRepo = cachePrefs.getString(KEY_CACHED_REPO, null)
        val sameRepo = prevRepo == repoSlug
        val prevEtag = if (sameRepo) cachePrefs.getString(KEY_ATOM_ETAG, null) else null
        val prevModified = if (sameRepo) cachePrefs.getString(KEY_ATOM_LAST_MODIFIED, null) else null

        val extraHeaders = buildMap {
            if (!prevEtag.isNullOrBlank() && cachedRelease != null) {
                put("If-None-Match", prevEtag)
            }
            if (!prevModified.isNullOrBlank() && cachedRelease != null) {
                put("If-Modified-Since", prevModified)
            }
        }

        val conn = openFollowingRedirects(
            urlString = atomUrl,
            acceptHeader = "application/atom+xml, application/xml, text/xml",
            extraHeaders = extraHeaders
        )
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cachedRelease != null) {
                return cachedRelease
            }
            if (code == 404) {
                throw IllegalStateException("Repository '$repoSlug' was not found on GitHub.")
            }
            if (code !in 200..299) {
                throw IllegalStateException(formatHttpError("GitHub feed error", code, conn))
            }

            val newEtag = conn.getHeaderField("ETag")?.trim()
            val newLastModified = conn.getHeaderField("Last-Modified")?.trim()
            val xmlPayload = conn.inputStream.bufferedReader().use { it.readText() }
            val entries = parseAtomFeedEntries(xmlPayload)

            if (entries.isEmpty()) {
                return null
            }

            // Prefer the latest non-prerelease tag if /releases/latest points to a valid entry,
            // otherwise use the newest entry in the feed that has an .apk asset.
            val latestStableTag = runCatching { resolveLatestTagViaRedirect(repoSlug) }.getOrNull()
            val orderedEntries = if (!latestStableTag.isNullOrBlank()) {
                val matching = entries.filter { it.tagName == latestStableTag }
                val others = entries.filterNot { it.tagName == latestStableTag }
                matching + others
            } else {
                entries
            }

            for (entry in orderedEntries.take(5)) {
                if (cachedRelease != null &&
                    cachedRelease.tagName == entry.tagName &&
                    cachedRelease.apkDownloadUrl.isNotBlank() &&
                    (entry.releaseNotes.isBlank() || cachedRelease.releaseNotes == entry.releaseNotes)
                ) {
                    saveAtomHeaders(repoSlug, newEtag, newLastModified)
                    return cachedRelease
                }

                val release = resolveReleaseAssetsForEntry(repoSlug, entry)
                if (release != null) {
                    saveAtomHeaders(repoSlug, newEtag, newLastModified)
                    return release
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveLatestTagViaRedirect(repoSlug: String): String? {
        val url = URL("https://github.com/$repoSlug/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html")
        }
        try {
            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location").orEmpty()
                val tagPart = location.substringAfter("/releases/tag/", "")
                    .substringBefore('?')
                    .substringBefore('#')
                    .trim()
                if (tagPart.isNotEmpty()) {
                    return Uri.decode(tagPart)
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveReleaseAssetsForEntry(
        repoSlug: String,
        entry: AtomReleaseEntry
    ): GitHubReleaseInfo? {
        val encodedTag = Uri.encode(entry.tagName)
        val assetsUrl = "https://github.com/$repoSlug/releases/expanded_assets/$encodedTag"
        val conn = openFollowingRedirects(
            urlString = assetsUrl,
            acceptHeader = "text/html"
        )
        val apkAssets = try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return null
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            parseExpandedAssetsHtml(html)
        } finally {
            conn.disconnect()
        }

        val trustedAssets = apkAssets.filter { isTrustedReleaseDownloadUrl(it.downloadUrl) }
        if (trustedAssets.isEmpty()) return null
        val chosen = selectBestApkAsset(trustedAssets)
        val exactSize = resolveRemoteFileSize(chosen.downloadUrl) ?: chosen.sizeBytes

        val versionName = extractVersionName(entry.tagName, entry.title, chosen.name)
            ?: entry.tagName.removePrefix("v").ifBlank { "0.0.0" }
        val versionCode = extractVersionCode(entry.releaseNotes)

        return GitHubReleaseInfo(
            tagName = entry.tagName,
            releaseName = entry.title.ifBlank { entry.tagName },
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = entry.releaseNotes,
            publishedAt = entry.updatedAt,
            apkAssetName = chosen.name,
            apkDownloadUrl = chosen.downloadUrl,
            apkSizeBytes = exactSize,
            htmlUrl = entry.htmlUrl
        )
    }

    private fun resolveRemoteFileSize(downloadUrl: String): Long? {
        if (!isTrustedReleaseDownloadUrl(downloadUrl)) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowingRedirects(
                urlString = downloadUrl,
                acceptHeader = "application/octet-stream",
                method = "HEAD",
                allowAssetCdnHosts = true
            )
            if (conn.responseCode in 200..299) {
                conn.contentLengthLong.takeIf { it > 0L }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun saveManifestHeaders(repoSlug: String, etag: String?, lastModified: String?) {
        cachePrefs.edit()
            .putString(KEY_CACHED_REPO, repoSlug)
            .apply {
                if (!etag.isNullOrBlank()) putString(KEY_MANIFEST_ETAG, etag)
                if (!lastModified.isNullOrBlank()) putString(KEY_MANIFEST_LAST_MODIFIED, lastModified)
            }
            .apply()
    }

    private fun saveAtomHeaders(repoSlug: String, etag: String?, lastModified: String?) {
        cachePrefs.edit()
            .putString(KEY_CACHED_REPO, repoSlug)
            .apply {
                if (!etag.isNullOrBlank()) putString(KEY_ATOM_ETAG, etag)
                if (!lastModified.isNullOrBlank()) putString(KEY_ATOM_LAST_MODIFIED, lastModified)
            }
            .apply()
    }

    private fun saveCachedRelease(repoSlug: String, release: GitHubReleaseInfo) {
        val json = JSONObject().apply {
            put("tagName", release.tagName)
            put("releaseName", release.releaseName)
            put("versionName", release.versionName)
            if (release.versionCode != null) {
                put("versionCode", release.versionCode)
            }
            put("releaseNotes", release.releaseNotes)
            put("publishedAt", release.publishedAt)
            put("apkAssetName", release.apkAssetName)
            put("apkDownloadUrl", release.apkDownloadUrl)
            put("apkSizeBytes", release.apkSizeBytes)
            put("htmlUrl", release.htmlUrl)
            if (!release.apkSha256.isNullOrBlank()) {
                put("apkSha256", release.apkSha256)
            }
        }
        cachePrefs.edit()
            .putString(KEY_CACHED_REPO, repoSlug)
            .putString(KEY_CACHED_RELEASE_JSON, json.toString())
            .apply()
    }

    private fun loadCachedRelease(repoSlug: String): GitHubReleaseInfo? {
        if (!repoSlug.equals(TRUSTED_GITHUB_REPO, ignoreCase = true)) return null
        if (cachePrefs.getString(KEY_CACHED_REPO, null) != TRUSTED_GITHUB_REPO) return null
        val raw = cachePrefs.getString(KEY_CACHED_RELEASE_JSON, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val downloadUrl = json.optString("apkDownloadUrl", "")
            val tagName = json.optString("tagName", "")
            if (downloadUrl.isBlank() || tagName.isBlank()) return null
            if (!isTrustedReleaseDownloadUrl(downloadUrl)) return null
            GitHubReleaseInfo(
                tagName = tagName,
                releaseName = json.optString("releaseName", tagName),
                versionName = json.optString("versionName", "0.0.0"),
                versionCode = if (json.has("versionCode")) json.optLong("versionCode") else null,
                releaseNotes = json.optString("releaseNotes", ""),
                publishedAt = json.optString("publishedAt", ""),
                apkAssetName = json.optString("apkAssetName", "update.apk"),
                apkDownloadUrl = downloadUrl,
                apkSizeBytes = json.optLong("apkSizeBytes", -1L),
                htmlUrl = json.optString("htmlUrl", ""),
                apkSha256 = json.optString("apkSha256", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
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
                "GitHub rate limit reached. Try again in about $waitMins min."
            } else {
                "GitHub rate limit reached. Please try again later."
            }
        }
        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val msg = extractApiMessage(errBody)
        return "$prefix ($code)${if (msg.isNotBlank()) ": $msg" else ""}"
    }

    private fun openFollowingRedirects(
        urlString: String,
        acceptHeader: String,
        method: String = "GET",
        extraHeaders: Map<String, String> = emptyMap(),
        allowAssetCdnHosts: Boolean = false,
        maxRedirects: Int = 8
    ): HttpURLConnection {
        var currentUrl = urlString
        var redirects = 0
        while (true) {
            val parsedUrl = URL(currentUrl)
            if (!isAllowedGitHubUrl(parsedUrl, allowAssetCdnHosts)) {
                throw SecurityException("Blocked connection to untrusted host: ${parsedUrl.host}")
            }
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = method
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", USER_AGENT)
                if (parsedUrl.host.equals("api.github.com", ignoreCase = true)) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                if (redirects == 0) {
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
        const val TRUSTED_GITHUB_REPO = "webapandroid-oss/Voice-recorder-MP3"
        private const val UPDATE_FEED_BRANCH = "update-feed"
        private const val UPDATE_MANIFEST_FILE = "update-manifest.json"
        private const val TRUSTED_DOWNLOAD_PREFIX = "https://github.com/webapandroid-oss/Voice-recorder-MP3/releases/download/"
        private const val UPDATES_DIR_NAME = "updates"
        private const val CACHE_PREFS_NAME = "github_update_cache"
        private const val KEY_CACHED_REPO = "cached_repo"
        private const val KEY_MANIFEST_ETAG = "manifest_etag"
        private const val KEY_MANIFEST_LAST_MODIFIED = "manifest_last_modified"
        private const val KEY_ATOM_ETAG = "atom_etag"
        private const val KEY_ATOM_LAST_MODIFIED = "atom_last_modified"
        private const val KEY_CACHED_RELEASE_JSON = "cached_release_json"
        private const val USER_AGENT = "VoiceRecorder-Android-Updater/1.3"

        fun isTrustedReleaseDownloadUrl(urlString: String): Boolean {
            val trimmed = urlString.trim()
            if (!trimmed.startsWith(TRUSTED_DOWNLOAD_PREFIX, ignoreCase = true)) return false
            val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return false
            return parsed.protocol.equals("https", ignoreCase = true) &&
                parsed.host.equals("github.com", ignoreCase = true) &&
                parsed.path.startsWith("/$TRUSTED_GITHUB_REPO/releases/download/", ignoreCase = true) &&
                parsed.path.endsWith(".apk", ignoreCase = true)
        }

        fun isAllowedGitHubUrl(url: URL, allowAssetCdnHosts: Boolean): Boolean {
            if (!url.protocol.equals("https", ignoreCase = true)) return false
            val host = url.host.lowercase(Locale.US)
            if (host == "github.com" || host == "api.github.com") return true
            if (!allowAssetCdnHosts) return false
            return host == "objects.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        }

        private val SEMVER_REGEX = Regex("""(\d+(?:\.\d+)+)""")
        private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val REPO_URL_REGEX = Regex(
            """^(?:(?:https?://)?(?:www\.)?github\.com/)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git|/(?:releases|tags|tree|blob|issues|actions).*)?/?$""",
            RegexOption.IGNORE_CASE
        )
        private val APK_HREF_REGEX = Regex(
            """href\s*=\s*["']([^"']*?/releases/download/[^"']+?\.apk(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        private val ASSET_SIZE_REGEX = Regex(
            """(\d+(?:\.\d+)?)\s*(Bytes?|KB|KiB|MB|MiB|GB|GiB)\b""",
            RegexOption.IGNORE_CASE
        )

        fun normalizeRepoSlug(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val match = REPO_URL_REGEX.matchEntire(trimmed) ?: return null
            return match.groupValues[1].removeSuffix(".git")
        }

        fun parseAtomFeedEntries(xmlPayload: String): List<AtomReleaseEntry> {
            val entries = mutableListOf<AtomReleaseEntry>()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xmlPayload))

            var inEntry = false
            var tagId = ""
            var title = ""
            var rawHtmlNotes = ""
            var updated = ""
            var linkHref = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase(Locale.US)) {
                            "entry" -> {
                                inEntry = true
                                tagId = ""
                                title = ""
                                rawHtmlNotes = ""
                                updated = ""
                                linkHref = ""
                            }

                            "id" -> if (inEntry) {
                                tagId = parser.nextText().trim()
                            }

                            "title" -> if (inEntry) {
                                title = parser.nextText().trim()
                            }

                            "updated" -> if (inEntry) {
                                updated = parser.nextText().trim()
                            }

                            "content" -> if (inEntry) {
                                rawHtmlNotes = parser.nextText()
                            }

                            "link" -> if (inEntry) {
                                val rel = parser.getAttributeValue(null, "rel").orEmpty()
                                val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                                if ((rel.isBlank() || rel == "alternate") && href.isNotBlank()) {
                                    linkHref = href
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("entry", ignoreCase = true) && inEntry) {
                            inEntry = false
                            val rawTagFromLink = linkHref.substringAfter("/releases/tag/", "")
                                .substringBefore('?')
                                .substringBefore('#')
                                .trim()
                            val rawTagFromId = tagId.substringAfterLast('/').trim()
                            val resolvedTag = when {
                                rawTagFromLink.isNotBlank() -> Uri.decode(rawTagFromLink)
                                rawTagFromId.isNotBlank() -> Uri.decode(rawTagFromId)
                                else -> ""
                            }
                            if (resolvedTag.isNotBlank()) {
                                entries.add(
                                    AtomReleaseEntry(
                                        tagName = resolvedTag,
                                        title = title.ifBlank { resolvedTag },
                                        releaseNotes = htmlToReadableText(rawHtmlNotes),
                                        updatedAt = updated,
                                        htmlUrl = linkHref
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            return entries
        }

        fun parseExpandedAssetsHtml(html: String): List<ParsedApkAsset> {
            val results = mutableListOf<ParsedApkAsset>()
            val seenUrls = mutableSetOf<String>()
            val matches = APK_HREF_REGEX.findAll(html).toList()

            for ((index, match) in matches.withIndex()) {
                val rawHref = match.groupValues[1].trim()
                val fullUrl = when {
                    rawHref.startsWith("http://", ignoreCase = true) ||
                        rawHref.startsWith("https://", ignoreCase = true) -> rawHref
                    rawHref.startsWith("/") -> "https://github.com$rawHref"
                    else -> "https://github.com/$rawHref"
                }
                if (!seenUrls.add(fullUrl)) continue

                val assetName = Uri.decode(
                    fullUrl.substringAfterLast('/').substringBefore('?')
                ).ifBlank { "update.apk" }

                val nextStart = matches.getOrNull(index + 1)?.range?.first ?: (match.range.last + 1200)
                val snippetEnd = minOf(html.length, nextStart, match.range.last + 1200)
                val snippet = html.substring(match.range.last, snippetEnd)
                val parsedSize = parseHumanFileSizeBytes(snippet)

                results.add(
                    ParsedApkAsset(
                        name = assetName,
                        downloadUrl = fullUrl,
                        sizeBytes = parsedSize
                    )
                )
            }
            return results
        }

        private fun parseHumanFileSizeBytes(snippet: String): Long {
            val match = ASSET_SIZE_REGEX.find(snippet) ?: return -1L
            val value = match.groupValues[1].toDoubleOrNull() ?: return -1L
            val unit = match.groupValues[2].lowercase(Locale.US)
            val multiplier = when (unit) {
                "b", "byte", "bytes" -> 1.0
                "kb", "kib" -> 1024.0
                "mb", "mib" -> 1024.0 * 1024.0
                "gb", "gib" -> 1024.0 * 1024.0 * 1024.0
                else -> return -1L
            }
            return (value * multiplier).toLong().takeIf { it > 0L } ?: -1L
        }

        private fun selectBestApkAsset(apkAssets: List<ParsedApkAsset>): ParsedApkAsset {
            val supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList()
            return apkAssets.firstOrNull { asset ->
                supportedAbis.any { abi -> asset.name.contains(abi, ignoreCase = true) } &&
                    asset.name.contains("release", ignoreCase = true)
            } ?: apkAssets.firstOrNull { asset ->
                supportedAbis.any { abi -> asset.name.contains(abi, ignoreCase = true) }
            } ?: apkAssets.firstOrNull {
                it.name.contains("release", ignoreCase = true)
            } ?: apkAssets.first()
        }

        private fun htmlToReadableText(rawHtml: String): String {
            if (rawHtml.isBlank()) return ""
            val plain = try {
                HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            } catch (_: Throwable) {
                rawHtml
                    .replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
                    .replace(Regex("(?i)<li[^>]*>"), "• ")
                    .replace(Regex("<[^>]+>"), "")
            }
            return plain
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        fun parseReleaseJson(json: JSONObject): GitHubReleaseInfo? {
            if (json.optBoolean("draft", false)) return null
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkAssets = mutableListOf<ParsedApkAsset>()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", -1L)
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkAssets.add(ParsedApkAsset(name = name, downloadUrl = url, sizeBytes = size))
                }
            }
            if (apkAssets.isEmpty()) return null

            val chosenAsset = selectBestApkAsset(apkAssets)
            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", "").trim().ifEmpty { tagName }
            val body = json.optString("body", "").replace("\r\n", "\n").trim()
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", "").trim()

            val versionName = extractVersionName(tagName, releaseName, chosenAsset.name)
                ?: tagName.removePrefix("v").ifBlank { "0.0.0" }
            val versionCode = extractVersionCode(body)

            return GitHubReleaseInfo(
                tagName = tagName,
                releaseName = releaseName,
                versionName = versionName,
                versionCode = versionCode,
                releaseNotes = body,
                publishedAt = publishedAt,
                apkAssetName = chosenAsset.name,
                apkDownloadUrl = chosenAsset.downloadUrl,
                apkSizeBytes = chosenAsset.sizeBytes,
                htmlUrl = htmlUrl
            )
        }

        fun parseUpdateManifestJson(rawJson: String): GitHubReleaseInfo? {
            return try {
                val json = JSONObject(rawJson)
                val tagName = json.optString("tag_name", json.optString("tagName", "")).trim()
                val downloadUrl = json.optString("apk_download_url", json.optString("apkDownloadUrl", "")).trim()
                if (tagName.isBlank() || downloadUrl.isBlank()) return null

                val releaseName = json.optString("release_name", json.optString("releaseName", tagName))
                    .trim()
                    .ifBlank { tagName }
                val assetName = json.optString("apk_asset_name", json.optString("apkAssetName", "update.apk"))
                    .trim()
                    .ifBlank { "update.apk" }
                val rawVersionName = json.optString("version_name", json.optString("versionName", "")).trim()
                val versionName = rawVersionName.ifBlank {
                    extractVersionName(tagName, releaseName, assetName)
                        ?: tagName.removePrefix("v").ifBlank { "0.0.0" }
                }
                val releaseNotes = json.optString("release_notes", json.optString("releaseNotes", ""))
                    .replace("\r\n", "\n")
                    .trim()
                val versionCode = when {
                    json.has("version_code") -> json.optLong("version_code").takeIf { it > 0L }
                    json.has("versionCode") -> json.optLong("versionCode").takeIf { it > 0L }
                    else -> extractVersionCode(releaseNotes)
                }
                val sizeBytes = when {
                    json.has("apk_size_bytes") -> json.optLong("apk_size_bytes", -1L)
                    json.has("apkSizeBytes") -> json.optLong("apkSizeBytes", -1L)
                    else -> -1L
                }
                val sha256 = json.optString("apk_sha256", json.optString("apkSha256", ""))
                    .trim()
                    .lowercase(Locale.US)
                    .takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
                val publishedAt = json.optString("published_at", json.optString("publishedAt", "")).trim()
                val htmlUrl = json.optString("html_url", json.optString("htmlUrl", "")).trim()

                GitHubReleaseInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    versionName = versionName,
                    versionCode = versionCode,
                    releaseNotes = releaseNotes,
                    publishedAt = publishedAt,
                    apkAssetName = assetName,
                    apkDownloadUrl = downloadUrl,
                    apkSizeBytes = sizeBytes,
                    htmlUrl = htmlUrl,
                    apkSha256 = sha256
                )
            } catch (_: Exception) {
                null
            }
        }

        fun computeSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
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
