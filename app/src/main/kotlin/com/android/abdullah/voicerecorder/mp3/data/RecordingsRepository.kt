package com.android.abdullah.voicerecorder.mp3.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsRepository(private val context: Context) {

    fun getRecordingsDir(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createTempRecordingFile(): File {
        val dir = getRecordingsDir()
        return File(dir, ".recording_active_${System.currentTimeMillis()}.tmp")
    }

    fun createNewMp3File(customTitle: String? = null, timestampDate: Date = Date()): File {
        val dir = getRecordingsDir()
        val sanitized = customTitle
            ?.trim()
            ?.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "")
            ?.replace(Regex("\\s+"), "_")
            ?.take(48)
            .orEmpty()

        val baseName = if (sanitized.isNotEmpty()) {
            sanitized
        } else {
            com.android.abdullah.voicerecorder.mp3.util.FormatUtils.formatDefaultRecordingName(timestampDate)
        }

        var candidate = File(dir, "$baseName.mp3")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$suffix.mp3")
            suffix++
        }
        return candidate
    }

    suspend fun convertUriToMp3(
        sourceUri: Uri,
        bitrateKbps: Int,
        sampleRateHz: Int,
        channels: Int
    ): Result<Mp3Recording> = withContext(Dispatchers.IO) {
        val tempInput = File(context.cacheDir, "ffmpeg_in_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempInput.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read selected audio file"))

            val outFile = createNewMp3File(customTitle = null, timestampDate = Date())
            val conv = com.android.abdullah.voicerecorder.mp3.audio.FfmpegMp3Native.convertFileToMp3(
                inputPath = tempInput.absolutePath,
                outputPath = outFile.absolutePath,
                bitrateKbps = bitrateKbps,
                targetSampleRate = sampleRateHz,
                targetChannels = channels
            )
            if (conv.isFailure) {
                outFile.delete()
                return@withContext Result.failure(conv.exceptionOrNull() ?: IllegalStateException("Conversion failed"))
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.delete()
                return@withContext Result.failure(IllegalStateException("Converted MP3 file is empty"))
            }
            Result.success(readRecordingMetadata(outFile))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempInput.delete()
        }
    }

    suspend fun loadRecordings(): List<Mp3Recording> = withContext(Dispatchers.IO) {
        val dir = getRecordingsDir()
        val files = dir.listFiles { file ->
            file.isFile &&
                !file.name.startsWith(".") &&
                file.extension.equals("mp3", ignoreCase = true) &&
                file.length() > 0L
        } ?: return@withContext emptyList()

        files.map { file -> readRecordingMetadata(file) }
            .sortedByDescending { it.createdAtMillis }
    }

    private fun readRecordingMetadata(file: File): Mp3Recording {
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
            // Fallback to 0ms if file metadata is unreadable
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        return Mp3Recording(
            id = file.absolutePath,
            displayName = file.nameWithoutExtension,
            fileName = file.name,
            filePath = file.absolutePath,
            durationMs = durationMs,
            sizeBytes = file.length(),
            createdAtMillis = file.lastModified(),
            bitrateKbps = bitrateKbps
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

            val source = File(recording.filePath)
            if (!source.exists()) {
                return@withContext Result.failure(IllegalStateException("Recording file not found"))
            }

            val targetFileName = if (cleaned.endsWith(".mp3", ignoreCase = true)) {
                cleaned
            } else {
                "$cleaned.mp3"
            }
            val target = File(source.parentFile, targetFileName)
            if (target.absolutePath != source.absolutePath && target.exists()) {
                return@withContext Result.failure(IllegalStateException("A recording with that name already exists"))
            }

            val renamed = source.renameTo(target)
            if (!renamed) {
                return@withContext Result.failure(IllegalStateException("Could not rename recording"))
            }
            Result.success(readRecordingMetadata(target))
        }

    suspend fun deleteRecording(recording: Mp3Recording): Boolean = withContext(Dispatchers.IO) {
        val file = File(recording.filePath)
        if (file.exists()) file.delete() else true
    }

    fun buildShareIntent(recording: Mp3Recording): Intent? {
        val file = File(recording.filePath)
        if (!file.exists()) return null
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, recording.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun exportToDeviceRecordings(recording: Mp3Recording): Result<String> =
        withContext(Dispatchers.IO) {
            val source = File(recording.filePath)
            if (!source.exists()) {
                return@withContext Result.failure(IllegalStateException("Recording file not found"))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, recording.fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/Voice Recorder"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                    ?: return@withContext Result.failure(IllegalStateException("Unable to create MediaStore entry"))

                return@withContext try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        source.inputStream().use { input ->
                            input.copyTo(out)
                        }
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
                // App external music directory fallback on API 24-28 (requires zero broad storage permissions)
                val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: return@withContext Result.failure(IllegalStateException("External storage unavailable"))
                if (!exportDir.exists()) exportDir.mkdirs()
                val dest = File(exportDir, recording.fileName)
                source.copyTo(dest, overwrite = true)
                Result.success("Saved to ${dest.name} in app Music folder")
            }
        }
}
