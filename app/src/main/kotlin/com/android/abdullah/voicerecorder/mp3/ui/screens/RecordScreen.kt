package com.android.abdullah.voicerecorder.mp3.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.model.StorageLocation
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.ui.components.StudioWaveformCard
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioObsidian
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils
import kotlinx.coroutines.flow.StateFlow

@Composable
fun RecordRoute(
    liveStateFlow: StateFlow<LiveRecordingState>,
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
    val liveState by liveStateFlow.collectAsStateWithLifecycle()
    RecordScreen(
        liveState = liveState,
        draftTitle = draftTitle,
        storageLocation = storageLocation,
        onDraftTitleChange = onDraftTitleChange,
        onStartRecording = onStartRecording,
        onPauseRecording = onPauseRecording,
        onResumeRecording = onResumeRecording,
        onStopAndSave = onStopAndSave,
        onDiscard = onDiscard,
        modifier = modifier
    )
}

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
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current

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
        focusManager.clearFocus()
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
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val isCompactHeight = maxHeight < 420.dp
            val verticalGap = if (isCompactHeight) 12.dp else 18.dp
            val waveformHeight = if (isCompactHeight) 104.dp else 144.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(verticalGap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (liveState.status == RecorderStatus.IDLE) {
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = onDraftTitleChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
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
                        trailingIcon = {
                            if (draftTitle.isNotEmpty()) {
                                IconButton(
                                    onClick = { onDraftTitleChange("") },
                                    modifier = Modifier.testTag("clear_title_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.action_clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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

                TimerReadout(
                    elapsedMs = liveState.elapsedMs,
                    status = liveState.status
                )

                StudioWaveformCard(
                    amplitudes = liveState.amplitudes,
                    currentLevelDb = liveState.currentLevelDb,
                    status = liveState.status,
                    waveformHeight = waveformHeight
                )

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
        }

        // Pinned Bottom Studio Transport Controls
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(top = 6.dp, bottom = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            TransportControlsDeck(
                status = liveState.status,
                onStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    requestMicAndStart()
                },
                onPause = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPauseRecording()
                },
                onResume = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onResumeRecording()
                },
                onStopAndSave = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStopAndSave()
                },
                onRequestDiscard = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                Text(
                    text = stringResource(R.string.discard_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.discard_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TimerReadout(
    elapsedMs: Long,
    status: RecorderStatus
) {
    val timerColor = when (status) {
        RecorderStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        RecorderStatus.FINALIZING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onBackground
    }
    val baseStyle = MaterialTheme.typography.displayLarge
    val tabularStyle = remember(baseStyle) {
        baseStyle.copy(fontFeatureSettings = "tnum")
    }

    Text(
        text = FormatUtils.formatTimerWithTenths(elapsedMs),
        style = tabularStyle,
        color = timerColor,
        maxLines = 1,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .testTag("recording_timer_text")
    )
}

@Composable
private fun ActiveRecordingStatusRow(
    status: RecorderStatus,
    fileName: String?,
    encodedBytes: Long
) {
    val dotAlpha = if (status == RecorderStatus.RECORDING) {
        val infiniteTransition = rememberInfiniteTransition(label = "recDot")
        val animated by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "recDotAlpha"
        )
        animated
    } else {
        1f
    }

    val statusTint = when (status) {
        RecorderStatus.RECORDING -> CoralRecord
        RecorderStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        RecorderStatus.FINALIZING -> MaterialTheme.colorScheme.secondary
        RecorderStatus.IDLE -> MaterialTheme.colorScheme.outline
    }

    val badgeLabel = when (status) {
        RecorderStatus.RECORDING -> "REC"
        RecorderStatus.PAUSED -> "PAUSED"
        RecorderStatus.FINALIZING -> "MP3"
        RecorderStatus.IDLE -> ""
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, statusTint.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusTint.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusTint.copy(alpha = dotAlpha), CircleShape)
                    )
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTint
                    )
                }
            }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
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
    val isIdle = status == RecorderStatus.IDLE

    AnimatedContent(
        targetState = isIdle,
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                .togetherWith(fadeOut(tween(130)))
        },
        label = "transportDeckTransition"
    ) { idleMode ->
        if (idleMode) {
            IdleRecordHeroButton(onStart = onStart)
        } else {
            ActiveRecordingTransportDock(
                status = status,
                onPause = onPause,
                onResume = onResume,
                onStopAndSave = onStopAndSave,
                onRequestDiscard = onRequestDiscard
            )
        }
    }
}

@Composable
private fun IdleRecordHeroButton(
    onStart: () -> Unit
) {
    val startDesc = stringResource(R.string.action_record)
    Surface(
        onClick = onStart,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .widthIn(min = 232.dp, max = 320.dp)
            .height(68.dp)
            .semantics {
                contentDescription = startDesc
                role = Role.Button
            }
            .testTag("primary_record_button")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = startDesc,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActiveRecordingTransportDock(
    status: RecorderStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onRequestDiscard: () -> Unit
) {
    val isBusyFinalizing = status == RecorderStatus.FINALIZING
    val haloScale = if (status == RecorderStatus.RECORDING) {
        val pulseTransition = rememberInfiniteTransition(label = "recordHalo")
        val animated by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(850),
                repeatMode = RepeatMode.Reverse
            ),
            label = "haloScale"
        )
        animated
    } else {
        1f
    }

    val primaryDesc = when (status) {
        RecorderStatus.IDLE -> stringResource(R.string.action_record)
        RecorderStatus.RECORDING -> stringResource(R.string.action_pause)
        RecorderStatus.PAUSED -> stringResource(R.string.action_resume)
        RecorderStatus.FINALIZING -> stringResource(R.string.status_encoding)
    }
    val discardDesc = stringResource(R.string.action_discard)
    val saveDesc = stringResource(R.string.action_stop_save)

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left: Ergonomic Discard button with unmistakable label
            Surface(
                onClick = onRequestDiscard,
                enabled = !isBusyFinalizing,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isBusyFinalizing) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .semantics {
                        contentDescription = discardDesc
                        role = Role.Button
                    }
                    .testTag("discard_recording_button")
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (isBusyFinalizing) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = discardDesc,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Center: Focal Pause / Resume circular button with acoustic ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(88.dp)
            ) {
                if (status == RecorderStatus.RECORDING) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .scale(haloScale)
                            .background(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                                CircleShape
                            )
                    )
                }

                val centerContainer = when (status) {
                    RecorderStatus.RECORDING -> MaterialTheme.colorScheme.surfaceContainerHigh
                    RecorderStatus.PAUSED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                val centerContent = when (status) {
                    RecorderStatus.PAUSED -> MaterialTheme.colorScheme.onPrimary
                    RecorderStatus.FINALIZING -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val centerBorder = when (status) {
                    RecorderStatus.RECORDING -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                    else -> null
                }

                Surface(
                    onClick = {
                        when (status) {
                            RecorderStatus.RECORDING -> onPause()
                            RecorderStatus.PAUSED -> onResume()
                            else -> Unit
                        }
                    },
                    enabled = !isBusyFinalizing,
                    shape = CircleShape,
                    color = centerContainer,
                    contentColor = centerContent,
                    border = centerBorder,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(78.dp)
                        .semantics {
                            contentDescription = primaryDesc
                            role = Role.Button
                        }
                        .testTag("primary_record_button")
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBusyFinalizing) {
                            CircularProgressIndicator(
                                color = CyanAccent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(26.dp)
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (status == RecorderStatus.PAUSED) {
                                        Icons.Filled.PlayArrow
                                    } else {
                                        Icons.Filled.Pause
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp)
                                )
                                Text(
                                    text = stringResource(
                                        if (status == RecorderStatus.PAUSED) {
                                            R.string.label_resume
                                        } else {
                                            R.string.label_pause
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Right: High-contrast Save MP3 button
            Surface(
                onClick = onStopAndSave,
                enabled = !isBusyFinalizing,
                shape = RoundedCornerShape(20.dp),
                color = if (isBusyFinalizing) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    EmeraldClean
                },
                contentColor = if (isBusyFinalizing) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    StudioObsidian
                },
                shadowElevation = if (isBusyFinalizing) 0.dp else 3.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .semantics {
                        contentDescription = saveDesc
                        role = Role.Button
                    }
                    .testTag("stop_save_button")
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            if (isBusyFinalizing) R.string.label_saving else R.string.label_save
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
