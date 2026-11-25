package com.android.abdullah.voicerecorder.mp3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.android.abdullah.voicerecorder.mp3.model.Mp3Bitrate
import com.android.abdullah.voicerecorder.mp3.model.Mp3ChannelMode
import com.android.abdullah.voicerecorder.mp3.model.Mp3SampleRate
import com.android.abdullah.voicerecorder.mp3.model.RecordingQualityConfig
import com.android.abdullah.voicerecorder.mp3.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recorderDataStore: DataStore<Preferences> by preferencesDataStore(name = "mp3_recorder_prefs")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BITRATE_KBPS = intPreferencesKey("bitrate_kbps")
        val SAMPLE_RATE_HZ = intPreferencesKey("sample_rate_hz")
        val CHANNELS = intPreferencesKey("channels")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
    }

    val qualityConfigFlow: Flow<RecordingQualityConfig> = context.recorderDataStore.data.map { prefs ->
        RecordingQualityConfig(
            bitrate = Mp3Bitrate.fromKbps(prefs[Keys.BITRATE_KBPS] ?: Mp3Bitrate.KBPS_192.kbps),
            sampleRate = Mp3SampleRate.fromHz(prefs[Keys.SAMPLE_RATE_HZ] ?: Mp3SampleRate.HZ_44100.hz),
            channelMode = Mp3ChannelMode.fromChannels(prefs[Keys.CHANNELS] ?: Mp3ChannelMode.MONO.channels)
        )
    }

    val sortOrderFlow: Flow<SortOrder> = context.recorderDataStore.data.map { prefs ->
        val saved = prefs[Keys.SORT_ORDER]
        SortOrder.entries.find { it.name == saved } ?: SortOrder.NEWEST
    }

    val githubRepoFlow: Flow<String> = context.recorderDataStore.data.map { prefs ->
        prefs[Keys.GITHUB_REPO].orEmpty()
    }

    suspend fun updateBitrate(bitrate: Mp3Bitrate) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.BITRATE_KBPS] = bitrate.kbps
        }
    }

    suspend fun updateSampleRate(sampleRate: Mp3SampleRate) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.SAMPLE_RATE_HZ] = sampleRate.hz
        }
    }

    suspend fun updateChannelMode(channelMode: Mp3ChannelMode) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.CHANNELS] = channelMode.channels
        }
    }

    suspend fun updateSortOrder(sortOrder: SortOrder) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.SORT_ORDER] = sortOrder.name
        }
    }

    suspend fun updateGithubRepo(repo: String) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.GITHUB_REPO] = repo.trim()
        }
    }
}
