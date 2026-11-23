package com.android.abdullah.voicerecorder.mp3.audio

/**
 * Native FFmpeg (libmp3lame) MP3 encoder backed by libffmpeg.so.
 */
class Mp3Encoder(
    private val sampleRateHz: Int,
    private val channels: Int,
    private val bitrateKbps: Int,
    private val quality: Int = 4
) {
    private var nativeHandle: Long = 0L
    private var isClosed = false

    init {
        check(FfmpegMp3Native.isLoaded) {
            "Failed to load native libffmpeg.so: ${FfmpegMp3Native.loadError?.message}"
        }
        nativeHandle = FfmpegMp3Native.nativeCreateEncoder(
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitrateKbps = bitrateKbps,
            quality = quality
        )
        require(nativeHandle != 0L) {
            "Failed to initialize FFmpeg MP3 encoder (${sampleRateHz}Hz, ch=$channels, ${bitrateKbps}kbps)"
        }
    }

    fun encodePcm16(pcmSamples: ShortArray, sampleCount: Int, outMp3Buffer: ByteArray): Int {
        if (isClosed || nativeHandle == 0L || sampleCount <= 0) return 0
        val encoded = FfmpegMp3Native.nativeEncodePcm16(
            handle = nativeHandle,
            pcmSamples = pcmSamples,
            sampleCount = sampleCount,
            outMp3Buffer = outMp3Buffer
        )
        check(encoded >= 0) { "FFmpeg MP3 encoding error ($encoded)" }
        return encoded
    }

    fun flush(outMp3Buffer: ByteArray): Int {
        if (isClosed || nativeHandle == 0L) return 0
        val flushed = FfmpegMp3Native.nativeFlushEncoder(
            handle = nativeHandle,
            outMp3Buffer = outMp3Buffer
        )
        return flushed.coerceAtLeast(0)
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            val handle = nativeHandle
            nativeHandle = 0L
            if (handle != 0L) {
                FfmpegMp3Native.nativeDestroyEncoder(handle)
            }
        }
    }
}
