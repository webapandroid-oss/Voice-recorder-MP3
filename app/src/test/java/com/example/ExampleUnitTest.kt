package com.android.abdullah.voicerecorder.mp3

import com.android.abdullah.voicerecorder.mp3.audio.FfmpegMp3Native
import com.android.abdullah.voicerecorder.mp3.audio.Mp3Encoder
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Calendar
import kotlin.math.sin

class Mp3EncoderTest {

    @Test
    fun `default recording filename matches Recording date time format`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 14, 5, 9)
        }
        val baseName = FormatUtils.formatDefaultRecordingName(cal.time)
        assertEquals("Recording_23_11_2026_14_05_09", baseName)
    }

    @Test
    fun `quality config calculates mp3 bitrate estimate`() {
        val cfg = RecordingQualityConfig(
            bitrate = Mp3Bitrate.KBPS_192,
            sampleRate = Mp3SampleRate.HZ_44100,
            channelMode = Mp3ChannelMode.MONO
        )
        assertTrue(cfg.estimatedMbPerMinute > 1.3 && cfg.estimatedMbPerMinute < 1.5)
    }

    @Test
    fun `encode sine wave pcm16 produces valid mp3 frames when native lib is loaded`() {
        assumeTrue(
            "Native libffmpeg.so is built for Android ABIs (not host glibc JVM)",
            FfmpegMp3Native.isLoaded
        )

        val sampleRate = 44100
        val encoder = Mp3Encoder(
            sampleRateHz = sampleRate,
            channels = 1,
            bitrateKbps = 128
        )

        val pcm = ShortArray(4410) { i ->
            val angle = 2.0 * Math.PI * 440.0 * i / sampleRate
            (sin(angle) * 16000.0).toInt().toShort()
        }
        val mp3Buffer = ByteArray(16384)
        var totalBytes = 0

        val encoded = encoder.encodePcm16(pcm, pcm.size, mp3Buffer)
        assertTrue("Expected encoded MP3 bytes >= 0", encoded >= 0)
        totalBytes += encoded

        val flushed = encoder.flush(mp3Buffer)
        assertTrue("Expected flushed MP3 bytes >= 0", flushed >= 0)
        totalBytes += flushed

        encoder.close()
        assertTrue("Expected non-empty MP3 output, got $totalBytes", totalBytes > 100)
    }
}
