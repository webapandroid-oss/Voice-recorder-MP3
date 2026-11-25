package com.android.abdullah.voicerecorder.mp3.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface InstallSessionEvent {
    data class PendingUserAction(val confirmIntent: Intent) : InstallSessionEvent
    data object Succeeded : InstallSessionEvent
    data object Aborted : InstallSessionEvent
    data class Failed(val status: Int, val message: String) : InstallSessionEvent
}

object InstallSessionEventBus {
    private val _events = MutableSharedFlow<InstallSessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallSessionEvent> = _events.asSharedFlow()

    fun emit(event: InstallSessionEvent) {
        _events.tryEmit(event)
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().trim()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java
                )
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    InstallSessionEventBus.emit(InstallSessionEvent.PendingUserAction(confirmIntent))
                } else {
                    InstallSessionEventBus.emit(
                        InstallSessionEvent.Failed(
                            status = status,
                            message = "Unable to display system install confirmation."
                        )
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                InstallSessionEventBus.emit(InstallSessionEvent.Succeeded)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                InstallSessionEventBus.emit(InstallSessionEvent.Aborted)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(
                        status = status,
                        message = "Installation conflicted with the installed build (different signing key). Uninstall the existing build first, then install again."
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(
                        status = status,
                        message = "This update package is incompatible with your device."
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(
                        status = status,
                        message = "Not enough storage space to install the update."
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(
                        status = status,
                        message = "Installation was blocked by system settings or security policy."
                    )
                )
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(
                        status = status,
                        message = "The downloaded APK package appears invalid or corrupted."
                    )
                )
            }

            else -> {
                val detail = rawMessage.ifBlank { "Package installation failed (code $status)." }
                InstallSessionEventBus.emit(
                    InstallSessionEvent.Failed(status = status, message = detail)
                )
            }
        }
    }
}
