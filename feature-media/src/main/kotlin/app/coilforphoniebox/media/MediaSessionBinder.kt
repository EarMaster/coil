package app.coilforphoniebox.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSessionService
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the media service alive while the UI is on screen, without making it a foreground
 * service.
 *
 * This is what "only while Coil is open" means in practice (§8.3): a bound service exists
 * as long as the screen does and posts no notification of its own, and media3 turns it
 * into a foreground service with a media notification the moment the box starts playing.
 *
 * It is also where [SessionMode.OFF] is enforced. The service cannot enforce it alone: a
 * bound service survives its own `stopSelf`, so as long as the UI holds a binding media3
 * would still put a notification up the moment the box starts playing. Binding is
 * therefore reconciled from two facts — whether the UI wants a session, and whether the
 * setting allows one — and either one changing re-runs the decision.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class MediaSessionBinder @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
) {
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether the UI is on screen, as reported by [bind] and [unbind]. */
    private var wanted = false

    /**
     * Whether the setting permits a session. It starts out false and is only ever set from
     * the setting itself, so a launch in [SessionMode.OFF] never briefly binds: the first
     * value arrives before there is anything for a session to show.
     */
    private var allowed = false
    private var bound = false

    init {
        scope.launch {
            settings.settings
                .map { it.sessionMode != SessionMode.OFF }
                .distinctUntilChanged()
                .collect { permitted ->
                    allowed = permitted
                    reconcile()
                }
        }
    }

    fun bind() {
        wanted = true
        reconcile()
    }

    fun unbind() {
        wanted = false
        reconcile()
    }

    private fun reconcile() {
        if (wanted && allowed) bindNow() else unbindNow()
    }

    /**
     * The action matters. `MediaSessionService.onBind` answers only its own service action and
     * the legacy browser one, and returns null for anything else — so an actionless bind keeps
     * the service alive without ever establishing a connection, which left the bound-client
     * count here scoring a binding the platform had not made.
     */
    private fun bindNow() {
        if (bound) return
        bound = runCatching {
            context.bindService(
                PhonieboxMediaService.serviceIntent(context)
                    .setAction(MediaSessionService.SERVICE_INTERFACE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure { Log.w(TAG, "Could not bind the media service", it) }
            .getOrDefault(false)
    }

    private fun unbindNow() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    private companion object {
        const val TAG = "CoilMediaBinder"
    }
}
