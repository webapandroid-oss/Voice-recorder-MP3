package com.android.abdullah.voicerecorder.mp3.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.android.abdullah.voicerecorder.mp3.MainActivity
import com.android.abdullah.voicerecorder.mp3.R
import com.android.abdullah.voicerecorder.mp3.data.GitHubUpdateRepository
import com.android.abdullah.voicerecorder.mp3.data.ReleaseCheckResult
import com.android.abdullah.voicerecorder.mp3.model.GitHubReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object UpdateCheckEventBus {
    private val _updates = MutableSharedFlow<GitHubReleaseInfo>(extraBufferCapacity = 4)
    val updates: SharedFlow<GitHubReleaseInfo> = _updates.asSharedFlow()

    fun emit(release: GitHubReleaseInfo) {
        _updates.tryEmit(release)
    }
}

class UpdateCheckJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            try {
                val updateRepository = GitHubUpdateRepository(applicationContext)
                val result = updateRepository.checkLatestRelease(
                    GitHubUpdateRepository.TRUSTED_GITHUB_REPO
                )
                if (result is ReleaseCheckResult.UpdateAvailable) {
                    UpdateCheckEventBus.emit(result.release)
                    maybeNotifyUpdateAvailable(result.release)
                }
            } catch (_: Throwable) {
                // Silent background check; next 1-minute run will retry cleanly
            } finally {
                if (params?.jobId == FAST_POLL_JOB_ID) {
                    scheduleFastPoll(applicationContext)
                }
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        checkJob?.cancel()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun maybeNotifyUpdateAvailable(release: GitHubReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTag = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)
        if (lastTag == release.tagName) return

        ensureNotificationChannel()

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.update_notification_title))
            .setContentText(
                applicationContext.getString(
                    R.string.update_notification_body,
                    release.versionName
                )
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, release.tagName).apply()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.update_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = applicationContext.getString(R.string.update_notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val JOB_ID = 4103
        private const val FAST_POLL_JOB_ID = 4105
        private const val NOTIFICATION_ID = 4104
        private const val CHANNEL_ID = "app_updates_channel"
        private const val PREFS_NAME = "update_bg_job_prefs"
        private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
        private const val PERIODIC_INTERVAL_MS = 15L * 60L * 1000L
        private const val FAST_POLL_INTERVAL_MS = 60L * 1000L

        fun schedulePeriodicChecks(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                    ?: return
                if (scheduler.getPendingJob(JOB_ID) == null) {
                    val component = ComponentName(context, UpdateCheckJobService::class.java)
                    val jobInfo = JobInfo.Builder(JOB_ID, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(PERIODIC_INTERVAL_MS)
                        .setPersisted(false)
                        .build()
                    scheduler.schedule(jobInfo)
                }
                if (scheduler.getPendingJob(FAST_POLL_JOB_ID) == null) {
                    scheduleFastPoll(context)
                }
            }
        }

        fun scheduleFastPoll(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                    ?: return
                val component = ComponentName(context, UpdateCheckJobService::class.java)
                val jobInfo = JobInfo.Builder(FAST_POLL_JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(FAST_POLL_INTERVAL_MS)
                    .setOverrideDeadline(FAST_POLL_INTERVAL_MS * 2L)
                    .setPersisted(false)
                    .build()
                scheduler.schedule(jobInfo)
            }
        }
    }
}
