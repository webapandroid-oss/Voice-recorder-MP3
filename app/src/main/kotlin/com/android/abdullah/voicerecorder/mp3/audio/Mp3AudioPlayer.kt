package com.android.abdullah.voicerecorder.mp3.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Mp3AudioPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun togglePlayPause(recording: Mp3Recording) {
        val current = _playbackState.value
        if (current.activeRecordingId == recording.id && mediaPlayer != null) {
            if (current.isPlaying) {
                pause()
            } else {
                resume()
            }
        } else {
            playNew(recording)
        }
    }

    private fun playNew(recording: Mp3Recording) {
        stopInternal()
        try {
            val player = MediaPlayer().apply {
                val uriStr = recording.contentUri
                if (!uriStr.isNullOrBlank()) {
                    setDataSource(context.applicationContext, Uri.parse(uriStr))
                } else {
                    setDataSource(recording.filePath)
                }
                prepare()
                val speed = _playbackState.value.speed
                if (speed != 1.0f) {
                    playbackParams = PlaybackParams().setSpeed(speed)
                }
                setOnCompletionListener {
                    progressJob?.cancel()
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            positionMs = 0L
                        )
                    }
                    try {
                        seekTo(0)
                    } catch (_: Exception) {
                    }
                }
                start()
            }
            mediaPlayer = player
            val duration = player.duration.toLong().coerceAtLeast(recording.durationMs)
            _playbackState.update {
                it.copy(
                    activeRecordingId = recording.id,
                    isPlaying = true,
                    positionMs = 0L,
                    durationMs = duration
                )
            }
            startProgressLoop()
        } catch (_: Exception) {
            stopInternal()
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
            progressJob?.cancel()
            _playbackState.update {
                it.copy(
                    isPlaying = false,
                    positionMs = player.currentPosition.toLong()
                )
            }
        }
    }

    fun resume() {
        mediaPlayer?.let { player ->
            val speed = _playbackState.value.speed
            try {
                player.playbackParams = PlaybackParams().setSpeed(speed)
            } catch (_: Exception) {
            }
            player.start()
            _playbackState.update { it.copy(isPlaying = true) }
            startProgressLoop()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            val clamped = positionMs.coerceIn(0L, player.duration.toLong().coerceAtLeast(0L))
            player.seekTo(clamped.toInt())
            _playbackState.update { it.copy(positionMs = clamped) }
        }
    }

    fun skipBy(deltaMs: Long) {
        val current = _playbackState.value
        if (mediaPlayer != null) {
            seekTo(current.positionMs + deltaMs)
        }
    }

    fun cycleSpeed() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val currentSpeed = _playbackState.value.speed
        val nextIndex = (speeds.indexOfFirst { it == currentSpeed } + 1) % speeds.size
        val nextSpeed = speeds[nextIndex]
        _playbackState.update { it.copy(speed = nextSpeed) }
        mediaPlayer?.let { player ->
            try {
                val wasPlaying = player.isPlaying
                player.playbackParams = PlaybackParams().setSpeed(nextSpeed)
                if (!wasPlaying && player.isPlaying) {
                    player.pause()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopIfPlaying(recordingId: String? = null) {
        if (recordingId == null || _playbackState.value.activeRecordingId == recordingId) {
            stopInternal()
            _playbackState.value = PlaybackState(speed = _playbackState.value.speed)
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val player = mediaPlayer ?: break
                if (player.isPlaying) {
                    _playbackState.update {
                        it.copy(
                            positionMs = player.currentPosition.toLong(),
                            durationMs = player.duration.toLong().coerceAtLeast(it.durationMs)
                        )
                    }
                }
                delay(50L)
            }
        }
    }

    private fun stopInternal() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
    }

    fun release() {
        stopInternal()
    }
}
