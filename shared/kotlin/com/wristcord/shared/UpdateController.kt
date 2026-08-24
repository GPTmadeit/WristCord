package com.wristcord.shared

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives one device's update: check GitHub, download, verify the signature, hand it to the
 * package installer. Both apps use this; only the surrounding UI differs.
 */
class UpdateController(
    context: Context,
    private val currentVersion: String,
    private val assetMarker: String,
) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<Updater.Status>(Updater.Status.Idle)
    val status: StateFlow<Updater.Status> = _status

    init {
        InstallResultReceiver.listener = { _, error ->
            if (error != null) _status.value = Updater.Status.Failed(error)
        }
    }

    val currentVersionLabel: String get() = currentVersion

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = Updater.Status.Checking
            Updater.check(currentVersion, assetMarker)
                .onSuccess { release ->
                    _status.value = if (release == null) {
                        Updater.Status.UpToDate(currentVersion)
                    } else {
                        Updater.Status.Available(release)
                    }
                }
                .onFailure { _status.value = Updater.Status.Failed(it.message ?: "Update check failed") }
        }
    }

    fun install(release: Updater.Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!Updater.canRequestInstalls(app)) {
                _status.value = Updater.Status.Failed(
                    "Allow WristCord to install apps first, then try again"
                )
                return@launch
            }

            _status.value = Updater.Status.Downloading(release, 0)
            val file = Updater.download(app, release) { percent ->
                _status.value = Updater.Status.Downloading(release, percent)
            }.getOrElse {
                _status.value = Updater.Status.Failed(it.message ?: "Download failed")
                return@launch
            }

            _status.value = Updater.Status.Verifying(release)
            if (!Updater.signedBySameKey(app, file)) {
                file.delete()
                // Refusing here rather than letting the installer reject it means the user gets
                // a reason. It also means we never stage an APK that is not a build of this app.
                _status.value = Updater.Status.Failed(
                    "Downloaded APK is signed with a different key - refusing to install"
                )
                return@launch
            }

            _status.value = Updater.Status.Installing(release)
            Updater.install(app, file).onFailure {
                _status.value = Updater.Status.Failed(it.message ?: "Could not start the installer")
            }
        }
    }

    fun dismissError() {
        if (_status.value is Updater.Status.Failed) _status.value = Updater.Status.Idle
    }

    /** One-line summary for a settings row. */
    fun summary(status: Updater.Status): String = when (status) {
        is Updater.Status.Idle -> "Version $currentVersion"
        is Updater.Status.Checking -> "Checking..."
        is Updater.Status.UpToDate -> "Up to date (${status.current})"
        is Updater.Status.Available -> "${status.release.tag} available"
        is Updater.Status.Downloading -> "Downloading ${status.percent}%"
        is Updater.Status.Verifying -> "Verifying signature"
        is Updater.Status.Installing -> "Installing"
        is Updater.Status.Failed -> status.message
    }
}
