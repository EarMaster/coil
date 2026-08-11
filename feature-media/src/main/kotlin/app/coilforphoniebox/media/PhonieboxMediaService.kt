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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the media session, and with it the connection to the box.
 *
 * In the default "only while Coil is open" mode the app binds to this service, so it
 * lives exactly as long as the UI and posts a notification only once the box has something
 * loaded — media3 handles that transition, provided the session was handed to it with
 * [addSession], without which it posts nothing at all. In automatic mode the service is started
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

    /** The pending "nothing needs this any more" stop, so a rebind can call it off. */
    private var stopJob: Job? = null

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

        // An id of its own, and media3 owns it: it cancels that id whenever it has nothing to
        // show, which is why the quiet status notification cannot live there too.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_PLAYBACK)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(texts.smallIcon) },
        )

        val built = MediaSession.Builder(this, player)
            .apply { launchIntent()?.let { setSessionActivity(it) } }
            .build()
        session = built

        // media3 shows nothing for a session it has not been handed: the first thing
        // `MediaNotificationManager.updateNotification` asks is `isSessionAdded`, and media3
        // only registers a session itself when a controller binds with its own service action
        // or a media button intent arrives. Coil's binding is neither, so without this line the
        // notification is never posted at all — and the same early return cancels the id below.
        addSession(built)

        // media3 catches Android's refusal to promote the service and reports it here; with no
        // listener set it is swallowed, and the symptom is controls that silently never appear.
        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    Log.w(TAG, "Android refused to start the media session in the foreground")
                    // Automatic mode has to show something, and this notification is allowed
                    // where a foreground promotion is not.
                    if (automatic) {
                        notificationManager().notify(STATUS_NOTIFICATION_ID, statusNotification())
                    }
                }
            },
        )

        observeIdleState()
        observeSessionModeSetting()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_START_AUTOMATIC) {
            automatic = true
            // Started with startForegroundService, so something visible has to go up now.
            promoteWithStatusNotification()
            watchNetwork()
        }

        return result
    }

    /**
     * Automatic mode is a foreground service by contract (§8.3) — it was started outright and
     * has to stay visible whether or not the box happens to be playing this second. media3
     * detaches the service as soon as playback stops, so the requirement is forced back on here.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || automatic)
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
     * Keeps the quiet status notification in step with media3's own, and ends the service in
     * the default mode when there is nothing left to do.
     *
     * The status notification follows whether the box has anything *loaded*, not whether it is
     * playing. `hasContent` is the exact complement of the condition media3 posts under —
     * `MediaNotificationManager.shouldShowNotification` wants a non-empty timeline and a state
     * other than idle — so one of the two is on screen and never both, and which one no longer
     * depends on the order two collectors happen to run in. A *paused* box keeps its media
     * controls, which is when they are wanted most.
     */
    private fun observeIdleState() {
        scope.launch {
            playerRepository.status
                .map { it.hasContent to it.state }
                .distinctUntilChanged()
                .collect { (hasContent, state) ->
                    if (automatic) updateStatusNotification(hasContent)
                    if (state != PlaybackState.PLAY) stopIfNoLongerNeeded()
                }
        }
    }

    private fun updateStatusNotification(hasContent: Boolean) {
        if (hasContent) {
            // media3 has the shade from here, on its own id. Two notifications for one box
            // would be one too many.
            notificationManager().cancel(STATUS_NOTIFICATION_ID)
        } else {
            // `startForeground` rather than `notify`: media3 has just detached the service and
            // taken its notification down, and automatic mode has to stay foreground.
            promoteWithStatusNotification()
        }
    }

    private fun promoteWithStatusNotification() {
        runCatching { startForeground(STATUS_NOTIFICATION_ID, statusNotification()) }
            .onFailure { Log.w(TAG, "Could not keep the automatic session in the foreground", it) }
    }

    /**
     * Ends the service when nothing needs it, after a moment's grace.
     *
     * The grace is the point. `currentStatus()` is what the box last *published*, which lags a
     * command by up to a quarter of a second and by however long the box's own sequential
     * socket takes (§6) — so a user who taps play and pockets the phone unbinds the UI while
     * the box still reports "not playing", and stopping on that reading would tear the session
     * down exactly as the notification was about to appear.
     */
    private fun stopIfNoLongerNeeded() {
        if (automatic || boundClients > 0) {
            stopJob?.cancel()
            stopJob = null
            return
        }

        stopJob?.cancel()
        stopJob = scope.launch {
            delay(STOP_GRACE_MILLIS)
            if (automatic || boundClients > 0) return@launch
            if (connectionManager.currentStatus().state == PlaybackState.PLAY) return@launch
            Log.i(TAG, "Nothing playing and nothing bound; stopping")
            stopSelf()
        }
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
        stopJob?.cancel()
        stopJob = null

        clearListener()

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

        /** media3's own, and media3's alone — it cancels this id whenever it has nothing to show. */
        const val NOTIFICATION_ID = 1001

        /**
         * The quiet "ready" notification automatic mode waits behind.
         *
         * An id of its own rather than media3's: sharing one meant two writers with no ordering
         * between them, and media3 cancelling the id was enough to leave a foreground service
         * with no notification at all.
         */
        const val STATUS_NOTIFICATION_ID = 1002

        /** Long enough for a command in flight to come back as a published status (§8.1). */
        private const val STOP_GRACE_MILLIS = 2_000L

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
