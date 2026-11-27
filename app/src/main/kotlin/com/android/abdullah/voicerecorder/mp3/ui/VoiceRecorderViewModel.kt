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
import com.android.abdullah.voicerecorder.mp3.data.GitHubUpdateRepository
import com.android.abdullah.voicerecorder.mp3.data.InstallSessionEvent
import com.android.abdullah.voicerecorder.mp3.data.InstallSessionEventBus
import com.android.abdullah.voicerecorder.mp3.data.RecordingsRepository
import com.android.abdullah.voicerecorder.mp3.data.ReleaseCheckResult
import com.android.abdullah.voicerecorder.mp3.data.SdCardInfo
import com.android.abdullah.voicerecorder.mp3.data.SettingsRepository
import com.android.abdullah.voicerecorder.mp3.model.AppThemeMode
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.PlaybackState
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.model.SortOrder
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.model.UpdateUiState
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.service.Mp3RecordingService
import com.android.abdullah.voicerecorder.mp3.service.RecordingEvent
import com.android.abdullah.voicerecorder.mp3.service.UpdateCheckEventBus
import com.android.abdullah.voicerecorder.mp3.service.UpdateCheckJobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    private val audioPlayer = Mp3AudioPlayer(context)

    private val _recordingService = MutableStateFlow<Mp3RecordingService?>(null)
    private var serviceStateJob: Job? = null
    private var serviceEventsJob: Job? = null
    private var updateCheckJob: Job? = null
    private var periodicUpdateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateInstallJob: Job? = null
    private var isBound = false
    private var pendingInstallAfterPermission = false
    private var lastUpdateCheckAtMs = 0L
    private var lastAutoOpenedTag: String? = null
    private var hasPromptedSdCardThisSession = false

    private val _liveRecordingState = MutableStateFlow(LiveRecordingState())
    val liveRecordingState: StateFlow<LiveRecordingState> = _liveRecordingState.asStateFlow()

    val qualityConfig: StateFlow<RecordingQualityConfig> = settingsRepository.qualityConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingQualityConfig())

    val sortOrder: StateFlow<SortOrder> = settingsRepository.sortOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortOrder.NEWEST)

    val storageLocation: StateFlow<StorageLocation> = settingsRepository.storageLocationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageLocation.INTERNAL)

    private val _appTheme = MutableStateFlow(AppThemeMode.DARK)
    val appTheme: StateFlow<AppThemeMode> = _appTheme.asStateFlow()
    private var hasUserChangedTheme = false

    val sdCardTreeUri: StateFlow<String?> = settingsRepository.sdCardTreeUriFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _sdCardInfo = MutableStateFlow<SdCardInfo?>(null)
    val sdCardInfo: StateFlow<SdCardInfo?> = _sdCardInfo.asStateFlow()

    private val _showSdCardPrompt = MutableStateFlow(false)
    val showSdCardPrompt: StateFlow<Boolean> = _showSdCardPrompt.asStateFlow()

    private val _sdCardSafPickerEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val sdCardSafPickerEvents: SharedFlow<Intent> = _sdCardSafPickerEvents.asSharedFlow()

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

    private val _showSettingsScreen = MutableStateFlow(false)
    val showSettingsScreen: StateFlow<Boolean> = _showSettingsScreen.asStateFlow()

    private val _installIntentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val installIntentEvents: SharedFlow<Intent> = _installIntentEvents.asSharedFlow()

    private val _unknownSourceIntentEvents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val unknownSourceIntentEvents: SharedFlow<Intent> = _unknownSourceIntentEvents.asSharedFlow()

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
        viewModelScope.launch {
            settingsRepository.appThemeFlow.collect { saved ->
                if (!hasUserChangedTheme) {
                    _appTheme.value = saved
                }
            }
        }
        refreshSdCardStatus(promptIfNeeded = true)
        refreshRecordings()
        bindRecordingService()
        observeInstallSessionEvents()
        observeBackgroundUpdateBus()
        UpdateCheckJobService.schedulePeriodicChecks(context)
        checkForUpdates(autoOpenIfAvailable = true, silent = true)
        startPeriodicBackgroundChecks()
    }

    private fun observeBackgroundUpdateBus() {
        viewModelScope.launch {
            UpdateCheckEventBus.updates.collect { release ->
                updateCheckJob?.cancel()
                val installed = updateRepository.getInstalledVersion()
                applyUpdateAvailable(
                    installedVersion = installed.versionName,
                    release = release,
                    autoOpenIfAvailable = true
                )
            }
        }
    }

    private fun startPeriodicBackgroundChecks() {
        periodicUpdateJob?.cancel()
        periodicUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(BACKGROUND_CHECK_INTERVAL_MS)
                val state = _updateState.value
                if (state !is UpdateUiState.Checking &&
                    state !is UpdateUiState.Downloading &&
                    state !is UpdateUiState.Installing
                ) {
                    checkForUpdates(
                        autoOpenIfAvailable = _liveRecordingState.value.status == RecorderStatus.IDLE,
                        silent = true
                    )
                }
            }
        }
    }

    fun onAppForegrounded() {
        refreshSdCardStatus(promptIfNeeded = true)
        refreshRecordings()
        val elapsed = System.currentTimeMillis() - lastUpdateCheckAtMs
        val state = _updateState.value
        if (elapsed >= FOREGROUND_CHECK_COOLDOWN_MS &&
            state !is UpdateUiState.Checking &&
            state !is UpdateUiState.Downloading &&
            state !is UpdateUiState.Installing
        ) {
            checkForUpdates(
                autoOpenIfAvailable = _liveRecordingState.value.status == RecorderStatus.IDLE,
                silent = true
            )
        }
    }

    private fun refreshSdCardStatus(promptIfNeeded: Boolean) {
        viewModelScope.launch {
            val detected = withContext(Dispatchers.IO) {
                recordingsRepository.detectMountedSdCard()
            }
            _sdCardInfo.value = detected
            val currentLocation = settingsRepository.storageLocationFlow.first()
            if (detected == null) {
                _showSdCardPrompt.value = false
            } else if (promptIfNeeded &&
                !hasPromptedSdCardThisSession &&
                currentLocation == StorageLocation.INTERNAL
            ) {
                hasPromptedSdCardThisSession = true
                _showSdCardPrompt.value = true
            }
        }
    }

    fun dismissSdCardPrompt() {
        _showSdCardPrompt.value = false
    }

    fun requestSwitchToSdCard() {
        if (_liveRecordingState.value.status != RecorderStatus.IDLE) {
            _uiMessages.tryEmit("Stop current recording before changing storage location")
            return
        }
        _showSdCardPrompt.value = false
        viewModelScope.launch {
            val sdCard = withContext(Dispatchers.IO) {
                recordingsRepository.detectMountedSdCard()
            }
            _sdCardInfo.value = sdCard
            if (sdCard == null) {
                _uiMessages.tryEmit("SD card not found")
                return@launch
            }
            val savedTree = settingsRepository.sdCardTreeUriFlow.first()
            val canWrite = withContext(Dispatchers.IO) {
                recordingsRepository.canWriteSdCardDirectly(sdCard, savedTree)
            }
            if (canWrite) {
                settingsRepository.updateStorageLocation(StorageLocation.SD_CARD)
                refreshRecordings()
                _uiMessages.tryEmit("Storage location changed to SD card")
            } else {
                _sdCardSafPickerEvents.tryEmit(recordingsRepository.buildOpenSdCardTreeIntent())
            }
        }
    }

    fun onSdCardTreeSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            settingsRepository.updateSdCardTreeUri(uri.toString())
            settingsRepository.updateStorageLocation(StorageLocation.SD_CARD)
            refreshRecordings()
            _uiMessages.tryEmit("Storage location changed to SD card")
        }
    }

    fun switchToInternalStorage() {
        if (_liveRecordingState.value.status != RecorderStatus.IDLE) {
            _uiMessages.tryEmit("Stop current recording before changing storage location")
            return
        }
        viewModelScope.launch {
            settingsRepository.updateStorageLocation(StorageLocation.INTERNAL)
            refreshRecordings()
            _uiMessages.tryEmit("Storage location changed to Internal Storage")
        }
    }

    private fun observeInstallSessionEvents() {
        viewModelScope.launch {
            InstallSessionEventBus.events.collect { event ->
                when (event) {
                    is InstallSessionEvent.PendingUserAction -> {
                        val current = _updateState.value
                        if (current is UpdateUiState.Installing) {
                            _updateState.value = UpdateUiState.ReadyToInstall(
                                currentVersion = current.currentVersion,
                                release = current.release,
                                apkFilePath = current.apkFilePath,
                                needsUnknownSourcePermission = false,
                                signatureMismatch = !updateRepository.hasCompatibleSigningCertificate(File(current.apkFilePath))
                            )
                        }
                        _installIntentEvents.tryEmit(event.confirmIntent)
                    }

                    is InstallSessionEvent.Succeeded -> {
                        _uiMessages.tryEmit("Update installed successfully")
                    }

                    is InstallSessionEvent.Aborted -> {
                        val current = _updateState.value
                        if (current is UpdateUiState.Installing) {
                            _updateState.value = UpdateUiState.ReadyToInstall(
                                currentVersion = current.currentVersion,
                                release = current.release,
                                apkFilePath = current.apkFilePath,
                                needsUnknownSourcePermission = !updateRepository.canRequestPackageInstalls(),
                                signatureMismatch = !updateRepository.hasCompatibleSigningCertificate(File(current.apkFilePath))
                            )
                        }
                    }

                    is InstallSessionEvent.Failed -> {
                        val current = _updateState.value
                        val installed = updateRepository.getInstalledVersion()
                        val release = when (current) {
                            is UpdateUiState.Installing -> current.release
                            is UpdateUiState.ReadyToInstall -> current.release
                            is UpdateUiState.Error -> current.release
                            else -> null
                        }
                        _updateState.value = UpdateUiState.Error(
                            currentVersion = installed.versionName,
                            message = event.message,
                            release = release
                        )
                        _uiMessages.tryEmit(event.message)
                    }
                }
            }
        }
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
                        _uiMessages.tryEmit("Saved ${event.result.fileName}")
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
        audioPlayer.stopIfPlaying()

        val intent = Intent(context, Mp3RecordingService::class.java)
        context.startService(intent)

        viewModelScope.launch {
            val targetLocation = settingsRepository.storageLocationFlow.first()
            val treeUri = settingsRepository.sdCardTreeUriFlow.first()
            val customTitle = _customDraftTitle.value.takeIf { it.isNotBlank() }

            val svc = _recordingService.value
            if (svc != null) {
                svc.startRecording(
                    config = qualityConfig.value,
                    customTitle = customTitle,
                    storageLocation = targetLocation,
                    sdCardTreeUri = treeUri
                )
            } else {
                bindRecordingService()
                _recordingService.collect { boundSvc ->
                    if (boundSvc != null && boundSvc.state.value.status == RecorderStatus.IDLE) {
                        boundSvc.startRecording(
                            config = qualityConfig.value,
                            customTitle = customTitle,
                            storageLocation = targetLocation,
                            sdCardTreeUri = treeUri
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

    fun updateAppTheme(themeMode: AppThemeMode) {
        hasUserChangedTheme = true
        _appTheme.value = themeMode
        viewModelScope.launch {
            settingsRepository.updateAppTheme(themeMode)
        }
    }

    fun openSettingsScreen() {
        _showSettingsScreen.value = true
    }

    fun dismissSettingsScreen() {
        _showSettingsScreen.value = false
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            val treeUri = settingsRepository.sdCardTreeUriFlow.first()
            _rawRecordings.value = recordingsRepository.loadRecordings(treeUri)
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
            val loc = settingsRepository.storageLocationFlow.first()
            val treeUri = settingsRepository.sdCardTreeUriFlow.first()
            recordingsRepository.convertUriToMp3(
                sourceUri = uri,
                bitrateKbps = cfg.bitrate.kbps,
                sampleRateHz = cfg.sampleRate.hz,
                channels = cfg.channelMode.channels,
                storageLocation = loc,
                sdCardTreeUri = treeUri
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
        _showSettingsScreen.value = false
        _showUpdateScreen.value = true
        val state = _updateState.value
        if (state is UpdateUiState.Idle ||
            state is UpdateUiState.UpToDate ||
            (state is UpdateUiState.Error && state.release == null)
        ) {
            checkForUpdates(autoOpenIfAvailable = false)
        }
    }

    fun dismissUpdateScreen() {
        _showUpdateScreen.value = false
    }

    fun checkForUpdates(
        autoOpenIfAvailable: Boolean = false,
        silent: Boolean = false
    ) {
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            lastUpdateCheckAtMs = System.currentTimeMillis()
            val installed = updateRepository.getInstalledVersion()

            if (!silent) {
                _updateState.value = UpdateUiState.Checking(installed.versionName)
            }
            runCatching {
                updateRepository.checkLatestRelease(GitHubUpdateRepository.TRUSTED_GITHUB_REPO)
            }.onSuccess { result ->
                when (result) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        applyUpdateAvailable(
                            installedVersion = installed.versionName,
                            release = result.release,
                            autoOpenIfAvailable = autoOpenIfAvailable
                        )
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
                val current = _updateState.value
                val hasActiveRelease = current is UpdateUiState.Available ||
                    current is UpdateUiState.ReadyToInstall ||
                    current is UpdateUiState.Downloading ||
                    current is UpdateUiState.Installing
                if (!silent && !hasActiveRelease) {
                    _updateState.value = UpdateUiState.Error(
                        currentVersion = installed.versionName,
                        message = err.message ?: "Unable to check GitHub Releases."
                    )
                }
            }
        }
    }

    private fun applyUpdateAvailable(
        installedVersion: String,
        release: GitHubReleaseInfo,
        autoOpenIfAvailable: Boolean
    ) {
        val current = _updateState.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Installing) {
            return
        }
        val cachedApk = updateRepository.findValidDownloadedApk(release)
        if (cachedApk != null) {
            _updateState.value = UpdateUiState.ReadyToInstall(
                currentVersion = installedVersion,
                release = release,
                apkFilePath = cachedApk.absolutePath,
                needsUnknownSourcePermission = !updateRepository.canRequestPackageInstalls(),
                signatureMismatch = !updateRepository.hasCompatibleSigningCertificate(cachedApk)
            )
        } else {
            _updateState.value = UpdateUiState.Available(
                currentVersion = installedVersion,
                release = release
            )
        }
        if (autoOpenIfAvailable &&
            _liveRecordingState.value.status == RecorderStatus.IDLE &&
            lastAutoOpenedTag != release.tagName
        ) {
            lastAutoOpenedTag = release.tagName
            _showUpdateScreen.value = true
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
        pendingInstallAfterPermission = false
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
                    release = release
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
                val canInstall = updateRepository.canRequestPackageInstalls()
                val signatureMismatch = !updateRepository.hasCompatibleSigningCertificate(apkFile)
                _updateState.value = UpdateUiState.ReadyToInstall(
                    currentVersion = installed.versionName,
                    release = release,
                    apkFilePath = apkFile.absolutePath,
                    needsUnknownSourcePermission = !canInstall,
                    signatureMismatch = signatureMismatch
                )
                if (canInstall && !signatureMismatch) {
                    startInAppInstall(installed.versionName, release, apkFile)
                }
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
        if (!updateRepository.isValidUpdateApk(apkFile)) {
            _updateState.value = UpdateUiState.Available(
                currentVersion = state.currentVersion,
                release = state.release
            )
            _uiMessages.tryEmit("Downloaded APK is missing or invalid. Please download again.")
            return
        }

        if (!updateRepository.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = true
            _updateState.value = state.copy(needsUnknownSourcePermission = true)
            return
        }

        pendingInstallAfterPermission = false
        startInAppInstall(state.currentVersion, state.release, apkFile)
    }

    fun onRequestUnknownSourcePermission() {
        pendingInstallAfterPermission = true
        _unknownSourceIntentEvents.tryEmit(updateRepository.buildUnknownSourcesSettingsIntent())
    }

    fun refreshInstallPermissionState() {
        val state = _updateState.value as? UpdateUiState.ReadyToInstall ?: return
        val canInstall = updateRepository.canRequestPackageInstalls()
        if (state.needsUnknownSourcePermission != !canInstall) {
            _updateState.value = state.copy(needsUnknownSourcePermission = !canInstall)
        }
        if (canInstall && pendingInstallAfterPermission) {
            pendingInstallAfterPermission = false
            val apkFile = File(state.apkFilePath)
            if (updateRepository.isValidUpdateApk(apkFile)) {
                startInAppInstall(state.currentVersion, state.release, apkFile)
            }
        }
    }

    private fun startInAppInstall(
        currentVersion: String,
        release: GitHubReleaseInfo,
        apkFile: File
    ) {
        if (!GitHubUpdateRepository.isTrustedReleaseDownloadUrl(release.apkDownloadUrl) ||
            !updateRepository.isValidUpdateApk(apkFile)
        ) {
            _updateState.value = UpdateUiState.Error(
                currentVersion = currentVersion,
                message = "Update APK failed integrity or signature verification.",
                release = release
            )
            return
        }
        updateInstallJob?.cancel()
        updateInstallJob = viewModelScope.launch {
            _updateState.value = UpdateUiState.Installing(
                currentVersion = currentVersion,
                release = release,
                apkFilePath = apkFile.absolutePath
            )
            runCatching {
                updateRepository.installApkViaPackageInstaller(apkFile)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                // Fallback to ACTION_VIEW package installer if session creation fails on an OEM ROM
                _updateState.value = UpdateUiState.ReadyToInstall(
                    currentVersion = currentVersion,
                    release = release,
                    apkFilePath = apkFile.absolutePath,
                    needsUnknownSourcePermission = !updateRepository.canRequestPackageInstalls(),
                    signatureMismatch = !updateRepository.hasCompatibleSigningCertificate(apkFile)
                )
                _installIntentEvents.tryEmit(updateRepository.buildInstallApkIntent(apkFile))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateCheckJob?.cancel()
        periodicUpdateJob?.cancel()
        updateDownloadJob?.cancel()
        updateInstallJob?.cancel()
        audioPlayer.release()
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            isBound = false
        }
    }

    companion object {
        private const val BACKGROUND_CHECK_INTERVAL_MS = 60L * 1000L
        private const val FOREGROUND_CHECK_COOLDOWN_MS = 30L * 1000L
    }
}
