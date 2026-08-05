package app.coilforphoniebox.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import app.coilforphoniebox.transport.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the media session, and with it the connection to the box.
 *
 * In the default "only while Coil is open" mode the app binds to this service, so it
 * lives exactly as long as the UI and posts a notification only once the box actually
 * plays — media3 handles that transition. In automatic mode the service is started
 * outright and keeps a low-priority notification up while it waits, because a foreground
 * service has to be visible (§8.3). When controls are switched off entirely the service is
 * not meant to exist at all: nothing binds or starts it, and it stops itself if it is
 * already running when the setting changes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class PhonieboxMediaService : MediaSessionService() {

    @Inject lateinit var playerRepository: PlayerRepository

    @Inject lateinit var boxRepository: BoxRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var texts: MediaNotificationTexts

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var phonieboxPlayer: PhonieboxPlayer? = null

    /** Set when the service was started for automatic mode rather than merely bound. */
    private var automatic = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * How many clients are bound. In the default mode the service should exist while the
     * UI does and while something plays, and not a moment longer — a connection nobody is
     * watching is exactly the battery cost that mode exists to avoid.
     */
    private var boundClients = 0

    override fun onCreate() {
        super.onCreate()
        createChannels()

        // The session owns the connection for as long as it lives.
        connectionManager.acquire()

        val player = PhonieboxPlayer(scope, playerRepository, boxRepository)
        phonieboxPlayer = player

        // Shares its notification id with the quiet status notification below, so the
        // media notification simply replaces it once playback starts.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_PLAYBACK)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(texts.smallIcon) },
        )

        session = MediaSession.Builder(this, player)
            .apply { launchIntent()?.let { setSessionActivity(it) } }
            .build()

        observeIdleState()
        observeSessionModeSetting()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_START_AUTOMATIC) {
            automatic = true
            // Started with startForegroundService, so something visible has to go up now.
            startForeground(NOTIFICATION_ID, statusNotification())
            watchNetwork()
        }

        return result
    }

    override fun onBind(intent: Intent?): IBinder? {
        boundClients++
        return super.onBind(intent)
    }

    override fun onRebind(intent: Intent?) {
        boundClients++
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        stopIfNoLongerNeeded()
        return super.onUnbind(intent)
    }

    /**
     * While automatic mode waits for the box to start playing there is nothing for media3
     * to show, but the service still has to be visible. Once playback begins, media3's own
     * notification takes over the same id, and this quiet one goes with it.
     *
     * The same signal ends the service in the default mode: nothing playing, nobody bound,
     * nothing to do.
     */
    private fun observeIdleState() {
        scope.launch {
            playerRepository.status
                .map { it.state }
                .distinctUntilChanged()
                .collect { state ->
                    if (automatic && state != PlaybackState.PLAY) {
                        notificationManager().notify(NOTIFICATION_ID, statusNotification())
                    }
                    if (state != PlaybackState.PLAY) stopIfNoLongerNeeded()
                }
        }
    }

    private fun stopIfNoLongerNeeded() {
        if (automatic || boundClients > 0) return
        if (connectionManager.currentStatus().state == PlaybackState.PLAY) return
        Log.i(TAG, "Nothing playing and nothing bound; stopping")
        stopSelf()
    }

    /**
     * Turning a mode off in settings should stop the service, not wait for a reboot.
     *
     * [SessionMode.OFF] ends the service whatever it is doing, including mid-playback:
     * that mode's promise is that no notification exists, and this service's notification
     * is the one thing that would break it. `stopSelf` alone cannot deliver that while the
     * UI is bound — `MediaSessionBinder` drops the binding for the same setting, and the
     * two together are what actually takes the notification down.
     */
    private fun observeSessionModeSetting() {
        scope.launch {
            settingsRepository.settings
                .map { it.sessionMode }
                .distinctUntilChanged()
                .collect { mode ->
                    when {
                        mode == SessionMode.OFF -> {
                            Log.i(TAG, "Controls switched off entirely; stopping")
                            stopSelf()
                        }

                        automatic && mode == SessionMode.APP_ONLY -> {
                            Log.i(TAG, "Automatic mode switched off; stopping")
                            stopSelf()
                        }
                    }
                }
        }
    }

    private fun statusNotification(): Notification {
        val box = connectionManager.activeBox.value
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(texts.smallIcon)
            .setContentTitle(texts.readyTitle)
            .setContentText(box?.displayName?.let { texts.connectedTo(it) })
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .apply { launchIntent()?.let { setContentIntent(it) } }
            .build()
    }

    /**
     * Stops the service when the device leaves Wi-Fi, which is the point of tying the
     * automatic mode to the network: no box in reach, no reason to run.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi lost; stopping the automatic session")
                stopSelf()
            }
        }
        networkCallback = callback

        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
        }.onFailure { Log.w(TAG, "Could not watch the network", it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away ends the session unless the user asked for automatic mode
        // or the box is actually playing right now.
        val playing = connectionManager.currentStatus().state == PlaybackState.PLAY
        if (!automatic && !playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null

        session?.release()
        session = null
        phonieboxPlayer?.release()
        phonieboxPlayer = null

        connectionManager.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val manager = notificationManager()

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                texts.playbackChannelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = texts.playbackChannelDescription },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                texts.statusChannelName,
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = texts.statusChannelDescription },
        )
    }

    companion object {
        private const val TAG = "CoilMediaService"

        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_STATUS = "connection"

        /** Shared with media3's own notification, so one replaces the other. */
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_AUTOMATIC = "app.coilforphoniebox.media.START_AUTOMATIC"

        fun automaticIntent(context: Context): Intent =
            Intent(context, PhonieboxMediaService::class.java).setAction(ACTION_START_AUTOMATIC)

        /**
         * Plain start or bind intent. Used with `bindService` by the UI and with
         * `startService` by the shortcut path, where there is no screen to stay bound.
         */
        fun serviceIntent(context: Context): Intent =
            Intent(context, PhonieboxMediaService::class.java)
    }
}
