package com.android.abdullah.voicerecorder.mp3.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.data.GitHubUpdateRepository
import com.android.abdullah.voicerecorder.mp3.model.AppThemeMode
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.model.UpdateUiState
import com.android.abdullah.voicerecorder.mp3.ui.components.QualitySettingsSheet
import com.android.abdullah.voicerecorder.mp3.ui.screens.LibraryScreen
import com.android.abdullah.voicerecorder.mp3.ui.screens.RecordRoute
import com.android.abdullah.voicerecorder.mp3.ui.screens.SettingsScreen
import com.android.abdullah.voicerecorder.mp3.ui.screens.UpdateScreen
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import com.android.abdullah.voicerecorder.mp3.ui.theme.VoiceRecorderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderApp(
    openUpdatesSignal: Int = 0,
    viewModel: VoiceRecorderViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorderStatus by viewModel.recorderStatus.collectAsStateWithLifecycle()
    val qualityConfig by viewModel.qualityConfig.collectAsStateWithLifecycle()
    val recordings by viewModel.filteredRecordings.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val draftTitle by viewModel.customDraftTitle.collectAsStateWithLifecycle()
    val isConvertingMp3 by viewModel.isConvertingMp3.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val showUpdateScreen by viewModel.showUpdateScreen.collectAsStateWithLifecycle()
    val showSettingsScreen by viewModel.showSettingsScreen.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val storageLocation by viewModel.storageLocation.collectAsStateWithLifecycle()
    val sdCardInfo by viewModel.sdCardInfo.collectAsStateWithLifecycle()
    val showSdCardPrompt by viewModel.showSdCardPrompt.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(MainDestination.RECORD) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showQualitySheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val convertPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.convertAudioUriToMp3(uri)
        }
    }

    val sdCardTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onSdCardTreeSelected(result.data?.data)
        }
    }

    LaunchedEffect(openUpdatesSignal) {
        if (openUpdatesSignal > 0) {
            viewModel.openUpdateScreen()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sdCardSafPickerEvents.collect { intent ->
            runCatching {
                sdCardTreeLauncher.launch(intent)
            }.onFailure { err ->
                snackbarHostState.showSnackbar(err.message ?: "Unable to open SD card folder picker")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.installIntentEvents.collect { intent ->
            runCatching {
                context.startActivity(intent)
            }.onFailure { err ->
                snackbarHostState.showSnackbar(err.message ?: "Unable to launch package installer")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.unknownSourceIntentEvents.collect { intent ->
            runCatching {
                context.startActivity(intent)
            }.onFailure { err ->
                snackbarHostState.showSnackbar(err.message ?: "Unable to open Unknown Sources settings")
            }
        }
    }

    VoiceRecorderTheme(darkTheme = appTheme == AppThemeMode.DARK) {
        if (showUpdateScreen) {
            UpdateScreen(
                updateState = updateState,
                onBack = viewModel::dismissUpdateScreen,
                onCheckAgain = { viewModel.checkForUpdates(autoOpenIfAvailable = false) },
                onDownloadUpdate = viewModel::downloadUpdate,
                onCancelDownload = viewModel::cancelDownload,
                onInstallNow = viewModel::onInstallNowClicked,
                onRequestUnknownSourcePermission = viewModel::onRequestUnknownSourcePermission,
                onRefreshInstallPermission = viewModel::refreshInstallPermissionState
            )
        } else if (showSettingsScreen) {
            SettingsScreen(
                appTheme = appTheme,
                qualityConfig = qualityConfig,
                storageLocation = storageLocation,
                hasSdCard = sdCardInfo != null,
                onThemeSelected = viewModel::updateAppTheme,
                onOpenQualitySettings = { showQualitySheet = true },
                onOpenGitHubSource = {
                    val repoUrl = "https://github.com/${GitHubUpdateRepository.TRUSTED_GITHUB_REPO}"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
                    }
                },
                onSwitchToSdCard = viewModel::requestSwitchToSdCard,
                onSwitchToInternalStorage = viewModel::switchToInternalStorage,
                onBack = viewModel::dismissSettingsScreen
            )
        } else {
            BackHandler(enabled = currentDestination == MainDestination.LIBRARY) {
                currentDestination = MainDestination.RECORD
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedLayout = maxWidth >= 840.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        val hasPendingUpdate = updateState is UpdateUiState.Available ||
                            updateState is UpdateUiState.Downloading ||
                            updateState is UpdateUiState.Installing ||
                            updateState is UpdateUiState.ReadyToInstall
                        Box {
                            IconButton(
                                onClick = { moreMenuExpanded = true },
                                modifier = Modifier.testTag("more_options_button")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (hasPendingUpdate) {
                                            Badge(containerColor = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.action_more_options)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.testTag("more_options_menu")
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Settings,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        viewModel.openSettingsScreen()
                                    },
                                    modifier = Modifier.testTag("menu_settings")
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_check_updates)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.SystemUpdate,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        viewModel.openUpdateScreen()
                                    },
                                    modifier = Modifier.testTag("menu_check_updates")
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (!isExpandedLayout) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = currentDestination == MainDestination.RECORD,
                            onClick = { currentDestination = MainDestination.RECORD },
                            icon = {
                                Icon(
                                    imageVector = if (currentDestination == MainDestination.RECORD) {
                                        Icons.Filled.Mic
                                    } else {
                                        Icons.Outlined.Mic
                                    },
                                    contentDescription = stringResource(R.string.tab_record)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_record)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("nav_record_tab")
                        )

                        NavigationBarItem(
                            selected = currentDestination == MainDestination.LIBRARY,
                            onClick = { currentDestination = MainDestination.LIBRARY },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (recordings.isNotEmpty()) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(recordings.size.toString())
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (currentDestination == MainDestination.LIBRARY) {
                                            Icons.Filled.LibraryMusic
                                        } else {
                                            Icons.Outlined.LibraryMusic
                                        },
                                        contentDescription = stringResource(R.string.tab_library)
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.tab_library)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("nav_library_tab")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val bannerRelease = when (val st = updateState) {
                    is UpdateUiState.Available -> st.release
                    is UpdateUiState.ReadyToInstall -> st.release
                    else -> null
                }
                AnimatedVisibility(visible = bannerRelease != null) {
                    if (bannerRelease != null) {
                        val isReadyToInstall = updateState is UpdateUiState.ReadyToInstall
                        val bannerAccent = if (isReadyToInstall) EmeraldClean else MaterialTheme.colorScheme.secondary
                        Surface(
                            color = bannerAccent.copy(alpha = 0.14f),
                            border = BorderStroke(
                                1.dp,
                                bannerAccent.copy(alpha = 0.45f)
                            ),
                            shape = MaterialTheme.shapes.medium,
                            onClick = viewModel::openUpdateScreen,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("in_app_update_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.NewReleases,
                                    contentDescription = null,
                                    tint = bannerAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(
                                            R.string.update_banner_title,
                                            bannerRelease.versionName
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(
                                            if (isReadyToInstall) {
                                                R.string.update_banner_ready_subtitle
                                            } else {
                                                R.string.update_banner_subtitle
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = viewModel::openUpdateScreen,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = bannerAccent,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("in_app_update_banner_button")
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (isReadyToInstall) {
                                                R.string.update_banner_install_action
                                            } else {
                                                R.string.update_banner_action
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isExpandedLayout) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                RecordRoute(
                                    liveStateFlow = viewModel.liveRecordingState,
                                    draftTitle = draftTitle,
                                    storageLocation = storageLocation,
                                    onDraftTitleChange = viewModel::updateDraftTitle,
                                    onStartRecording = viewModel::startRecording,
                                    onPauseRecording = viewModel::pauseRecording,
                                    onResumeRecording = viewModel::resumeRecording,
                                    onStopAndSave = viewModel::stopAndSaveRecording,
                                    onDiscard = viewModel::discardRecording
                                )
                            }

                            VerticalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                LibraryScreen(
                                    recordings = recordings,
                                    searchQuery = searchQuery,
                                    sortOrder = sortOrder,
                                    playbackState = playbackState,
                                    isConvertingMp3 = isConvertingMp3,
                                    onSearchQueryChange = viewModel::updateSearchQuery,
                                    onSortOrderChange = viewModel::updateSortOrder,
                                    onTogglePlay = viewModel::togglePlayback,
                                    onSeekTo = viewModel::seekTo,
                                    onSkipBy = viewModel::skipBy,
                                    onCycleSpeed = viewModel::cyclePlaybackSpeed,
                                    onRename = viewModel::renameRecording,
                                    onShare = { rec ->
                                        viewModel.buildShareIntent(rec)?.let { shareIntent ->
                                            context.startActivity(
                                                Intent.createChooser(shareIntent, "Share")
                                            )
                                        }
                                    },
                                    onExport = viewModel::exportToDevice,
                                    onDelete = viewModel::deleteRecording,
                                    onConvertToMp3 = { convertPickerLauncher.launch(arrayOf("audio/*")) }
                                )
                            }
                        }
                    } else {
                        when (currentDestination) {
                            MainDestination.RECORD -> {
                                RecordRoute(
                                    liveStateFlow = viewModel.liveRecordingState,
                                    draftTitle = draftTitle,
                                    storageLocation = storageLocation,
                                    onDraftTitleChange = viewModel::updateDraftTitle,
                                    onStartRecording = viewModel::startRecording,
                                    onPauseRecording = viewModel::pauseRecording,
                                    onResumeRecording = viewModel::resumeRecording,
                                    onStopAndSave = viewModel::stopAndSaveRecording,
                                    onDiscard = viewModel::discardRecording
                                )
                            }
                            MainDestination.LIBRARY -> {
                                LibraryScreen(
                                    recordings = recordings,
                                    searchQuery = searchQuery,
                                    sortOrder = sortOrder,
                                    playbackState = playbackState,
                                    isConvertingMp3 = isConvertingMp3,
                                    onSearchQueryChange = viewModel::updateSearchQuery,
                                    onSortOrderChange = viewModel::updateSortOrder,
                                    onTogglePlay = viewModel::togglePlayback,
                                    onSeekTo = viewModel::seekTo,
                                    onSkipBy = viewModel::skipBy,
                                    onCycleSpeed = viewModel::cyclePlaybackSpeed,
                                    onRename = viewModel::renameRecording,
                                    onShare = { rec ->
                                        viewModel.buildShareIntent(rec)?.let { shareIntent ->
                                            context.startActivity(
                                                Intent.createChooser(shareIntent, "Share")
                                            )
                                        }
                                    },
                                    onExport = viewModel::exportToDevice,
                                    onDelete = viewModel::deleteRecording,
                                    onConvertToMp3 = { convertPickerLauncher.launch(arrayOf("audio/*")) },
                                    onGoToRecord = { currentDestination = MainDestination.RECORD }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        }

        if (showQualitySheet) {
            QualitySettingsSheet(
                config = qualityConfig,
                isRecordingActive = recorderStatus != RecorderStatus.IDLE,
                onBitrateSelected = viewModel::updateBitrate,
                onSampleRateSelected = viewModel::updateSampleRate,
                onChannelModeSelected = viewModel::updateChannelMode,
                onDismiss = { showQualitySheet = false }
            )
        }

        if (showSdCardPrompt) {
            AlertDialog(
                onDismissRequest = viewModel::dismissSdCardPrompt,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.SdCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.sd_card_prompt_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.sd_card_prompt_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::requestSwitchToSdCard,
                        modifier = Modifier.testTag("sd_card_prompt_change_button")
                    ) {
                        Text(stringResource(R.string.action_change))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::dismissSdCardPrompt,
                        modifier = Modifier.testTag("sd_card_prompt_cancel_button")
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
                modifier = Modifier.testTag("sd_card_prompt_dialog")
            )
        }
    }
}
