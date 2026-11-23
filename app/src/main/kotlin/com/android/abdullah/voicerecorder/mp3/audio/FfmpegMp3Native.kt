package com.android.abdullah.voicerecorder.mp3.audio

/**
 * JNI interface backed by custom MP3-only libffmpeg.so (FFmpeg + libmp3lame),
 * built for 64-bit (arm64-v8a, x86_64) and 32-bit (armeabi-v7a, x86) ABIs.
 */
object FfmpegMp3Native {

    val isLoaded: Boolean
    val loadError: Throwable?

    init {
        var loaded = false
        var err: Throwable? = null
        try {
            System.loadLibrary("ffmpeg")
            loaded = true
        } catch (t: Throwable) {
            err = t
        }
        isLoaded = loaded
        loadError = err
    }

    fun capabilityInfo(): String {
        if (!isLoaded) return "Native libffmpeg.so unavailable: ${loadError?.message}"
        return nativeGetCapabilityInfo()
    }

    fun convertFileToMp3(
        inputPath: String,
        outputPath: String,
        bitrateKbps: Int = 192,
        targetSampleRate: Int = 44100,
        targetChannels: Int = 1
    ): Result<Unit> {
        if (!isLoaded) {
            return Result.failure(
                IllegalStateException("libffmpeg.so could not be loaded", loadError)
            )
        }
        val code = nativeConvertToMp3(
            inputPath = inputPath,
            outputPath = outputPath,
            bitrateKbps = bitrateKbps,
            targetSampleRate = targetSampleRate,
            targetChannels = targetChannels
        )
        return if (code == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("FFmpeg MP3 conversion failed (code=$code)"))
        }
    }

    external fun nativeGetCapabilityInfo(): String

    external fun nativeCreateEncoder(
        sampleRateHz: Int,
        channels: Int,
        bitrateKbps: Int,
        quality: Int
    ): Long

    external fun nativeEncodePcm16(
        handle: Long,
        pcmSamples: ShortArray,
        sampleCount: Int,
        outMp3Buffer: ByteArray
    ): Int

    external fun nativeFlushEncoder(
        handle: Long,
        outMp3Buffer: ByteArray
    ): Int

    external fun nativeDestroyEncoder(handle: Long)

    external fun nativeConvertToMp3(
        inputPath: String,
        outputPath: String,
        bitrateKbps: Int,
        targetSampleRate: Int,
        targetChannels: Int
    ): Int
}
