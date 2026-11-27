package com.android.abdullah.voicerecorder.mp3.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.ui.theme.AmberMeter
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralSoft
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun StudioWaveformCard(
    amplitudes: List<Float>,
    currentLevelDb: Float,
    status: RecorderStatus,
    modifier: Modifier = Modifier,
    waveformHeight: Dp = 144.dp
) {
    val idlePhase = if (status == RecorderStatus.IDLE) {
        val infiniteTransition = rememberInfiniteTransition(label = "idleWave")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600),
                repeatMode = RepeatMode.Restart
            ),
            label = "idlePhase"
        )
        phase
    } else {
        0f
    }

    val borderColor = when (status) {
        RecorderStatus.RECORDING -> CoralRecord.copy(alpha = 0.28f)
        RecorderStatus.PAUSED -> AmberMeter.copy(alpha = 0.30f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(waveformHeight)
            ) {
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val idleWaveColor = MaterialTheme.colorScheme.secondary
                val baseBarColor = onSurfaceColor.copy(alpha = 0.80f)
                val pausedBarColor = AmberMeter.copy(alpha = 0.55f)
                val idleBarColor = idleWaveColor.copy(alpha = 0.30f)
                val gridColor = onSurfaceColor.copy(alpha = 0.08f)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f

                    drawLine(
                        color = gridColor,
                        start = Offset(0f, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = 1.dp.toPx()
                    )

                    val barCount = 56
                    val slotWidth = width / barCount
                    val barWidth = (slotWidth * 0.56f).coerceAtLeast(2.5.dp.toPx())
                    val minHalfHeight = 3.dp.toPx()
                    val maxBarHalfHeight = height * 0.44f
                    val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
                    val xOffset = (slotWidth - barWidth) / 2f

                    val ampSize = amplitudes.size
                    val startIndex = (ampSize - barCount).coerceAtLeast(0)
                    val padSlots = (barCount - ampSize).coerceAtLeast(0)

                    for (i in 0 until barCount) {
                        val sample = when (status) {
                            RecorderStatus.IDLE -> {
                                val wave = (sin(idlePhase + i * 0.22f) + 1f) * 0.5f
                                0.05f + wave * 0.06f
                            }
                            else -> {
                                val srcIdx = i - padSlots + startIndex
                                if (srcIdx in 0 until ampSize) {
                                    amplitudes[srcIdx].coerceIn(0.03f, 1f)
                                } else {
                                    0.03f
                                }
                            }
                        }

                        val halfHeight = (sample * maxBarHalfHeight).coerceAtLeast(minHalfHeight)
                        val x = i * slotWidth + xOffset
                        val topY = centerY - halfHeight
                        val barHeight = halfHeight * 2f

                        val barColor = when {
                            status == RecorderStatus.IDLE -> idleBarColor
                            status == RecorderStatus.PAUSED -> pausedBarColor
                            sample > 0.85f -> CoralRecord
                            sample > 0.58f -> CoralSoft
                            else -> baseBarColor
                        }

                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, topY),
                            size = Size(barWidth, barHeight),
                            cornerRadius = corner
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VuLevelMeter(
                    levelDb = if (status == RecorderStatus.RECORDING) currentLevelDb else -60f,
                    modifier = Modifier.weight(1f)
                )
                val levelText = when (status) {
                    RecorderStatus.RECORDING -> "${currentLevelDb.roundToInt()} dB"
                    RecorderStatus.PAUSED -> "PAUSED"
                    RecorderStatus.FINALIZING -> "MP3"
                    RecorderStatus.IDLE -> "READY"
                }
                Text(
                    text = levelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        RecorderStatus.RECORDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        RecorderStatus.PAUSED -> AmberMeter
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun VuLevelMeter(
    levelDb: Float,
    modifier: Modifier = Modifier
) {
    val normalized = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val segmentCount = 28

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val gap = 3.dp.toPx()
        val segWidth = (size.width - gap * (segmentCount - 1)) / segmentCount
        val activeSegments = (normalized * segmentCount).roundToInt()
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val segSize = Size(segWidth, size.height)

        for (i in 0 until segmentCount) {
            val isActive = i < activeSegments
            val fraction = i.toFloat() / segmentCount
            val baseColor = when {
                fraction >= 0.85f -> CoralRecord
                fraction >= 0.65f -> AmberMeter
                else -> EmeraldClean
            }
            val color = if (isActive) baseColor else baseColor.copy(alpha = 0.14f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (segWidth + gap), 0f),
                size = segSize,
                cornerRadius = corner
            )
        }
    }
}
