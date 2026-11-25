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
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.audio.Mp3AudioPlayer
import com.android.abdullah.voicerecorder.mp3.data.GitHubUpdateRepository
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.data.ReleaseCheckResult
import com.android.abdullah.voicerecorder.mp3.data.SettingsRepository
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.PlaybackState
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.model.SortOrder
import com.android.abdullah.voicerecorder.mp3.model.UpdateUiState
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.service.Mp3RecordingService
import com.android.abdullah.voicerecorder.mp3.service.RecordingEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class MainDestination {
    RECORD,
    LIBRARY
}

class VoiceRecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val recordingsRepository = RecordingsRepository(context)
    private val settingsRepository = SettingsRepository(context)
    private val updateRepository = GitHubUpdateRepository(context)
    private val audioPlayer = Mp3AudioPlayer()

    private val _recordingService = MutableStateFlow<Mp3RecordingService?>(null)
    private var serviceStateJob: Job? = null
    private var serviceEventsJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
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

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _showUpdateScreen = MutableStateFlow(false)
    val showUpdateScreen: StateFlow<Boolean> = _showUpdateScreen.asStateFlow()

    private val _installIntentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val installIntentEvents: SharedFlow<Intent> = _installIntentEvents.asSharedFlow()

    private val _unknownSourceIntentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val unknownSourceIntentEvents: SharedFlow<Intent> = _unknownSourceIntentEvents.asSharedFlow()

    val configuredRepo: StateFlow<String> = settingsRepository.githubRepoFlow
        .map { saved ->
            val bundled = context.getString(R.string.github_repo).trim()
            bundled.ifBlank { saved.trim() }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            context.getString(R.string.github_repo).trim()
        )

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
        checkForUpdates(autoOpenIfAvailable = true)
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

    fun openUpdateScreen() {
        _showUpdateScreen.value = true
        val state = _updateState.value
        if (state is UpdateUiState.Idle ||
            state is UpdateUiState.UpToDate ||
            state is UpdateUiState.RepoNotConfigured ||
            (state is UpdateUiState.Error && state.release == null)
        ) {
            checkForUpdates(autoOpenIfAvailable = false)
        }
    }

    fun dismissUpdateScreen() {
        _showUpdateScreen.value = false
    }

    fun saveRepoAndCheck(rawRepo: String) {
        val slug = GitHubUpdateRepository.normalizeRepoSlug(rawRepo)
        if (slug == null) {
            _uiMessages.tryEmit("Enter a valid repository like owner/repo")
            return
        }
        viewModelScope.launch {
            settingsRepository.updateGithubRepo(slug)
            checkForUpdates(autoOpenIfAvailable = false, explicitRepo = slug)
        }
    }

    fun checkForUpdates(autoOpenIfAvailable: Boolean = false, explicitRepo: String? = null) {
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val installed = updateRepository.getInstalledVersion()
            val bundledRepo = context.getString(R.string.github_repo).trim()
            val savedRepo = settingsRepository.githubRepoFlow.first().trim()
            val repo = explicitRepo?.trim()?.ifBlank { null }
                ?: bundledRepo.ifBlank { savedRepo }

            if (repo.isBlank()) {
                _updateState.value = UpdateUiState.RepoNotConfigured(installed.versionName)
                return@launch
            }

            _updateState.value = UpdateUiState.Checking(installed.versionName)
            runCatching {
                updateRepository.checkLatestRelease(repo)
            }.onSuccess { result ->
                when (result) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        _updateState.value = UpdateUiState.Available(
                            currentVersion = installed.versionName,
                            release = result.release
                        )
                        if (autoOpenIfAvailable) {
                            _showUpdateScreen.value = true
                        }
                    }
                    is ReleaseCheckResult.UpToDate -> {
                        _updateState.value = UpdateUiState.UpToDate(
                            currentVersion = installed.versionName,
                            latestVersion = result.latestVersion
                        )
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _updateState.value = UpdateUiState.Error(
                    currentVersion = installed.versionName,
                    message = err.message ?: "Unable to check GitHub Releases."
                )
            }
        }
    }

    fun downloadUpdate() {
        val currentState = _updateState.value
        val release = when (currentState) {
            is UpdateUiState.Available -> currentState.release
            is UpdateUiState.Error -> currentState.release
            else -> null
        } ?: return

        val installed = updateRepository.getInstalledVersion()
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(
                currentVersion = installed.versionName,
                release = release,
                downloadedBytes = 0L,
                totalBytes = release.apkSizeBytes,
                progress = if (release.apkSizeBytes > 0L) 0f else null
            )

            runCatching {
                updateRepository.downloadApk(
                    downloadUrl = release.apkDownloadUrl,
                    assetFileName = release.apkAssetName,
                    expectedSizeBytes = release.apkSizeBytes
                ) { downloaded, total ->
                    val progress = if (total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _updateState.value = UpdateUiState.Downloading(
                        currentVersion = installed.versionName,
                        release = release,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        progress = progress
                    )
                }
            }.onSuccess { apkFile ->
                _updateState.value = UpdateUiState.ReadyToInstall(
                    currentVersion = installed.versionName,
                    release = release,
                    apkFilePath = apkFile.absolutePath,
                    needsUnknownSourcePermission = false
                )
            }.onFailure { err ->
                if (err is CancellationException) {
                    _updateState.value = UpdateUiState.Available(
                        currentVersion = installed.versionName,
                        release = release
                    )
                } else {
                    _updateState.value = UpdateUiState.Error(
                        currentVersion = installed.versionName,
                        message = err.message ?: "Failed to download update APK.",
                        release = release
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        updateDownloadJob?.cancel()
    }

    fun onInstallNowClicked() {
        val state = _updateState.value as? UpdateUiState.ReadyToInstall ?: return
        val apkFile = File(state.apkFilePath)
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            _updateState.value = UpdateUiState.Available(
                currentVersion = state.currentVersion,
                release = state.release
            )
            _uiMessages.tryEmit("Downloaded APK was removed. Please download again.")
            return
        }

        if (!updateRepository.canRequestPackageInstalls()) {
            _updateState.value = state.copy(needsUnknownSourcePermission = true)
            return
        }

        _updateState.value = state.copy(needsUnknownSourcePermission = false)
        _installIntentEvents.tryEmit(updateRepository.buildInstallApkIntent(apkFile))
    }

    fun onRequestUnknownSourcePermission() {
        _unknownSourceIntentEvents.tryEmit(updateRepository.buildUnknownSourcesSettingsIntent())
    }

    fun refreshInstallPermissionState() {
        val state = _updateState.value as? UpdateUiState.ReadyToInstall ?: return
        if (state.needsUnknownSourcePermission && updateRepository.canRequestPackageInstalls()) {
            _updateState.value = state.copy(needsUnknownSourcePermission = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
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
