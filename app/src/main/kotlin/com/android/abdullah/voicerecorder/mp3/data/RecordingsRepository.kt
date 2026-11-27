package com.android.abdullah.voicerecorder.mp3.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.android.abdullah.voicerecorder.mp3.audio.FfmpegMp3Native
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale

data class SdCardInfo(
    val uuid: String?,
    val mediaStoreVolumeName: String?,
    val displayName: String,
    val directoryPath: String?
)

data class SavedRecordingResult(
    val fileName: String,
    val contentUri: String?,
    val filePath: String,
    val storageLocation: StorageLocation
)

sealed interface DirectRecordingTarget {
    val fileName: String
    val storageLocation: StorageLocation
    fun openOutputStream(): BufferedOutputStream
    fun closeStream(stream: BufferedOutputStream?)
    fun finalizeSuccess(durationMs: Long, totalBytes: Long): SavedRecordingResult?
    fun discardAndDelete()
}

private const val OUTPUT_BUFFER_BYTES = 32 * 1024

private class MediaStoreDirectTarget(
    private val context: Context,
    private val uri: Uri,
    override val fileName: String,
    override val storageLocation: StorageLocation,
    private val displayPathHint: String
) : DirectRecordingTarget {
    private var pfd: ParcelFileDescriptor? = null

    override fun openOutputStream(): BufferedOutputStream {
        val resolver = context.contentResolver
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Unable to open output descriptor for $fileName")
        pfd = descriptor
        return BufferedOutputStream(FileOutputStream(descriptor.fileDescriptor), OUTPUT_BUFFER_BYTES)
    }

    override fun closeStream(stream: BufferedOutputStream?) {
        try {
            stream?.flush()
        } catch (_: Exception) {
        }
        try {
            stream?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }

    override fun finalizeSuccess(durationMs: Long, totalBytes: Long): SavedRecordingResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
            if (durationMs > 0L) {
                runCatching {
                    val durValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DURATION, durationMs)
                    }
                    context.contentResolver.update(uri, durValues, null, null)
                }
            }
        }
        return SavedRecordingResult(
            fileName = fileName,
            contentUri = uri.toString(),
            filePath = displayPathHint,
            storageLocation = storageLocation
        )
    }

    override fun discardAndDelete() {
        runCatching { pfd?.close() }
        pfd = null
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}

private class FileDirectTarget(
    private val context: Context,
    private val file: File,
    override val storageLocation: StorageLocation
) : DirectRecordingTarget {
    override val fileName: String get() = file.name

    override fun openOutputStream(): BufferedOutputStream {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return BufferedOutputStream(FileOutputStream(file), OUTPUT_BUFFER_BYTES)
    }

    override fun closeStream(stream: BufferedOutputStream?) {
        try {
            stream?.flush()
        } catch (_: Exception) {
        }
        try {
            stream?.close()
        } catch (_: Exception) {
        }
    }

    override fun finalizeSuccess(durationMs: Long, totalBytes: Long): SavedRecordingResult? {
        if (!file.exists() || file.length() <= 0L) return null
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("audio/mpeg"),
                null
            )
        }
        return SavedRecordingResult(
            fileName = file.name,
            contentUri = null,
            filePath = file.absolutePath,
            storageLocation = storageLocation
        )
    }

    override fun discardAndDelete() {
        runCatching { if (file.exists()) file.delete() }
    }
}

private class SafDirectTarget(
    private val context: Context,
    private val docUri: Uri,
    override val fileName: String,
    override val storageLocation: StorageLocation
) : DirectRecordingTarget {
    private var pfd: ParcelFileDescriptor? = null

    override fun openOutputStream(): BufferedOutputStream {
        val resolver = context.contentResolver
        val descriptor = resolver.openFileDescriptor(docUri, "w")
            ?: resolver.openFileDescriptor(docUri, "rw")
            ?: throw IllegalStateException("Unable to open SD card output descriptor for $fileName")
        pfd = descriptor
        return BufferedOutputStream(FileOutputStream(descriptor.fileDescriptor), OUTPUT_BUFFER_BYTES)
    }

    override fun closeStream(stream: BufferedOutputStream?) {
        try {
            stream?.flush()
        } catch (_: Exception) {
        }
        try {
            stream?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }

    override fun finalizeSuccess(durationMs: Long, totalBytes: Long): SavedRecordingResult {
        return SavedRecordingResult(
            fileName = fileName,
            contentUri = docUri.toString(),
            filePath = docUri.toString(),
            storageLocation = storageLocation
        )
    }

    override fun discardAndDelete() {
        runCatching { pfd?.close() }
        pfd = null
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
    }
}

class RecordingsRepository(private val context: Context) {

    fun getPublicInternalMusicDir(): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(musicDir, VOICE_RECORDER_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLegacyInternalDir(): File {
        return File(context.filesDir, "recordings")
    }

    fun detectMountedSdCard(): SdCardInfo? {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return null

        val volumes = runCatching { storageManager.storageVolumes }.getOrNull().orEmpty()
        val sdVolume = volumes.firstOrNull { volume ->
            volume.isRemovable &&
                !volume.isPrimary &&
                volume.state == Environment.MEDIA_MOUNTED
        } ?: return null

        val uuid = sdVolume.uuid
        val displayName = runCatching { sdVolume.getDescription(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "SD Card"
        val directoryPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { sdVolume.directory?.absolutePath }.getOrNull()
        } else {
            null
        }

        val mediaStoreVolumeName = resolveMediaStoreVolumeName(sdVolume, uuid)
        return SdCardInfo(
            uuid = uuid,
            mediaStoreVolumeName = mediaStoreVolumeName,
            displayName = displayName,
            directoryPath = directoryPath
        )
    }

    private fun resolveMediaStoreVolumeName(
        sdVolume: android.os.storage.StorageVolume,
        uuid: String?
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val volName = runCatching { sdVolume.mediaStoreVolumeName }.getOrNull()
            if (!volName.isNullOrBlank()) return volName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val allVolumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull().orEmpty()
            val nonPrimary = allVolumes.filterNot { it.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase = true) }
            if (!uuid.isNullOrBlank()) {
                val matched = nonPrimary.firstOrNull { it.equals(uuid, ignoreCase = true) }
                if (matched != null) return matched
            }
            if (nonPrimary.size == 1) {
                return nonPrimary.first()
            }
        }
        return null
    }

    fun buildOpenSdCardTreeIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val sdVolume = storageManager?.storageVolumes?.firstOrNull {
                it.isRemovable && !it.isPrimary && it.state == Environment.MEDIA_MOUNTED
            }
            if (sdVolume != null) {
                runCatching { return sdVolume.createOpenDocumentTreeIntent() }
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    fun canWriteSdCardDirectly(sdCardInfo: SdCardInfo?, savedTreeUri: String?): Boolean {
        if (sdCardInfo == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !sdCardInfo.mediaStoreVolumeName.isNullOrBlank()) {
            if (probeMediaStoreVolumeWritable(sdCardInfo.mediaStoreVolumeName)) {
                return true
            }
        }
        return hasPersistedSafTreeAccess(savedTreeUri)
    }

    private fun probeMediaStoreVolumeWritable(volumeName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val collection = MediaStore.Audio.Media.getContentUri(volumeName)
            val probeName = ".vr_probe_${System.currentTimeMillis()}.mp3"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, probeName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_MUSIC_VOICE_RECORDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return false
            context.contentResolver.delete(uri, null, null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPersistedSafTreeAccess(treeUriString: String?): Boolean {
        if (treeUriString.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        val hasPerm = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPerm) return false
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun buildBaseRecordingName(customTitle: String? = null, timestampDate: Date = Date()): String {
        val sanitized = customTitle
            ?.trim()
            ?.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "")
            ?.replace(Regex("\\s+"), "_")
            ?.take(48)
            .orEmpty()

        return if (sanitized.isNotEmpty()) {
            sanitized
        } else {
            FormatUtils.formatDefaultRecordingName(timestampDate)
        }
    }

    fun createNewMp3File(customTitle: String? = null, timestampDate: Date = Date()): File {
        val dir = getPublicInternalMusicDir()
        val baseName = buildBaseRecordingName(customTitle, timestampDate)
        var candidate = File(dir, "$baseName.mp3")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$suffix.mp3")
            suffix++
        }
        return candidate
    }

    fun createDirectRecordingTarget(
        customTitle: String? = null,
        storageLocation: StorageLocation,
        sdCardTreeUri: String?,
        timestampDate: Date = Date()
    ): Result<DirectRecordingTarget> {
        val baseName = buildBaseRecordingName(customTitle, timestampDate)

        if (storageLocation == StorageLocation.SD_CARD) {
            val sdCard = detectMountedSdCard()
                ?: return Result.failure(
                    IllegalStateException("SD card is not inserted. Insert an SD card or switch storage location to Internal Storage.")
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !sdCard.mediaStoreVolumeName.isNullOrBlank()) {
                val mediaStoreTarget = createMediaStoreTarget(
                    baseName = baseName,
                    volumeName = sdCard.mediaStoreVolumeName,
                    storageLocation = StorageLocation.SD_CARD,
                    displayRoot = sdCard.directoryPath ?: sdCard.displayName
                )
                if (mediaStoreTarget.isSuccess) {
                    return mediaStoreTarget
                }
            }

            if (hasPersistedSafTreeAccess(sdCardTreeUri)) {
                val treeUri = Uri.parse(sdCardTreeUri!!)
                return createSafTarget(
                    treeUri = treeUri,
                    baseName = baseName,
                    storageLocation = StorageLocation.SD_CARD
                )
            }

            return Result.failure(
                IllegalStateException("SD card write permission is needed. Switch storage location to SD card in More Options.")
            )
        }

        // Internal Storage -> /storage/emulated/0/Music/Voice Recorder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return createMediaStoreTarget(
                baseName = baseName,
                volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                storageLocation = StorageLocation.INTERNAL,
                displayRoot = Environment.getExternalStorageDirectory().absolutePath
            )
        }

        return runCatching {
            val file = createNewMp3File(customTitle = customTitle, timestampDate = timestampDate)
            FileDirectTarget(
                context = context,
                file = file,
                storageLocation = StorageLocation.INTERNAL
            )
        }
    }

    private fun createMediaStoreTarget(
        baseName: String,
        volumeName: String,
        storageLocation: StorageLocation,
        displayRoot: String
    ): Result<DirectRecordingTarget> = runCatching {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(volumeName)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val existingNames = queryMediaStoreNamesInVoiceRecorder(volumeName)
        var candidateName = "$baseName.mp3"
        var suffix = 1
        while (existingNames.contains(candidateName.lowercase(Locale.US))) {
            candidateName = "${baseName}_$suffix.mp3"
            suffix++
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, candidateName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_MUSIC_VOICE_RECORDER)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create recording file in $RELATIVE_MUSIC_VOICE_RECORDER")

        val displayPath = "$displayRoot/$RELATIVE_MUSIC_VOICE_RECORDER/$candidateName"
        MediaStoreDirectTarget(
            context = context,
            uri = itemUri,
            fileName = candidateName,
            storageLocation = storageLocation,
            displayPathHint = displayPath
        )
    }

    private fun queryMediaStoreNamesInVoiceRecorder(volumeName: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        val collection = MediaStore.Audio.Media.getContentUri(volumeName)
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%$VOICE_RECORDER_FOLDER%")
        runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val relPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty().trimEnd('/') else ""
                    if (relPath.equals(RELATIVE_MUSIC_VOICE_RECORDER, ignoreCase = true)) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        if (name.isNotBlank()) {
                            names.add(name.lowercase(Locale.US))
                        }
                    }
                }
            }
        }
        return names
    }

    private fun createSafTarget(
        treeUri: Uri,
        baseName: String,
        storageLocation: StorageLocation
    ): Result<DirectRecordingTarget> = runCatching {
        val folderUri = ensureSafVoiceRecorderFolder(treeUri)
            ?: throw IllegalStateException("Unable to access Music/Voice Recorder folder on SD card")
        val existingNames = listSafChildNames(treeUri, DocumentsContract.getDocumentId(folderUri))
        var candidateName = "$baseName.mp3"
        var suffix = 1
        while (existingNames.contains(candidateName.lowercase(Locale.US))) {
            candidateName = "${baseName}_$suffix.mp3"
            suffix++
        }
        val docUri = DocumentsContract.createDocument(
            context.contentResolver,
            folderUri,
            "audio/mpeg",
            candidateName
        ) ?: throw IllegalStateException("Could not create MP3 file on SD card")

        SafDirectTarget(
            context = context,
            docUri = docUri,
            fileName = candidateName,
            storageLocation = storageLocation
        )
    }

    private fun ensureSafVoiceRecorderFolder(treeUri: Uri): Uri? {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val rootName = querySafDocumentName(rootUri).orEmpty()

        if (rootName.equals(VOICE_RECORDER_FOLDER, ignoreCase = true)) {
            return rootUri
        }

        val musicDocUri = if (rootName.equals(Environment.DIRECTORY_MUSIC, ignoreCase = true)) {
            rootUri
        } else {
            findOrCreateSafSubfolder(treeUri, rootUri, Environment.DIRECTORY_MUSIC) ?: return null
        }

        return findOrCreateSafSubfolder(treeUri, musicDocUri, VOICE_RECORDER_FOLDER)
    }

    private fun findSafVoiceRecorderFolder(treeUri: Uri): Uri? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val rootName = querySafDocumentName(rootUri).orEmpty()

        if (rootName.equals(VOICE_RECORDER_FOLDER, ignoreCase = true)) {
            return rootUri
        }

        val musicDocUri = if (rootName.equals(Environment.DIRECTORY_MUSIC, ignoreCase = true)) {
            rootUri
        } else {
            findSafSubfolder(treeUri, rootDocId, Environment.DIRECTORY_MUSIC) ?: return null
        }

        return findSafSubfolder(treeUri, DocumentsContract.getDocumentId(musicDocUri), VOICE_RECORDER_FOLDER)
    }

    private fun findOrCreateSafSubfolder(treeUri: Uri, parentUri: Uri, folderName: String): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        findSafSubfolder(treeUri, parentDocId, folderName)?.let { return it }
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
            )
        }.getOrNull()
    }

    private fun findSafSubfolder(treeUri: Uri, parentDocId: String, folderName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                        name.equals(folderName, ignoreCase = true)
                    ) {
                        val childId = cursor.getString(idCol)
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun querySafDocumentName(docUri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun listSafChildNames(treeUri: Uri, parentDocId: String): Set<String> {
        val names = mutableSetOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let {
                        names.add(it.lowercase(Locale.US))
                    }
                }
            }
        }
        return names
    }

    suspend fun convertUriToMp3(
        sourceUri: Uri,
        bitrateKbps: Int,
        sampleRateHz: Int,
        channels: Int,
        storageLocation: StorageLocation = StorageLocation.INTERNAL,
        sdCardTreeUri: String? = null
    ): Result<Mp3Recording> = withContext(Dispatchers.IO) {
        val tempInput = File(context.cacheDir, "ffmpeg_in_${System.currentTimeMillis()}")
        val tempOutput = File(context.cacheDir, "ffmpeg_out_${System.currentTimeMillis()}.mp3")
        var target: DirectRecordingTarget? = null
        var outStream: BufferedOutputStream? = null
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempInput.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read selected audio file"))

            val conv = FfmpegMp3Native.convertFileToMp3(
                inputPath = tempInput.absolutePath,
                outputPath = tempOutput.absolutePath,
                bitrateKbps = bitrateKbps,
                targetSampleRate = sampleRateHz,
                targetChannels = channels
            )
            if (conv.isFailure) {
                return@withContext Result.failure(conv.exceptionOrNull() ?: IllegalStateException("Conversion failed"))
            }
            if (!tempOutput.exists() || tempOutput.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Converted MP3 file is empty"))
            }

            val createdTarget = createDirectRecordingTarget(
                customTitle = null,
                storageLocation = storageLocation,
                sdCardTreeUri = sdCardTreeUri,
                timestampDate = Date()
            ).getOrElse { return@withContext Result.failure(it) }
            target = createdTarget

            outStream = createdTarget.openOutputStream()
            tempOutput.inputStream().use { input ->
                input.copyTo(outStream)
            }
            createdTarget.closeStream(outStream)
            outStream = null

            val localMeta = readRecordingMetadata(tempOutput)
            val saved = createdTarget.finalizeSuccess(localMeta.durationMs, tempOutput.length())
                ?: return@withContext Result.failure(IllegalStateException("Failed to save converted MP3"))

            Result.success(
                localMeta.copy(
                    id = saved.contentUri ?: saved.filePath,
                    displayName = saved.fileName.removeSuffix(".mp3"),
                    fileName = saved.fileName,
                    filePath = saved.filePath,
                    sizeBytes = tempOutput.length(),
                    contentUri = saved.contentUri,
                    storageLocation = saved.storageLocation
                )
            )
        } catch (e: Exception) {
            target?.closeStream(outStream)
            target?.discardAndDelete()
            Result.failure(e)
        } finally {
            tempInput.delete()
            tempOutput.delete()
        }
    }

    suspend fun loadRecordings(sdCardTreeUri: String? = null): List<Mp3Recording> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Mp3Recording>()
        val seenKeys = mutableSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreList = loadFromMediaStore()
            mediaStoreList.forEach { rec ->
                val dedupKey = "${rec.storageLocation}:${rec.fileName.lowercase(Locale.US)}:${rec.sizeBytes}"
                if (seenKeys.add(dedupKey)) {
                    results.add(rec)
                }
            }
        } else {
            val publicDir = getPublicInternalMusicDir()
            val files = publicDir.listFiles { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    file.extension.equals("mp3", ignoreCase = true) &&
                    file.length() > 0L
            }.orEmpty()
            files.forEach { file ->
                val rec = readRecordingMetadata(file, StorageLocation.INTERNAL)
                val dedupKey = "${rec.storageLocation}:${rec.fileName.lowercase(Locale.US)}:${rec.sizeBytes}"
                if (seenKeys.add(dedupKey)) {
                    results.add(rec)
                }
            }
        }

        if (hasPersistedSafTreeAccess(sdCardTreeUri)) {
            val safList = loadFromSafTree(Uri.parse(sdCardTreeUri!!))
            safList.forEach { rec ->
                val dedupKey = "${rec.storageLocation}:${rec.fileName.lowercase(Locale.US)}:${rec.sizeBytes}"
                if (seenKeys.add(dedupKey)) {
                    results.add(rec)
                }
            }
        }

        val legacyDir = getLegacyInternalDir()
        if (legacyDir.exists()) {
            val legacyFiles = legacyDir.listFiles { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    file.extension.equals("mp3", ignoreCase = true) &&
                    file.length() > 0L
            }.orEmpty()
            legacyFiles.forEach { file ->
                val rec = readRecordingMetadata(file, StorageLocation.INTERNAL)
                val dedupKey = "LEGACY:${rec.filePath}"
                if (seenKeys.add(dedupKey)) {
                    results.add(rec)
                }
            }
        }

        results.sortedByDescending { it.createdAtMillis }
    }

    private fun loadFromMediaStore(): List<Mp3Recording> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val list = mutableListOf<Mp3Recording>()
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.IS_PENDING,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%$VOICE_RECORDER_FOLDER%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val addCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val volCol = cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
                val pendingCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_PENDING)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val isPending = if (pendingCol >= 0) cursor.getInt(pendingCol) else 0
                    if (isPending != 0) continue

                    val relPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty().trimEnd('/') else ""
                    if (!relPath.equals(RELATIVE_MUSIC_VOICE_RECORDER, ignoreCase = true)) continue

                    val fileName = cursor.getString(nameCol).orEmpty()
                    if (!fileName.endsWith(".mp3", ignoreCase = true)) continue

                    val sizeBytes = cursor.getLong(sizeCol)
                    if (sizeBytes <= 0L) continue

                    val id = cursor.getLong(idCol)
                    val volumeName = if (volCol >= 0) cursor.getString(volCol).orEmpty() else ""
                    val storageLoc = if (volumeName.isBlank() || volumeName.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase = true)) {
                        StorageLocation.INTERNAL
                    } else {
                        StorageLocation.SD_CARD
                    }

                    val itemUri = if (volumeName.isNotBlank()) {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volumeName), id)
                    } else {
                        ContentUris.withAppendedId(collection, id)
                    }

                    var durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (durationMs <= 0L) {
                        durationMs = extractDurationFromUri(itemUri)
                    }

                    val modSec = if (modCol >= 0) cursor.getLong(modCol) else 0L
                    val addSec = if (addCol >= 0) cursor.getLong(addCol) else 0L
                    val createdMs = (if (modSec > 0L) modSec else addSec) * 1000L
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""

                    list.add(
                        Mp3Recording(
                            id = itemUri.toString(),
                            displayName = fileName.removeSuffix(".mp3").removeSuffix(".MP3"),
                            fileName = fileName,
                            filePath = dataPath.ifBlank { itemUri.toString() },
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            createdAtMillis = createdMs,
                            bitrateKbps = null,
                            contentUri = itemUri.toString(),
                            storageLocation = storageLoc
                        )
                    )
                }
            }
        }
        return list
    }

    private fun loadFromSafTree(treeUri: Uri): List<Mp3Recording> {
        val folderUri = findSafVoiceRecorderFolder(treeUri) ?: return emptyList()
        val folderDocId = DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val list = mutableListOf<Mp3Recording>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val fileName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    if (!fileName.endsWith(".mp3", ignoreCase = true)) continue
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    if (sizeBytes <= 0L) continue

                    val childId = cursor.getString(idCol)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val modMs = if (modCol >= 0) cursor.getLong(modCol) else System.currentTimeMillis()
                    val durationMs = extractDurationFromUri(docUri)

                    list.add(
                        Mp3Recording(
                            id = docUri.toString(),
                            displayName = fileName.removeSuffix(".mp3").removeSuffix(".MP3"),
                            fileName = fileName,
                            filePath = docUri.toString(),
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            createdAtMillis = modMs,
                            bitrateKbps = null,
                            contentUri = docUri.toString(),
                            storageLocation = StorageLocation.SD_CARD
                        )
                    )
                }
            }
        }
        return list
    }

    private fun extractDurationFromUri(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readRecordingMetadata(
        file: File,
        storageLocation: StorageLocation = StorageLocation.INTERNAL
    ): Mp3Recording {
        var durationMs = 0L
        var bitrateKbps: Int? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bitrateBitsPerSec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
            if (bitrateBitsPerSec != null && bitrateBitsPerSec > 0) {
                bitrateKbps = bitrateBitsPerSec / 1000
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        return Mp3Recording(
            id = file.absolutePath,
            displayName = file.nameWithoutExtension,
            fileName = file.name,
            filePath = file.absolutePath,
            durationMs = durationMs,
            sizeBytes = file.length(),
            createdAtMillis = file.lastModified(),
            bitrateKbps = bitrateKbps,
            contentUri = null,
            storageLocation = storageLocation
        )
    }

    suspend fun renameRecording(recording: Mp3Recording, newName: String): Result<Mp3Recording> =
        withContext(Dispatchers.IO) {
            val cleaned = newName.trim()
                .replace(Regex("[/\\\\:*?\"<>|]"), "")
                .take(64)
            if (cleaned.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Name cannot be empty"))
            }

            val targetFileName = if (cleaned.endsWith(".mp3", ignoreCase = true)) {
                cleaned
            } else {
                "$cleaned.mp3"
            }

            val uriStr = recording.contentUri
            if (!uriStr.isNullOrBlank()) {
                val uri = Uri.parse(uriStr)
                return@withContext runCatching {
                    if (uri.authority == MediaStore.AUTHORITY) {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, targetFileName)
                        }
                        val updated = context.contentResolver.update(uri, values, null, null)
                        if (updated <= 0) {
                            throw IllegalStateException("Could not rename recording")
                        }
                        recording.copy(
                            displayName = targetFileName.removeSuffix(".mp3").removeSuffix(".MP3"),
                            fileName = targetFileName
                        )
                    } else {
                        val newUri = DocumentsContract.renameDocument(context.contentResolver, uri, targetFileName)
                            ?: throw IllegalStateException("Could not rename recording on SD card")
                        recording.copy(
                            id = newUri.toString(),
                            displayName = targetFileName.removeSuffix(".mp3").removeSuffix(".MP3"),
                            fileName = targetFileName,
                            filePath = newUri.toString(),
                            contentUri = newUri.toString()
                        )
                    }
                }
            }

            val source = File(recording.filePath)
            if (!source.exists()) {
                return@withContext Result.failure(IllegalStateException("Recording file not found"))
            }

            val target = File(source.parentFile, targetFileName)
            if (target.absolutePath != source.absolutePath && target.exists()) {
                return@withContext Result.failure(IllegalStateException("A recording with that name already exists"))
            }

            val renamed = source.renameTo(target)
            if (!renamed) {
                return@withContext Result.failure(IllegalStateException("Could not rename recording"))
            }
            Result.success(readRecordingMetadata(target, recording.storageLocation))
        }

    suspend fun deleteRecording(recording: Mp3Recording): Boolean = withContext(Dispatchers.IO) {
        val uriStr = recording.contentUri
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            return@withContext runCatching {
                if (uri.authority == MediaStore.AUTHORITY) {
                    context.contentResolver.delete(uri, null, null) > 0
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            }.getOrDefault(false)
        }
        val file = File(recording.filePath)
        if (file.exists()) file.delete() else true
    }

    fun buildShareIntent(recording: Mp3Recording): Intent? {
        val uriStr = recording.contentUri
        val uri: Uri = if (!uriStr.isNullOrBlank()) {
            Uri.parse(uriStr)
        } else {
            val file = File(recording.filePath)
            if (!file.exists()) return null
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, recording.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun exportToDeviceRecordings(recording: Mp3Recording): Result<String> =
        withContext(Dispatchers.IO) {
            if (recording.storageLocation == StorageLocation.INTERNAL && !recording.contentUri.isNullOrBlank()) {
                return@withContext Result.success("Saved in Music/Voice Recorder/${recording.fileName}")
            }

            val inputStream = if (!recording.contentUri.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(recording.contentUri))
            } else {
                val src = File(recording.filePath)
                if (src.exists()) src.inputStream() else null
            } ?: return@withContext Result.failure(IllegalStateException("Recording file not found"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, recording.fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_MUSIC_VOICE_RECORDER)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                    ?: return@withContext Result.failure(IllegalStateException("Unable to create MediaStore entry"))

                return@withContext try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        inputStream.use { input -> input.copyTo(out) }
                    } ?: return@withContext Result.failure(IllegalStateException("Failed to open output stream"))

                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    Result.success("Saved to Music/Voice Recorder/${recording.fileName}")
                } catch (e: Exception) {
                    resolver.delete(itemUri, null, null)
                    Result.failure(e)
                }
            } else {
                val exportDir = getPublicInternalMusicDir()
                val dest = File(exportDir, recording.fileName)
                inputStream.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                Result.success("Saved to Music/Voice Recorder/${dest.name}")
            }
        }

    companion object {
        const val VOICE_RECORDER_FOLDER = "Voice Recorder"
        val RELATIVE_MUSIC_VOICE_RECORDER = "${Environment.DIRECTORY_MUSIC}/$VOICE_RECORDER_FOLDER"
    }
}
