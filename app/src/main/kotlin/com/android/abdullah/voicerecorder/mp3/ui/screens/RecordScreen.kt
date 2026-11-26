package com.android.abdullah.voicerecorder.mp3.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.ui.components.StudioWaveformCard
import com.android.abdullah.voicerecorder.mp3.ui.theme.AmberMeter
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils

@Composable
fun RecordScreen(
    liveState: LiveRecordingState,
    draftTitle: String,
    storageLocation: StorageLocation = StorageLocation.INTERNAL,
    onDraftTitleChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var micPermissionDeniedOnce by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasMicPermission = granted
                if (granted) {
                    micPermissionDeniedOnce = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onStartRecording()
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onStartRecording()
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            micPermissionDeniedOnce = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                storageLocation == StorageLocation.INTERNAL &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onStartRecording()
            }
        } else {
            micPermissionDeniedOnce = true
        }
    }

    fun requestMicAndStart() {
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasMicPermission = micGranted
        if (!micGranted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            storageLocation == StorageLocation.INTERNAL &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStartRecording()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Minimal recording title field when idle, or active file size row when recording
            if (liveState.status == RecorderStatus.IDLE) {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = onDraftTitleChange,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "Title",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recording_title_input")
                )
            } else {
                ActiveRecordingStatusRow(
                    status = liveState.status,
                    fileName = liveState.activeFileName,
                    encodedBytes = liveState.encodedBytes
                )
            }

            // Minimal Precision Timecode Readout (no extra descriptive lines)
            Text(
                text = FormatUtils.formatTimerWithTenths(liveState.elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .testTag("recording_timer_text")
            )

            // Clean Material Waveform
            StudioWaveformCard(
                amplitudes = liveState.amplitudes,
                currentLevelDb = liveState.currentLevelDb,
                status = liveState.status
            )

            // Microphone permission notice if denied
            if (!hasMicPermission && micPermissionDeniedOnce && liveState.status == RecorderStatus.IDLE) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.permission_mic_title),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.permission_mic_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = stringResource(R.string.permission_settings_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { requestMicAndStart() },
                                modifier = Modifier.testTag("retry_mic_permission_button")
                            ) {
                                Text(stringResource(R.string.permission_mic_button))
                            }
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.testTag("open_settings_button")
                            ) {
                                Text(stringResource(R.string.open_app_settings))
                            }
                        }
                    }
                }
            }
        }

        // Pinned Bottom-Center Material Transport Controls
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TransportControlsDeck(
                status = liveState.status,
                onStart = { requestMicAndStart() },
                onPause = onPauseRecording,
                onResume = onResumeRecording,
                onStopAndSave = onStopAndSave,
                onRequestDiscard = {
                    if (liveState.elapsedMs >= 3000L) {
                        showDiscardConfirm = true
                    } else {
                        onDiscard()
                    }
                }
            )
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text("Discard recording?", style = MaterialTheme.typography.titleLarge)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onDiscard()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_discard_button")
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ActiveRecordingStatusRow(
    status: RecorderStatus,
    fileName: String?,
    encodedBytes: Long
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    val dotColor = when (status) {
        RecorderStatus.RECORDING -> CoralRecord.copy(alpha = dotAlpha)
        RecorderStatus.PAUSED -> AmberMeter
        RecorderStatus.FINALIZING -> CyanAccent
        RecorderStatus.IDLE -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = fileName.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = FormatUtils.formatFileSize(encodedBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransportControlsDeck(
    status: RecorderStatus,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onRequestDiscard: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "recordHalo")
    val haloScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )

    val isActive = status == RecorderStatus.RECORDING || status == RecorderStatus.PAUSED

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left zone: Material FilledTonalIconButton for Discard
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                FilledTonalIconButton(
                    onClick = onRequestDiscard,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .size(56.dp)
                        .testTag("discard_recording_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_discard)
                    )
                }
            }
        }

        // Center zone: Material LargeFloatingActionButton for Record / Pause / Resume
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(104.dp)
        ) {
            if (status == RecorderStatus.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .scale(haloScale)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), CircleShape)
                )
            }

            val buttonDescription = when (status) {
                RecorderStatus.IDLE -> stringResource(R.string.action_record)
                RecorderStatus.RECORDING -> stringResource(R.string.action_pause)
                RecorderStatus.PAUSED -> stringResource(R.string.action_resume)
                RecorderStatus.FINALIZING -> stringResource(R.string.status_encoding)
            }

            LargeFloatingActionButton(
                onClick = {
                    if (status != RecorderStatus.FINALIZING) {
                        when (status) {
                            RecorderStatus.IDLE -> onStart()
                            RecorderStatus.RECORDING -> onPause()
                            RecorderStatus.PAUSED -> onResume()
                            RecorderStatus.FINALIZING -> Unit
                        }
                    }
                },
                shape = CircleShape,
                containerColor = when (status) {
                    RecorderStatus.IDLE -> MaterialTheme.colorScheme.primary
                    RecorderStatus.RECORDING -> MaterialTheme.colorScheme.surfaceContainerHigh
                    RecorderStatus.PAUSED -> MaterialTheme.colorScheme.primary
                    RecorderStatus.FINALIZING -> MaterialTheme.colorScheme.surfaceContainer
                },
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 4.dp
                ),
                modifier = Modifier
                    .size(80.dp)
                    .semantics { contentDescription = buttonDescription }
                    .testTag("primary_record_button")
            ) {
                Icon(
                    imageVector = when (status) {
                        RecorderStatus.IDLE -> Icons.Filled.Mic
                        RecorderStatus.RECORDING -> Icons.Filled.Pause
                        RecorderStatus.PAUSED -> Icons.Filled.PlayArrow
                        RecorderStatus.FINALIZING -> Icons.Filled.Mic
                    },
                    contentDescription = null,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        // Right zone: Material FilledIconButton for Stop & Save
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                FilledIconButton(
                    onClick = onStopAndSave,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .size(56.dp)
                        .testTag("stop_save_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.action_stop_save)
                    )
                }
            }
        }
    }
}
