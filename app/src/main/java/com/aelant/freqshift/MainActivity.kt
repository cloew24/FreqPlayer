package com.aelant.freqshift

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

                if (!state.hasPermission) {
                    PermissionGateScreen(onRequest = { audioPermissionLauncher.launch(audioPermission) })
                } else {
                    MainScreen(
                        state = state,
                        sleepState = sleepState,
                        exportState = exportState,
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
