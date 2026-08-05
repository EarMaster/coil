package app.coilforphoniebox.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the media service alive while the UI is on screen, without making it a foreground
 * service.
 *
 * This is what "only while Coil is open" means in practice (§8.3): a bound service exists
 * as long as the screen does and posts no notification of its own, and media3 turns it
 * into a foreground service with a media notification the moment the box starts playing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class MediaSessionBinder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private var bound = false

    fun bind() {
        if (bound) return
        bound = runCatching {
            context.bindService(
                PhonieboxMediaService.serviceIntent(context),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure { Log.w(TAG, "Could not bind the media service", it) }
            .getOrDefault(false)
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    private companion object {
        const val TAG = "CoilMediaBinder"
    }
}
