package com.android.abdullah.voicerecorder.mp3.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.abdullah.voicerecorder.mp3.model.RecorderStatus
import com.android.abdullah.voicerecorder.mp3.ui.theme.AmberMeter
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralRecord
import com.android.abdullah.voicerecorder.mp3.ui.theme.CoralSoft
import com.android.abdullah.voicerecorder.mp3.ui.theme.CyanAccent
import com.android.abdullah.voicerecorder.mp3.ui.theme.EmeraldClean
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun StudioWaveformCard(
    amplitudes: List<Float>,
    currentLevelDb: Float,
    status: RecorderStatus,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "idleWave")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600),
            repeatMode = RepeatMode.Restart
        ),
        label = "idlePhase"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            ) {
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val idleWaveColor = MaterialTheme.colorScheme.secondary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f

                    val gridColor = onSurfaceColor.copy(alpha = 0.08f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = 1.dp.toPx()
                    )

                    val barCount = 56
                    val slotWidth = width / barCount
                    val barWidth = (slotWidth * 0.56f).coerceAtLeast(2.5.dp.toPx())
                    val maxBarHalfHeight = height * 0.44f

                    val visibleSamples = if (amplitudes.size >= barCount) {
                        amplitudes.takeLast(barCount)
                    } else {
                        List(barCount - amplitudes.size) { 0f } + amplitudes
                    }

                    for (i in 0 until barCount) {
                        val sample = when (status) {
                            RecorderStatus.IDLE -> {
                                val wave = (sin(idlePhase + i * 0.22f) + 1f) * 0.5f
                                0.05f + wave * 0.06f
                            }
                            else -> visibleSamples[i].coerceIn(0.03f, 1f)
                        }

                        val halfHeight = (sample * maxBarHalfHeight).coerceAtLeast(3.dp.toPx())
                        val x = i * slotWidth + (slotWidth - barWidth) / 2f
                        val topY = centerY - halfHeight
                        val barHeight = halfHeight * 2f

                        val barColor = when {
                            status == RecorderStatus.IDLE -> idleWaveColor.copy(alpha = 0.30f)
                            status == RecorderStatus.PAUSED -> AmberMeter.copy(alpha = 0.55f)
                            sample > 0.85f -> CoralRecord
                            sample > 0.58f -> CoralSoft
                            else -> onSurfaceColor.copy(alpha = 0.80f)
                        }

                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, topY),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }

            VuLevelMeter(
                levelDb = if (status == RecorderStatus.RECORDING) currentLevelDb else -60f
            )
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
                size = Size(segWidth, size.height),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}
