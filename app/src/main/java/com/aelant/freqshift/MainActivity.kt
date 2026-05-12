package com.aelant.freqshift

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.aelant.freqshift.ui.FreqShiftTheme
import com.aelant.freqshift.ui.MainScreen
import com.aelant.freqshift.ui.PermissionGateScreen

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    private val audioPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.setPermissionGranted(granted)
        if (granted) {
            vm.startService()
            requestNotificationPermissionIfNeeded()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* non-blocking — playback works either way */ }

    /**
     * SAF folder picker. On confirmation we take a persistable read grant
     * for the picked tree URI — that lets [MediaLibrary.scanFolder] keep
     * walking the tree across process death without the system revoking
     * access. Then we hand the URI to the ViewModel which scans, builds a
     * transient queue, and starts playback.
     *
     * The launcher is a no-op if the user backs out of the picker.
     */
    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        vm.playFolder(uri)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val granted = ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        vm.setPermissionGranted(granted)
        if (granted) {
            vm.startService()
            requestNotificationPermissionIfNeeded()
        }

        setContent {
            FreqShiftTheme {
                val state by vm.state.collectAsState()
                val sleepState by vm.sleepTimerState.collectAsState()
                val exportState by vm.exportState.collectAsState()

                // WindowSizeClass updates on rotation / fold-state change.
                // We treat MEDIUM and EXPANDED width as "wide" — that's
                // landscape phones, foldables in tablet posture, and tablets.
                val windowSizeClass = calculateWindowSizeClass(this)
                val isWideLayout = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                if (!state.hasPermission) {
                    PermissionGateScreen(onRequest = { audioPermissionLauncher.launch(audioPermission) })
                } else {
                    // Surface folder scan errors as a toast. We collect once
                    // per error transition (LaunchedEffect keyed on the
                    // value) and clear the state so retrying is clean.
                    androidx.compose.runtime.LaunchedEffect(state.folderError) {
                        val err = state.folderError ?: return@LaunchedEffect
                        val msgRes = when (err) {
                            FolderError.NO_AUDIO -> R.string.folder_no_audio
                        }
                        android.widget.Toast.makeText(
                            this@MainActivity, msgRes, android.widget.Toast.LENGTH_LONG,
                        ).show()
                        vm.clearFolderError()
                    }

                    MainScreen(
                        state = state,
                        sleepState = sleepState,
                        exportState = exportState,
                        isWideLayout = isWideLayout,
                        onPlayTrack = vm::playTrack,
                        onTogglePlayPause = vm::togglePlayPause,
                        onNext = vm::next,
                        onPrevious = vm::previous,
                        onSeek = vm::seekTo,
                        onPresetSelected = vm::setPreset,
                        onToggleShuffle = vm::toggleShuffle,
                        onCycleRepeat = vm::cycleRepeat,
                        onStartSleep = vm::startSleepTimer,
                        onCancelSleep = vm::cancelSleepTimer,
                        onExport = vm::exportCurrent,
                        onDismissExport = vm::dismissExportResult,
                        onToggleTuningMode = vm::toggleTuningMode,
                        onSearchChanged = vm::setSearchQuery,
                        onOpenFolder = { openFolderLauncher.launch(null) },
                        onReturnToLibrary = vm::returnToLibrary,
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
