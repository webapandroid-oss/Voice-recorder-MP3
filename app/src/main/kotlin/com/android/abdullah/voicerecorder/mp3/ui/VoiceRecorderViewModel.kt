package com.android.abdullah.voicerecorder.mp3.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.abdullah.voicerecorder.mp3.audio.Mp3AudioPlayer
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.data.SettingsRepository
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.PlaybackState
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.model.SortOrder
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.service.Mp3RecordingService
import com.android.abdullah.voicerecorder.mp3.service.RecordingEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainDestination {
    RECORD,
    LIBRARY
}

class VoiceRecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val recordingsRepository = RecordingsRepository(context)
    private val settingsRepository = SettingsRepository(context)
    private val audioPlayer = Mp3AudioPlayer()

    private val _recordingService = MutableStateFlow<Mp3RecordingService?>(null)
    private var serviceStateJob: Job? = null
    private var serviceEventsJob: Job? = null
    private var isBound = false

    private val _liveRecordingState = MutableStateFlow(LiveRecordingState())
    val liveRecordingState: StateFlow<LiveRecordingState> = _liveRecordingState.asStateFlow()

    val qualityConfig: StateFlow<RecordingQualityConfig> = settingsRepository.qualityConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingQualityConfig())

    val sortOrder: StateFlow<SortOrder> = settingsRepository.sortOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortOrder.NEWEST)

    private val _rawRecordings = MutableStateFlow<List<Mp3Recording>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredRecordings: StateFlow<List<Mp3Recording>> = combine(
        _rawRecordings,
        _searchQuery,
        sortOrder
    ) { list, query, sort ->
        val trimmed = query.trim()
        val searched = if (trimmed.isEmpty()) {
            list
        } else {
            list.filter {
                it.displayName.contains(trimmed, ignoreCase = true) ||
                    it.fileName.contains(trimmed, ignoreCase = true)
            }
        }
        when (sort) {
            SortOrder.NEWEST -> searched.sortedByDescending { it.createdAtMillis }
            SortOrder.OLDEST -> searched.sortedBy { it.createdAtMillis }
            SortOrder.LONGEST -> searched.sortedByDescending { it.durationMs }
            SortOrder.LARGEST -> searched.sortedByDescending { it.sizeBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackState: StateFlow<PlaybackState> = audioPlayer.playbackState

    private val _customDraftTitle = MutableStateFlow("")
    val customDraftTitle: StateFlow<String> = _customDraftTitle.asStateFlow()

    private val _isConvertingMp3 = MutableStateFlow(false)
    val isConvertingMp3: StateFlow<Boolean> = _isConvertingMp3.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? Mp3RecordingService.LocalBinder ?: return
            val svc = binder.getService()
            _recordingService.value = svc
            observeService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _recordingService.value = null
            serviceStateJob?.cancel()
            serviceEventsJob?.cancel()
        }
    }

    init {
        refreshRecordings()
        bindRecordingService()
    }

    private fun bindRecordingService() {
        if (!isBound) {
            val intent = Intent(context, Mp3RecordingService::class.java)
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeService(service: Mp3RecordingService) {
        serviceStateJob?.cancel()
        serviceEventsJob?.cancel()

        serviceStateJob = viewModelScope.launch {
            service.state.collect { state ->
                _liveRecordingState.value = state
            }
        }

        serviceEventsJob = viewModelScope.launch {
            service.events.collect { event ->
                when (event) {
                    is RecordingEvent.Saved -> {
                        _customDraftTitle.value = ""
                        refreshRecordings()
                        _uiMessages.tryEmit("Saved ${event.file.name}")
                    }
                    is RecordingEvent.Discarded -> {
                        _customDraftTitle.value = ""
                        _uiMessages.tryEmit("Recording discarded")
                    }
                    is RecordingEvent.Error -> {
                        _uiMessages.tryEmit(event.message)
                    }
                }
            }
        }
    }

    fun updateDraftTitle(title: String) {
        _customDraftTitle.value = title
    }

    fun startRecording() {
        // Stop playback if any MP3 is playing
        audioPlayer.stopIfPlaying()

        val intent = Intent(context, Mp3RecordingService::class.java)
        context.startService(intent)

        val svc = _recordingService.value
        if (svc != null) {
            svc.startRecording(
                config = qualityConfig.value,
                customTitle = _customDraftTitle.value.takeIf { it.isNotBlank() }
            )
        } else {
            bindRecordingService()
            viewModelScope.launch {
                _recordingService.collect { boundSvc ->
                    if (boundSvc != null && boundSvc.state.value.status == RecorderStatus.IDLE) {
                        boundSvc.startRecording(
                            config = qualityConfig.value,
                            customTitle = _customDraftTitle.value.takeIf { it.isNotBlank() }
                        )
                        return@collect
                    }
                }
            }
        }
    }

    fun pauseRecording() {
        _recordingService.value?.pauseRecording()
    }

    fun resumeRecording() {
        audioPlayer.stopIfPlaying()
        _recordingService.value?.resumeRecording()
    }

    fun stopAndSaveRecording() {
        _recordingService.value?.stopAndSaveRecording()
    }

    fun discardRecording() {
        _recordingService.value?.discardRecording()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortOrder(order: SortOrder) {
        viewModelScope.launch {
            settingsRepository.updateSortOrder(order)
        }
    }

    fun updateBitrate(bitrate: Mp3Bitrate) {
        viewModelScope.launch {
            settingsRepository.updateBitrate(bitrate)
        }
    }

    fun updateSampleRate(sampleRate: Mp3SampleRate) {
        viewModelScope.launch {
            settingsRepository.updateSampleRate(sampleRate)
        }
    }

    fun updateChannelMode(channelMode: Mp3ChannelMode) {
        viewModelScope.launch {
            settingsRepository.updateChannelMode(channelMode)
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            _rawRecordings.value = recordingsRepository.loadRecordings()
        }
    }

    fun togglePlayback(recording: Mp3Recording) {
        audioPlayer.togglePlayPause(recording)
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun skipBy(deltaMs: Long) {
        audioPlayer.skipBy(deltaMs)
    }

    fun cyclePlaybackSpeed() {
        audioPlayer.cycleSpeed()
    }

    fun renameRecording(recording: Mp3Recording, newName: String) {
        viewModelScope.launch {
            val wasPlaying = playbackState.value.activeRecordingId == recording.id
            if (wasPlaying) {
                audioPlayer.stopIfPlaying(recording.id)
            }
            recordingsRepository.renameRecording(recording, newName)
                .onSuccess { updated ->
                    refreshRecordings()
                    _uiMessages.tryEmit("Renamed to ${updated.fileName}")
                }
                .onFailure { err ->
                    _uiMessages.tryEmit(err.message ?: "Rename failed")
                }
        }
    }

    fun deleteRecording(recording: Mp3Recording) {
        viewModelScope.launch {
            audioPlayer.stopIfPlaying(recording.id)
            val deleted = recordingsRepository.deleteRecording(recording)
            if (deleted) {
                refreshRecordings()
                _uiMessages.tryEmit("Deleted ${recording.fileName}")
            } else {
                _uiMessages.tryEmit("Could not delete file")
            }
        }
    }

    fun exportToDevice(recording: Mp3Recording) {
        viewModelScope.launch {
            recordingsRepository.exportToDeviceRecordings(recording)
                .onSuccess { msg -> _uiMessages.tryEmit(msg) }
                .onFailure { err -> _uiMessages.tryEmit(err.message ?: "Export failed") }
        }
    }

    fun buildShareIntent(recording: Mp3Recording): Intent? =
        recordingsRepository.buildShareIntent(recording)

    fun convertAudioUriToMp3(uri: Uri) {
        if (_isConvertingMp3.value) return
        viewModelScope.launch {
            _isConvertingMp3.value = true
            val cfg = qualityConfig.value
            recordingsRepository.convertUriToMp3(
                sourceUri = uri,
                bitrateKbps = cfg.bitrate.kbps,
                sampleRateHz = cfg.sampleRate.hz,
                channels = cfg.channelMode.channels
            ).onSuccess { saved ->
                refreshRecordings()
                _uiMessages.tryEmit("Converted to ${saved.fileName}")
            }.onFailure { err ->
                _uiMessages.tryEmit(err.message ?: "MP3 conversion failed")
            }
            _isConvertingMp3.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            isBound = false
        }
    }
}
