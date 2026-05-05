package com.aelant.freqshift

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Foreground media playback service.
 *
 * The activity calls `startForegroundService`; we satisfy the platform 5-second
 * foreground contract with a [MediaStyleNotificationHelper.MediaStyle]
 * placeholder. Critically it uses the *same* notification ID as Media3's
 * [DefaultMediaNotificationProvider], so when playback begins Media3 just
 * updates this notification rather than posting a competing second one.
 *
 * Two custom buttons — previous-tuning and next-tuning — are registered via
 * [MediaSession.setCustomLayout] with [DefaultMediaNotificationProvider]'s
 * `COMMAND_KEY_COMPACT_VIEW_INDEX` extras, claiming slots 0 and 2 in the
 * compact view (play/pause goes in slot 1). Tapping a button writes the new
 * preset to [SettingsStore]; the running ViewModel observes the same flow and
 * reacts (UI + animated pitch glide). The service never writes
 * `PlaybackParameters` directly — the ViewModel owns that mutation, so users
 * always get the smooth animated transition.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(DeepBufferRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val callback = TuningSessionCallback(SettingsStore(applicationContext), scope)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setCustomLayout(ImmutableList.of(prevTuningButton(), nextTuningButton()))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        PlayerHolder.set(player)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        PlayerHolder.set(null)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val session = mediaSession ?: return

        val pendingIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { launch ->
                PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_ready))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
            lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
        }
        nm.createNotificationChannel(channel)
    }

    private fun nextTuningButton(): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_NEXT_TUNING, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_next_tuning)
            .setDisplayName(getString(R.string.cmd_next_tuning))
            .setExtras(Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 2)
            })
            .build()

    private fun prevTuningButton(): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_PREV_TUNING, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_prev_tuning)
            .setDisplayName(getString(R.string.cmd_prev_tuning))
            .setExtras(Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 0)
            })
            .build()

    companion object {
        const val CHANNEL_ID = "freqshift.playback"
        const val NOTIFICATION_ID = 1001  // matches DefaultMediaNotificationProvider default

        const val CMD_NEXT_TUNING = "com.aelant.freqshift.NEXT_TUNING"
        const val CMD_PREV_TUNING = "com.aelant.freqshift.PREV_TUNING"
    }
}

/**
 * Notification command callback. Cycles the active preset via
 * [TuningCycle.next] / [TuningCycle.previous] and persists. The ViewModel
 * picks the change up via the DataStore flow and animates the pitch glide;
 * this callback never touches PlaybackParameters directly.
 */
private class TuningSessionCallback(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(PlaybackService.CMD_NEXT_TUNING, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.CMD_PREV_TUNING, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(available)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            PlaybackService.CMD_NEXT_TUNING -> cycle(forward = true)
            PlaybackService.CMD_PREV_TUNING -> cycle(forward = false)
            else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun cycle(forward: Boolean) {
        scope.launch {
            val saved = settings.flow.firstOrNull() ?: return@launch
            val current = Frequencies.byId(saved.presetId)
            val next = if (forward) TuningCycle.next(current) else TuningCycle.previous(current)
            settings.savePreset(next.id)
        }
    }
}
