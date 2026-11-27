package com.android.abdullah.voicerecorder.mp3.model

enum class Mp3Bitrate(val kbps: Int, val label: String, val description: String) {
    KBPS_96(96, "96 kbps", "Compact Voice"),
    KBPS_128(128, "128 kbps", "Standard MP3"),
    KBPS_192(192, "192 kbps", "High Fidelity"),
    KBPS_320(320, "320 kbps", "Studio Master");

    companion object {
        fun fromKbps(kbps: Int): Mp3Bitrate = entries.find { it.kbps == kbps } ?: KBPS_192
    }
}

enum class Mp3SampleRate(val hz: Int, val label: String) {
    HZ_22050(22050, "22.05 kHz"),
    HZ_44100(44100, "44.1 kHz"),
    HZ_48000(48000, "48 kHz");

    companion object {
        fun fromHz(hz: Int): Mp3SampleRate = entries.find { it.hz == hz } ?: HZ_44100
    }
}

enum class Mp3ChannelMode(val channels: Int, val label: String) {
    MONO(1, "Mono"),
    STEREO(2, "Stereo");

    companion object {
        fun fromChannels(channels: Int): Mp3ChannelMode = entries.find { it.channels == channels } ?: MONO
    }
}

data class RecordingQualityConfig(
    val bitrate: Mp3Bitrate = Mp3Bitrate.KBPS_192,
    val sampleRate: Mp3SampleRate = Mp3SampleRate.HZ_44100,
    val channelMode: Mp3ChannelMode = Mp3ChannelMode.MONO
) {
    val estimatedMbPerMinute: Double
        get() = (bitrate.kbps * 1000.0 / 8.0 * 60.0) / (1024.0 * 1024.0)
}

enum class RecorderStatus {
    IDLE,
    RECORDING,
    PAUSED,
    FINALIZING
}

enum class SortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LONGEST("Longest"),
    LARGEST("Largest")
}

enum class StorageLocation {
    INTERNAL,
    SD_CARD
}

enum class AppThemeMode(val label: String) {
    LIGHT("Light Theme"),
    DARK("Dark Theme")
}

data class Mp3Recording(
    val id: String,
    val displayName: String,
    val fileName: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val bitrateKbps: Int? = null,
    val contentUri: String? = null,
    val storageLocation: StorageLocation = StorageLocation.INTERNAL
)

data class PlaybackState(
    val activeRecordingId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
)
