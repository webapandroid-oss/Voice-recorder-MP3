package com.android.abdullah.voicerecorder.mp3.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatTimerWithTenths(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val tenths = ((safeMs % 1000L) / 100L).toInt()
        val hours = totalSeconds / 3600L
        val minutes = ((totalSeconds % 3600L) / 60L).toInt()
        val seconds = (totalSeconds % 60L).toInt()
        return buildString(11) {
            if (hours > 0L) {
                if (hours < 10L) append('0')
                append(hours).append(':')
            }
            if (minutes < 10) append('0')
            append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds).append('.').append(tenths)
        }
    }

    fun formatDuration(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = ((totalSeconds % 3600L) / 60L).toInt()
        val seconds = (totalSeconds % 60L).toInt()
        return buildString(8) {
            if (hours > 0L) {
                append(hours).append(':')
            }
            if (minutes < 10) append('0')
            append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun formatDateTime(epochMillis: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US)
        return sdf.format(Date(epochMillis))
    }

    fun formatDefaultRecordingName(date: Date = Date()): String {
        val timestamp = SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.US).format(date)
        return "Recording_$timestamp"
    }
}
