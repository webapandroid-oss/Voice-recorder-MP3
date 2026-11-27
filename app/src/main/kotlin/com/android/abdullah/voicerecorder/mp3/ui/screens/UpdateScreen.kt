package com.android.abdullah.voicerecorder.mp3.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import com.android.abdullah.voicerecorder.mp3.model.UpdateUiState
import com.android.abdullah.voicerecorder.mp3.ui.theme.AmberMeter
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    updateState: UpdateUiState,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onRequestUnknownSourcePermission: () -> Unit,
    onRefreshInstallPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshInstallPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.update_screen_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("update_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.update_later_button)
                        )
                    }
                },
                actions = {
                    if (updateState !is UpdateUiState.Checking &&
                        updateState !is UpdateUiState.Downloading &&
                        updateState !is UpdateUiState.Installing
                    ) {
                        IconButton(
                            onClick = onCheckAgain,
                            modifier = Modifier.testTag("update_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.update_retry_button)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (updateState) {
                    is UpdateUiState.Idle,
                    is UpdateUiState.Checking -> {
                        CheckingUpdatesCard()
                    }

                    is UpdateUiState.UpToDate -> {
                        UpToDateCard(
                            currentVersion = updateState.currentVersion,
                            latestVersion = updateState.latestVersion,
                            onCheckAgain = onCheckAgain,
                            onBack = onBack
                        )
                    }

                    is UpdateUiState.Available -> {
                        ReleaseUpdateContent(
                            currentVersion = updateState.currentVersion,
                            release = updateState.release,
                            updateState = updateState,
                            onDownloadUpdate = onDownloadUpdate,
                            onCancelDownload = onCancelDownload,
                            onInstallNow = onInstallNow,
                            onRequestUnknownSourcePermission = onRequestUnknownSourcePermission,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    is UpdateUiState.Downloading -> {
                        ReleaseUpdateContent(
                            currentVersion = updateState.currentVersion,
                            release = updateState.release,
                            updateState = updateState,
                            onDownloadUpdate = onDownloadUpdate,
                            onCancelDownload = onCancelDownload,
                            onInstallNow = onInstallNow,
                            onRequestUnknownSourcePermission = onRequestUnknownSourcePermission,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        ReleaseUpdateContent(
                            currentVersion = updateState.currentVersion,
                            release = updateState.release,
                            updateState = updateState,
                            onDownloadUpdate = onDownloadUpdate,
                            onCancelDownload = onCancelDownload,
                            onInstallNow = onInstallNow,
                            onRequestUnknownSourcePermission = onRequestUnknownSourcePermission,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    is UpdateUiState.Installing -> {
                        ReleaseUpdateContent(
                            currentVersion = updateState.currentVersion,
                            release = updateState.release,
                            updateState = updateState,
                            onDownloadUpdate = onDownloadUpdate,
                            onCancelDownload = onCancelDownload,
                            onInstallNow = onInstallNow,
                            onRequestUnknownSourcePermission = onRequestUnknownSourcePermission,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    is UpdateUiState.Error -> {
                        if (updateState.release != null) {
                            ReleaseUpdateContent(
                                currentVersion = updateState.currentVersion,
                                release = updateState.release,
                                updateState = updateState,
                                onDownloadUpdate = onDownloadUpdate,
                                onCancelDownload = onCancelDownload,
                                onInstallNow = onInstallNow,
                                onRequestUnknownSourcePermission = onRequestUnknownSourcePermission,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            UpdateErrorCard(
                                message = updateState.message,
                                onCheckAgain = onCheckAgain,
                                onBack = onBack
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckingUpdatesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = stringResource(R.string.update_checking_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun UpToDateCard(
    currentVersion: String,
    latestVersion: String?,
    onCheckAgain: () -> Unit,
    onBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = EmeraldClean,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.update_up_to_date_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.update_up_to_date_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(999.dp)
            ) {
                val versionSummary = if (!latestVersion.isNullOrBlank()) {
                    "Installed: v$currentVersion • Latest: v$latestVersion"
                } else {
                    "Installed: v$currentVersion"
                }
                Text(
                    text = versionSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.update_later_button))
                }
                Button(
                    onClick = onCheckAgain,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("update_check_again_button")
                ) {
                    Text(stringResource(R.string.update_retry_button))
                }
            }
        }
    }
}

@Composable
private fun UpdateErrorCard(
    message: String,
    onCheckAgain: () -> Unit,
    onBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Could not check updates",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.update_later_button))
                }
                Button(
                    onClick = onCheckAgain,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.update_retry_button))
                }
            }
        }
    }
}

@Composable
private fun ReleaseUpdateContent(
    currentVersion: String,
    release: GitHubReleaseInfo,
    updateState: UpdateUiState,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onRequestUnknownSourcePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Hero release header card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                CyanAccent.copy(alpha = 0.14f),
                                MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = EmeraldClean.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.NewReleases,
                                    contentDescription = null,
                                    tint = EmeraldClean,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.update_available_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldClean,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (release.apkSizeBytes > 0L) {
                            Text(
                                text = FormatUtils.formatFileSize(release.apkSizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = release.releaseName.ifBlank { "v${release.versionName}" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("update_release_title")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "v$currentVersion",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "v${release.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("update_target_version")
                            )
                        }
                    }
                    if (release.apkAssetName.isNotBlank()) {
                        Text(
                            text = release.apkAssetName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Scrollable full release notes card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.update_release_notes_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = release.releaseNotes.ifBlank {
                                stringResource(R.string.update_no_notes)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("update_release_notes_body")
                        )
                    }
                }
            }
        }

        // Download progress screen panel
        AnimatedVisibility(visible = updateState is UpdateUiState.Downloading) {
            val downloading = updateState as? UpdateUiState.Downloading
            if (downloading != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("update_download_progress_card"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = release.apkAssetName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val pctText = downloading.progress?.let {
                                "${(it * 100f).roundToInt().coerceIn(0, 100)}%"
                            } ?: "…"
                            Text(
                                text = pctText,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (downloading.progress != null) {
                            LinearProgressIndicator(
                                progress = { downloading.progress.coerceIn(0f, 1f) },
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .testTag("update_progress_bar")
                            )
                        } else {
                            LinearProgressIndicator(
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .testTag("update_progress_bar")
                            )
                        }

                        val bytesSummary = if (downloading.totalBytes > 0L) {
                            "${FormatUtils.formatFileSize(downloading.downloadedBytes)} / ${FormatUtils.formatFileSize(downloading.totalBytes)}"
                        } else {
                            FormatUtils.formatFileSize(downloading.downloadedBytes)
                        }
                        Text(
                            text = bytesSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Unknown sources permission hint
        AnimatedVisibility(
            visible = updateState is UpdateUiState.ReadyToInstall &&
                updateState.needsUnknownSourcePermission
        ) {
            Surface(
                color = AmberMeter.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, AmberMeter.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("update_permission_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AdminPanelSettings,
                        contentDescription = null,
                        tint = AmberMeter
                    )
                    Text(
                        text = stringResource(R.string.update_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Signing certificate mismatch hint (e.g. debug build -> release APK)
        AnimatedVisibility(
            visible = updateState is UpdateUiState.ReadyToInstall &&
                updateState.signatureMismatch
        ) {
            Surface(
                color = AmberMeter.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, AmberMeter.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("update_signature_mismatch_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = AmberMeter
                    )
                    Text(
                        text = stringResource(R.string.update_signature_mismatch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Inline error message if download failed
        if (updateState is UpdateUiState.Error) {
            Text(
                text = updateState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Single morphing primary button + optional secondary Cancel when downloading
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (updateState) {
                is UpdateUiState.Downloading -> {
                    val pct = updateState.progress?.let {
                        " (${(it * 100f).roundToInt().coerceIn(0, 100)}%)"
                    }.orEmpty()
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("update_primary_action_button")
                    ) {
                        Text(stringResource(R.string.update_downloading_button) + pct)
                    }

                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("update_cancel_button")
                    ) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    if (updateState.needsUnknownSourcePermission) {
                        Button(
                            onClick = onRequestUnknownSourcePermission,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberMeter,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("update_primary_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AdminPanelSettings,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.update_allow_unknown_button),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Button(
                            onClick = onInstallNow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldClean,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("update_primary_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.InstallMobile,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.update_install_now_button),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                is UpdateUiState.Installing -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("update_primary_action_button")
                    ) {
                        CircularProgressIndicator(
                            color = CyanAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.update_installing_button),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                else -> {
                    Button(
                        onClick = onDownloadUpdate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("update_primary_action_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_download_button),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
