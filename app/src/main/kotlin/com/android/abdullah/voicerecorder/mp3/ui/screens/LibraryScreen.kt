package com.android.abdullah.voicerecorder.mp3.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.model.Mp3Recording
import com.android.abdullah.voicerecorder.mp3.model.PlaybackState
import com.android.abdullah.voicerecorder.mp3.model.SortOrder
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecordContainer
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioOutline
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioOutlineSubtle
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurface
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurfaceElevated
import com.android.abdullah.voicerecorder.mp3.ui.theme.StudioSurfaceHigh
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils

@Composable
fun LibraryScreen(
    recordings: List<Mp3Recording>,
    searchQuery: String,
    sortOrder: SortOrder,
    playbackState: PlaybackState,
    isConvertingMp3: Boolean = false,
    onSearchQueryChange: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onTogglePlay: (Mp3Recording) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipBy: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onRename: (Mp3Recording, String) -> Unit,
    onShare: (Mp3Recording) -> Unit,
    onExport: (Mp3Recording) -> Unit,
    onDelete: (Mp3Recording) -> Unit,
    onConvertToMp3: (() -> Unit)? = null,
    onGoToRecord: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var recordingToRename by remember { mutableStateOf<Mp3Recording?>(null) }
    var recordingToDelete by remember { mutableStateOf<Mp3Recording?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search recordings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
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
                    .testTag("library_search_input")
            )

            // Sort chips + Convert to MP3 action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(SortOrder.entries) { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { onSortOrderChange(order) },
                            label = {
                                Text(
                                    text = order.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CoralRecordContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("sort_${order.name.lowercase()}")
                        )
                    }
                }
                if (onConvertToMp3 != null) {
                    OutlinedButton(
                        onClick = onConvertToMp3,
                        enabled = !isConvertingMp3,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("convert_to_mp3_button")
                    ) {
                        if (isConvertingMp3) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CoralRecord
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = "Convert audio to MP3",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConvertingMp3) "Converting…" else "Convert MP3",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (recordings.isEmpty()) {
                EmptyLibraryState(
                    hasSearchFilter = searchQuery.isNotBlank(),
                    onClearSearch = { onSearchQueryChange("") },
                    onGoToRecord = onGoToRecord,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = recordings,
                        key = { it.id }
                    ) { recording ->
                        val isActive = playbackState.activeRecordingId == recording.id
                        RecordingItemCard(
                            recording = recording,
                            isActive = isActive,
                            playbackState = playbackState,
                            onTogglePlay = { onTogglePlay(recording) },
                            onSeekTo = onSeekTo,
                            onSkipBy = onSkipBy,
                            onCycleSpeed = onCycleSpeed,
                            onRenameClick = { recordingToRename = recording },
                            onShareClick = { onShare(recording) },
                            onExportClick = { onExport(recording) },
                            onDeleteClick = { recordingToDelete = recording }
                        )
                    }
                }
            }
        }
    }

    recordingToRename?.let { target ->
        RenameRecordingDialog(
            currentName = target.displayName,
            onDismiss = { recordingToRename = null },
            onConfirm = { newName ->
                onRename(target, newName)
                recordingToRename = null
            }
        )
    }

    recordingToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            containerColor = StudioSurfaceElevated,
            title = {
                Text("Delete MP3 Recording?", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text(
                    "\"${target.fileName}\" will be permanently removed from Voice Recorder.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target)
                        recordingToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { recordingToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RecordingItemCard(
    recording: Mp3Recording,
    isActive: Boolean,
    playbackState: PlaybackState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipBy: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onRenameClick: () -> Unit,
    onShareClick: () -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isActive) StudioSurfaceElevated else StudioSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) CoralRecord.copy(alpha = 0.55f) else StudioOutlineSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_card_${recording.fileName}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Play / Pause circle button
                Surface(
                    onClick = onTogglePlay,
                    shape = CircleShape,
                    color = if (isActive && playbackState.isPlaying) CoralRecord else StudioSurfaceHigh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("play_button_${recording.fileName}")
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isActive && playbackState.isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (isActive && playbackState.isPlaying) {
                                "Pause ${recording.displayName}"
                            } else {
                                "Play ${recording.displayName}"
                            },
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Title & metadata
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = recording.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CoralRecordContainer
                        ) {
                            Text(
                                text = if (recording.bitrateKbps != null) {
                                    "MP3 ${recording.bitrateKbps}k"
                                } else {
                                    "MP3"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "${FormatUtils.formatDuration(recording.durationMs)} · ${FormatUtils.formatFileSize(recording.sizeBytes)} · ${FormatUtils.formatDateTime(recording.createdAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Overflow menu
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("menu_button_${recording.fileName}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Recording options"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = StudioSurfaceElevated
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = {
                                Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share MP3") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onShareClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Save to Music") },
                            leadingIcon = {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onExportClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = CoralRecord) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = CoralRecord
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }

            // Expandable Inline MP3 Player Controls
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val totalMs = playbackState.durationMs.coerceAtLeast(recording.durationMs).coerceAtLeast(1L)
                val progress = (playbackState.positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Slider(
                        value = progress,
                        onValueChange = { fraction ->
                            onSeekTo((fraction * totalMs).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = CoralRecord,
                            activeTrackColor = CoralRecord,
                            inactiveTrackColor = StudioOutline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playback_slider_${recording.fileName}")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(playbackState.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { onSkipBy(-10_000L) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Replay10,
                                    contentDescription = "Rewind 10 seconds"
                                )
                            }

                            Surface(
                                onClick = onCycleSpeed,
                                shape = RoundedCornerShape(8.dp),
                                color = StudioSurfaceHigh,
                                border = BorderStroke(1.dp, StudioOutline),
                                modifier = Modifier.testTag("speed_button")
                            ) {
                                Text(
                                    text = "${playbackState.speed}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            IconButton(
                                onClick = { onSkipBy(10_000L) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Forward10,
                                    contentDescription = "Forward 10 seconds"
                                )
                            }
                        }

                        Text(
                            text = FormatUtils.formatDuration(totalMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    hasSearchFilter: Boolean,
    onClearSearch: () -> Unit,
    onGoToRecord: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasSearchFilter) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_recordings_1795425772133),
                contentDescription = "Studio microphone illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(170.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.empty_library_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (onGoToRecord != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onGoToRecord,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
                    modifier = Modifier.testTag("empty_go_record_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Record First MP3")
                }
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No matching MP3 files",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = onClearSearch) {
                Text("Clear search")
            }
        }
    }
}

@Composable
private fun RenameRecordingDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurfaceElevated,
        title = {
            Text("Rename MP3 File", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                suffix = {
                    Text(
                        text = ".mp3",
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralRecord
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CoralRecord,
                    unfocusedBorderColor = StudioOutline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_recording_input")
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameInput) },
                enabled = nameInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
                modifier = Modifier.testTag("confirm_rename_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
