package com.android.abdullah.voicerecorder.mp3.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.ui.components.QualitySettingsSheet
import com.android.abdullah.voicerecorder.mp3.ui.screens.LibraryScreen
import com.android.abdullah.voicerecorder.mp3.ui.screens.RecordScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderApp(
    viewModel: VoiceRecorderViewModel = viewModel()
) {
    val context = LocalContext.current
    val liveRecordingState by viewModel.liveRecordingState.collectAsStateWithLifecycle()
    val qualityConfig by viewModel.qualityConfig.collectAsStateWithLifecycle()
    val recordings by viewModel.filteredRecordings.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val draftTitle by viewModel.customDraftTitle.collectAsStateWithLifecycle()
    val isConvertingMp3 by viewModel.isConvertingMp3.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(MainDestination.RECORD) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showQualitySheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val convertPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.convertAudioUriToMp3(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

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
                        Box {
                            IconButton(
                                onClick = { moreMenuExpanded = true },
                                modifier = Modifier.testTag("more_options_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.action_more_options)
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.testTag("more_options_menu")
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_settings)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Tune,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        showQualitySheet = true
                                    },
                                    modifier = Modifier.testTag("menu_quality_settings")
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
            if (isExpandedLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        RecordScreen(
                            liveState = liveRecordingState,
                            draftTitle = draftTitle,
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentDestination) {
                        MainDestination.RECORD -> {
                            RecordScreen(
                                liveState = liveRecordingState,
                                draftTitle = draftTitle,
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

    if (showQualitySheet) {
        QualitySettingsSheet(
            config = qualityConfig,
            isRecordingActive = liveRecordingState.status != RecorderStatus.IDLE,
            onBitrateSelected = viewModel::updateBitrate,
            onSampleRateSelected = viewModel::updateSampleRate,
            onChannelModeSelected = viewModel::updateChannelMode,
            onDismiss = { showQualitySheet = false }
        )
    }
}
