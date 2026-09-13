package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.UpdateChecker
import com.example.util.VersionComparator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * State of the Settings > App Update section.
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class UpdateAvailable(val release: UpdateChecker.AppRelease) : UpdateUiState
    data class Downloading(val progress: Int) : UpdateUiState
    data class Downloaded(val apkFile: File, val version: String) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Drives the in-app update check. Every network call is user-triggered and one-shot, so the screen
 * costs no background battery while it is not in use.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    // Comment: Read the installed version from BuildConfig, which is parsed from versionHistory.txt at
    // build time — the same value the release tag is compared against.
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    // Comment: Track the running download so the user can cancel it from the UI
    private var downloadJob: Job? = null

    // Comment: Fetch the latest published release once and compare it with the installed version
    fun checkForUpdates() {
        if (_uiState.value is UpdateUiState.Checking || _uiState.value is UpdateUiState.Downloading) return
        _uiState.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = UpdateChecker.fetchLatestRelease()
            val release = result.getOrNull()
            _uiState.value = when {
                release == null -> UpdateUiState.Failed(
                    result.exceptionOrNull()?.message ?: "Couldn't check for updates."
                )
                VersionComparator.isNewer(release.version, currentVersion) ->
                    UpdateUiState.UpdateAvailable(release)
                else -> UpdateUiState.UpToDate
            }
        }
    }

    // Comment: Download the release APK into the app cache directory, which needs no storage
    // permission and is already exposed to the FileProvider for the install intent.
    fun downloadUpdate(release: UpdateChecker.AppRelease) {
        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrBlank()) {
            _uiState.value = UpdateUiState.Failed(
                "This release has no APK attached. Use \"What's new\" to open the release page."
            )
            return
        }

        downloadJob?.cancel()
        _uiState.value = UpdateUiState.Downloading(0)
        downloadJob = viewModelScope.launch {
            val targetFile = File(
                getApplication<Application>().cacheDir,
                "swanirvor23-${release.version}.apk"
            )
            val result = UpdateChecker.downloadApk(apkUrl, targetFile) { progress ->
                // Comment: Called from the download loop's IO thread; StateFlow updates are thread-safe
                if (_uiState.value is UpdateUiState.Downloading) {
                    _uiState.value = UpdateUiState.Downloading(progress)
                }
            }
            val downloadedFile = result.getOrNull()
            _uiState.value = if (downloadedFile != null) {
                UpdateUiState.Downloaded(downloadedFile, release.version)
            } else {
                UpdateUiState.Failed(result.exceptionOrNull()?.message ?: "Download failed.")
            }
        }
    }

    // Comment: Stop an in-flight download and drop back to the idle state
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = UpdateUiState.Idle
    }

    override fun onCleared() {
        downloadJob?.cancel()
        super.onCleared()
    }
}
