package com.android.abdullah.voicerecorder.mp3.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.service.LiveRecordingState
import com.android.abdullah.voicerecorder.mp3.ui.components.StudioWaveformCard
import com.android.abdullah.voicerecorder.mp3.ui.theme.AmberMeter
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecordContainer
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioOutline
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurface
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurfaceElevated
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurfaceHigh
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils

@Composable
fun RecordScreen(
    liveState: LiveRecordingState,
    qualityConfig: RecordingQualityConfig,
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpenQualitySettings: () -> Unit,
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

    // Refresh permission state when returning from App Settings
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

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            micPermissionDeniedOnce = false
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
        // Upper scrollable studio area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // MP3 Format & Quality Pill Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicatorPill(status = liveState.status)

                Surface(
                    onClick = onOpenQualitySettings,
                    shape = RoundedCornerShape(999.dp),
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, StudioOutline),
                    modifier = Modifier.testTag("quality_pill_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(CoralRecordContainer, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "MP3",
                                style = MaterialTheme.typography.labelSmall,
                                color = CoralRecord
                            )
                        }
                        Text(
                            text = "${qualityConfig.bitrate.label} · ${qualityConfig.sampleRate.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Optional recording title input (when idle) or active MP3 filename badge
            if (liveState.status == RecorderStatus.IDLE) {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = onDraftTitleChange,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "Optional title (auto: রেকর্ডিং_DD_MM_YYYY_HH_MM_SS.mp3)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Recording title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    suffix = {
                        Text(
                            text = ".mp3",
                            style = MaterialTheme.typography.labelMedium,
                            color = CoralRecord
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CoralRecord,
                        unfocusedBorderColor = StudioOutline,
                        focusedContainerColor = StudioSurface,
                        unfocusedContainerColor = StudioSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recording_title_input")
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = liveState.activeFileName ?: "রেকর্ডিং_...mp3",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = FormatUtils.formatFileSize(liveState.encodedBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent
                        )
                    }
                }
            }

            // Precision Timecode Readout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Text(
                    text = FormatUtils.formatTimerWithTenths(liveState.elapsedMs),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("recording_timer_text")
                )
                Text(
                    text = when (liveState.status) {
                        RecorderStatus.IDLE -> "Native FFmpeg (libffmpeg.so) MP3 Engine"
                        RecorderStatus.RECORDING -> "Capturing ${qualityConfig.channelMode.label} MP3 at ${qualityConfig.bitrate.label}"
                        RecorderStatus.PAUSED -> "Paused — Tap center button to resume or W/X to save"
                        RecorderStatus.FINALIZING -> "Flushing MP3 frames…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Live Waveform & dBFS VU Meter
            StudioWaveformCard(
                amplitudes = liveState.amplitudes,
                currentLevelDb = liveState.currentLevelDb,
                status = liveState.status
            )

            // Microphone permission notice if denied
            if (!hasMicPermission && micPermissionDeniedOnce && liveState.status == RecorderStatus.IDLE) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CoralRecordContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Microphone permission required",
                                tint = CoralRecord
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
                                colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
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

        // Pinned Bottom-Center Transport Controls Deck
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(top = 4.dp, bottom = 12.dp),
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
            containerColor = StudioSurfaceElevated,
            title = {
                Text("Discard MP3 Recording?", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text(
                    "This will permanently delete the current ${FormatUtils.formatDuration(liveState.elapsedMs)} recording.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onDiscard()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
                    modifier = Modifier.testTag("confirm_discard_button")
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep Recording")
                }
            }
        )
    }
}

@Composable
private fun StatusIndicatorPill(status: RecorderStatus) {
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

    val (dotColor, labelText) = when (status) {
        RecorderStatus.IDLE -> EmeraldClean to stringResource(R.string.status_ready)
        RecorderStatus.RECORDING -> CoralRecord.copy(alpha = dotAlpha) to stringResource(R.string.status_recording)
        RecorderStatus.PAUSED -> AmberMeter to stringResource(R.string.status_paused)
        RecorderStatus.FINALIZING -> CyanAccent to stringResource(R.string.status_encoding)
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = StudioSurfaceElevated,
        border = BorderStroke(1.dp, StudioOutline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
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

    // Symmetric 3-column layout ensures the primary button stays dead-center at the bottom
    // while the Stop (3 W's & X) button sits immediately to its right when active.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left zone: Discard button when active
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                Column(
                    modifier = Modifier.padding(end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onRequestDiscard,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = StudioSurfaceHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .testTag("discard_recording_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_discard)
                        )
                    }
                    Text(
                        text = "Discard",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Exact Bottom-Center Zone: Red 3-Line Record Button -> transforms into M-shaped Pause Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(100.dp)
        ) {
            if (status == RecorderStatus.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .scale(haloScale)
                        .background(CoralRecord.copy(alpha = 0.20f), CircleShape)
                )
            }

            val buttonDescription = when (status) {
                RecorderStatus.IDLE -> stringResource(R.string.action_record)
                RecorderStatus.RECORDING -> stringResource(R.string.action_pause)
                RecorderStatus.PAUSED -> stringResource(R.string.action_resume)
                RecorderStatus.FINALIZING -> stringResource(R.string.status_encoding)
            }

            Surface(
                onClick = {
                    when (status) {
                        RecorderStatus.IDLE -> onStart()
                        RecorderStatus.RECORDING -> onPause()
                        RecorderStatus.PAUSED -> onResume()
                        RecorderStatus.FINALIZING -> Unit
                    }
                },
                enabled = status != RecorderStatus.FINALIZING,
                shape = CircleShape,
                color = when (status) {
                    RecorderStatus.IDLE -> Color(0xFF1F0E11)
                    RecorderStatus.RECORDING -> StudioSurfaceHigh
                    RecorderStatus.PAUSED -> CoralRecord
                    RecorderStatus.FINALIZING -> StudioSurfaceElevated
                },
                border = BorderStroke(
                    width = 2.5.dp,
                    color = when (status) {
                        RecorderStatus.IDLE -> CoralRecord
                        RecorderStatus.RECORDING -> CoralRecord
                        RecorderStatus.PAUSED -> Color.White.copy(alpha = 0.25f)
                        RecorderStatus.FINALIZING -> StudioOutline
                    }
                ),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .size(82.dp)
                    .semantics { contentDescription = buttonDescription }
                    .testTag("primary_record_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    CoralRecord.copy(alpha = if (status == RecorderStatus.IDLE) 0.18f else 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                ) {
                    when (status) {
                        RecorderStatus.IDLE -> {
                            RedThreeLineRecordGlyph(
                                color = CoralRecord,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        RecorderStatus.RECORDING -> {
                            MPauseGlyph(
                                color = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        RecorderStatus.PAUSED -> {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        RecorderStatus.FINALIZING -> {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        }

        // Right zone: Immediately to the right of the Record/Pause button -> Stop button shaped with 3 W's & X
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                val stopDescription = stringResource(R.string.action_stop_save)
                Column(
                    modifier = Modifier.padding(start = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = onStopAndSave,
                        shape = CircleShape,
                        color = EmeraldClean.copy(alpha = 0.16f),
                        border = BorderStroke(1.5.dp, EmeraldClean.copy(alpha = 0.65f)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .size(64.dp)
                            .semantics { contentDescription = stopDescription }
                            .testTag("stop_save_button")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ThreeWAndXStopGlyph(
                                color = EmeraldClean,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Text(
                        text = "Stop & Save",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldClean
                    )
                }
            }
        }
    }
}

/**
 * Custom Red Three-Line Record glyph ("লাল তিনটি লাইন রেকর্ড বাটন").
 */
@Composable
private fun RedThreeLineRecordGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 4.5.dp.toPx()

        val xPositions = listOf(w * 0.22f, w * 0.50f, w * 0.78f)
        val topOffsets = listOf(h * 0.24f, h * 0.12f, h * 0.24f)
        val bottomOffsets = listOf(h * 0.76f, h * 0.88f, h * 0.76f)

        for (i in 0 until 3) {
            drawLine(
                color = color,
                start = Offset(xPositions[i], topOffsets[i]),
                end = Offset(xPositions[i], bottomOffsets[i]),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Custom M-shaped Pause glyph ("পজ বাটন এম এর আকৃতিতে").
 */
@Composable
private fun MPauseGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 4.2.dp.toPx()

        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.82f)
            lineTo(w * 0.16f, h * 0.18f)
            lineTo(w * 0.50f, h * 0.58f)
            lineTo(w * 0.84f, h * 0.18f)
            lineTo(w * 0.84f, h * 0.82f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * Custom Stop glyph featuring three W's and an X ("তিনটি ডাব্লিউ এবং এক্স এর আকৃতির বোতাম").
 */
@Composable
private fun ThreeWAndXStopGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val wStroke = 1.9.dp.toPx()
        val xStroke = 2.4.dp.toPx()

        // Three W glyphs across the upper row
        val wTop = h * 0.18f
        val wBottom = h * 0.44f
        val wMidPeak = h * 0.26f
        val wStarts = listOf(w * 0.06f, w * 0.37f, w * 0.68f)
        val wSpan = w * 0.26f

        wStarts.forEach { startX ->
            val path = Path().apply {
                moveTo(startX, wTop)
                lineTo(startX + wSpan * 0.25f, wBottom)
                lineTo(startX + wSpan * 0.50f, wMidPeak)
                lineTo(startX + wSpan * 0.75f, wBottom)
                lineTo(startX + wSpan, wTop)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = wStroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Bold X glyph centered below the three W's
        val xLeft = w * 0.35f
        val xRight = w * 0.65f
        val xTop = h * 0.56f
        val xBottom = h * 0.86f

        drawLine(
            color = color,
            start = Offset(xLeft, xTop),
            end = Offset(xRight, xBottom),
            strokeWidth = xStroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(xRight, xTop),
            end = Offset(xLeft, xBottom),
            strokeWidth = xStroke,
            cap = StrokeCap.Round
        )
    }
}
