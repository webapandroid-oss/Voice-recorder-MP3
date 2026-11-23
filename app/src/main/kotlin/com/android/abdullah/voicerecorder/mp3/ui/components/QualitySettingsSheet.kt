package com.android.abdullah.voicerecorder.mp3.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecordContainer
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioOutline
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurface
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurfaceElevated
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySettingsSheet(
    config: RecordingQualityConfig,
    isRecordingActive: Boolean,
    onBitrateSelected: (Mp3Bitrate) -> Unit,
    onSampleRateSelected: (Mp3SampleRate) -> Unit,
    onChannelModeSelected: (Mp3ChannelMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MP3 Encoding Quality",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Applies exclusively to .mp3 voice capture",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CoralRecordContainer,
                    border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = String.format(Locale.US, "~%.2f MB/min", config.estimatedMbPerMinute),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (isRecordingActive) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Stop the active recording to switch MP3 encoder parameters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyanAccent,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Bitrate selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MP3 BITRATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mp3Bitrate.entries.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { bitrate ->
                                val selected = config.bitrate == bitrate
                                Surface(
                                    onClick = { if (!isRecordingActive) onBitrateSelected(bitrate) },
                                    enabled = !isRecordingActive,
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) CoralRecordContainer else StudioSurfaceElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) CoralRecord else StudioOutline
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("bitrate_${bitrate.kbps}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = bitrate.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                        Text(
                                            text = bitrate.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sample rate selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SAMPLE RATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Mp3SampleRate.entries.forEach { rate ->
                        val selected = config.sampleRate == rate
                        FilterChip(
                            selected = selected,
                            onClick = { if (!isRecordingActive) onSampleRateSelected(rate) },
                            enabled = !isRecordingActive,
                            label = {
                                Text(
                                    text = rate.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CoralRecordContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("samplerate_${rate.hz}")
                        )
                    }
                }
            }

            // Channel mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CHANNELS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Mp3ChannelMode.entries.forEach { mode ->
                        val selected = config.channelMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { if (!isRecordingActive) onChannelModeSelected(mode) },
                            enabled = !isRecordingActive,
                            label = {
                                Text(
                                    text = if (mode == Mp3ChannelMode.MONO) "Mono (Voice)" else "Stereo",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CoralRecordContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("channel_${mode.channels}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
