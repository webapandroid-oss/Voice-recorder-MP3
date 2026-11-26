package com.android.abdullah.voicerecorder.mp3.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.android.abdullah.voicerecorder.mp3.MainActivity
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.audio.Mp3Encoder
import com.android.abdullah.voicerecorder.mp3.data.DirectRecordingTarget
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.data.SavedRecordingResult
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.util.Date
import kotlin.math.log10
import kotlin.math.sqrt

data class LiveRecordingState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0L,
    val encodedBytes: Long = 0L,
    val currentLevelDb: Float = -60f,
    val amplitudes: List<Float> = emptyList(),
    val activeFileName: String? = null
)

sealed interface RecordingEvent {
    data class Saved(val result: SavedRecordingResult) : RecordingEvent
    data object Discarded : RecordingEvent
    data class Error(val message: String) : RecordingEvent
}

class Mp3RecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): Mp3RecordingService = this@Mp3RecordingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LiveRecordingState())
    val state: StateFlow<LiveRecordingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecordingEvent> = _events.asSharedFlow()

    private lateinit var repository: RecordingsRepository

    @Volatile
    private var isCapturing = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var shouldDiscard = false

    private var recordingJob: Job? = null
    private var timerJob: Job? = null
    private var currentTarget: DirectRecordingTarget? = null

    private var accumulatedElapsedMs = 0L
    private var lastSegmentStartClockMs = 0L

    override fun onCreate() {
        super.onCreate()
        repository = RecordingsRepository(applicationContext)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopAndSaveRecording()
            ACTION_DISCARD -> discardRecording()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        config: RecordingQualityConfig,
        customTitle: String?,
        storageLocation: StorageLocation = StorageLocation.INTERNAL,
        sdCardTreeUri: String? = null
    ) {
        if (_state.value.status != RecorderStatus.IDLE) return

        val cleanedCustomTitle = customTitle?.trim()?.takeIf { it.isNotEmpty() }
        val targetResult = repository.createDirectRecordingTarget(
            customTitle = cleanedCustomTitle,
            storageLocation = storageLocation,
            sdCardTreeUri = sdCardTreeUri,
            timestampDate = Date()
        )
        val target = targetResult.getOrElse { err ->
            _events.tryEmit(RecordingEvent.Error(err.message ?: "Could not create recording file"))
            return
        }

        currentTarget = target
        val activeName = target.fileName
        accumulatedElapsedMs = 0L
        lastSegmentStartClockMs = SystemClock.elapsedRealtime()
        isCapturing = true
        isPaused = false
        shouldDiscard = false

        _state.value = LiveRecordingState(
            status = RecorderStatus.RECORDING,
            elapsedMs = 0L,
            encodedBytes = 0L,
            currentLevelDb = -60f,
            amplitudes = emptyList(),
            activeFileName = activeName
        )

        startForegroundCompat(buildNotification(paused = false, elapsedMs = 0L, fileName = activeName))
        startTimerLoop()

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            runCaptureAndMp3Encode(target, config, discardOnFinish = { shouldDiscard })
        }
    }

    fun pauseRecording() {
        if (_state.value.status != RecorderStatus.RECORDING) return
        isPaused = true
        accumulatedElapsedMs += SystemClock.elapsedRealtime() - lastSegmentStartClockMs
        _state.update {
            it.copy(
                status = RecorderStatus.PAUSED,
                elapsedMs = accumulatedElapsedMs,
                currentLevelDb = -60f
            )
        }
        updateNotification(paused = true)
    }

    fun resumeRecording() {
        if (_state.value.status != RecorderStatus.PAUSED) return
        isPaused = false
        lastSegmentStartClockMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = RecorderStatus.RECORDING) }
        updateNotification(paused = false)
    }

    fun stopAndSaveRecording() {
        val currentStatus = _state.value.status
        if (currentStatus != RecorderStatus.RECORDING && currentStatus != RecorderStatus.PAUSED) return

        shouldDiscard = false
        if (currentStatus == RecorderStatus.RECORDING) {
            accumulatedElapsedMs += SystemClock.elapsedRealtime() - lastSegmentStartClockMs
        }
        isCapturing = false
        isPaused = false
        timerJob?.cancel()
        _state.update { it.copy(status = RecorderStatus.FINALIZING, elapsedMs = accumulatedElapsedMs) }
    }

    fun discardRecording() {
        val currentStatus = _state.value.status
        if (currentStatus != RecorderStatus.RECORDING && currentStatus != RecorderStatus.PAUSED) return

        shouldDiscard = true
        isCapturing = false
        isPaused = false
        timerJob?.cancel()
        _state.update { it.copy(status = RecorderStatus.FINALIZING) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runCaptureAndMp3Encode(
        target: DirectRecordingTarget,
        config: RecordingQualityConfig,
        discardOnFinish: () -> Boolean
    ) {
        val sampleRate = config.sampleRate.hz
        val channels = config.channelMode.channels
        val channelConfig = if (channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            finishWithError("Device microphone does not support ${sampleRate}Hz ${config.channelMode.label}", target)
            return
        }

        val bufferBytes = (minBufBytes * 2).coerceAtLeast(4096)
        val shortsPerChunk = (bufferBytes / 2).coerceAtLeast(1152 * channels)
        val pcmBuffer = ShortArray(shortsPerChunk)
        val mp3Buffer = ByteArray((7200 + shortsPerChunk * 1.25).toInt())

        var audioRecord: AudioRecord? = null
        var encoder: Mp3Encoder? = null
        var outputStream: BufferedOutputStream? = null
        var totalBytesWritten = 0L
        var lastUiUpdateMs = 0L

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                finishWithError("Could not initialize microphone capture", target)
                return
            }

            encoder = Mp3Encoder(
                sampleRateHz = sampleRate,
                channels = channels,
                bitrateKbps = config.bitrate.kbps,
                quality = 4
            )
            outputStream = target.openOutputStream()

            audioRecord.startRecording()

            while (isCapturing) {
                val readCount = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (readCount > 0) {
                    if (isPaused) {
                        continue
                    }

                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val s = pcmBuffer[i].toDouble()
                        sumSquares += s * s
                    }
                    val rms = sqrt(sumSquares / readCount)
                    val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                    val db = if (normalizedRms > 0.0001f) {
                        (20f * log10(normalizedRms)).coerceIn(-60f, 0f)
                    } else {
                        -60f
                    }
                    val visualAmp = ((db + 52f) / 52f).coerceIn(0.03f, 1f)

                    val encodedCount = encoder.encodePcm16(pcmBuffer, readCount, mp3Buffer)
                    if (encodedCount > 0) {
                        outputStream.write(mp3Buffer, 0, encodedCount)
                        totalBytesWritten += encodedCount
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdateMs >= 45L) {
                        lastUiUpdateMs = now
                        _state.update { prev ->
                            val nextAmps = (prev.amplitudes + visualAmp).takeLast(64)
                            prev.copy(
                                encodedBytes = totalBytesWritten,
                                currentLevelDb = db,
                                amplitudes = nextAmps
                            )
                        }
                    }
                }
            }

            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }

            if (!discardOnFinish()) {
                val flushed = encoder.flush(mp3Buffer)
                if (flushed > 0) {
                    outputStream.write(mp3Buffer, 0, flushed)
                    totalBytesWritten += flushed
                }
                outputStream.flush()
            }
        } catch (e: Exception) {
            target.closeStream(outputStream)
            outputStream = null
            try {
                encoder?.close()
            } catch (_: Exception) {
            }
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            finishWithError(e.message ?: "MP3 encoding failed", target)
            return
        } finally {
            target.closeStream(outputStream)
            try {
                encoder?.close()
            } catch (_: Exception) {
            }
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
        }

        val discarded = discardOnFinish()
        if (discarded || totalBytesWritten <= 0L) {
            target.discardAndDelete()
            _events.tryEmit(RecordingEvent.Discarded)
        } else {
            val saved = target.finalizeSuccess(accumulatedElapsedMs, totalBytesWritten)
            if (saved != null) {
                _events.tryEmit(RecordingEvent.Saved(saved))
            } else {
                target.discardAndDelete()
                _events.tryEmit(RecordingEvent.Error("Could not save MP3 recording"))
            }
        }

        resetToIdleAndStopForeground()
    }

    private fun finishWithError(message: String, target: DirectRecordingTarget) {
        target.discardAndDelete()
        _events.tryEmit(RecordingEvent.Error(message))
        resetToIdleAndStopForeground()
    }

    private fun resetToIdleAndStopForeground() {
        shouldDiscard = false
        currentTarget = null
        timerJob?.cancel()
        _state.value = LiveRecordingState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val current = _state.value
                if (current.status == RecorderStatus.RECORDING) {
                    val totalMs = accumulatedElapsedMs + (SystemClock.elapsedRealtime() - lastSegmentStartClockMs)
                    _state.update { it.copy(elapsedMs = totalMs) }
                    if ((totalMs / 1000L) != (current.elapsedMs / 1000L)) {
                        updateNotification(paused = false, elapsedMsOverride = totalMs)
                    }
                }
                delay(100L)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(paused: Boolean, elapsedMsOverride: Long? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = _state.value
        val notification = buildNotification(
            paused = paused,
            elapsedMs = elapsedMsOverride ?: current.elapsedMs,
            fileName = current.activeFileName ?: "Recording.mp3"
        )
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(paused: Boolean, elapsedMs: Long, fileName: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleActionIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, Mp3RecordingService::class.java).apply {
                action = if (paused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopActionIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, Mp3RecordingService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (paused) {
            getString(R.string.notification_paused_title)
        } else {
            getString(R.string.notification_recording_title)
        }
        val formattedTime = FormatUtils.formatDuration(elapsedMs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("$formattedTime • $fileName")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                toggleActionIntent
            )
            .addAction(
                0,
                "Save MP3",
                stopActionIntent
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "mp3_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PAUSE = "com.android.abdullah.voicerecorder.mp3.ACTION_PAUSE"
        const val ACTION_RESUME = "com.android.abdullah.voicerecorder.mp3.ACTION_RESUME"
        const val ACTION_STOP = "com.android.abdullah.voicerecorder.mp3.ACTION_STOP"
        const val ACTION_DISCARD = "com.android.abdullah.voicerecorder.mp3.ACTION_DISCARD"
    }
}
