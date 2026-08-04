package app.coilforphoniebox.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the automatic session when its conditions hold (§8.3).
 *
 * Three of them, all necessary: the global mode is automatic, the active box allows it,
 * and the device is on Wi-Fi. A box in a guest room can be excluded permanently through
 * its own flag (§7.4), and monitoring is limited to the active box — one persistent
 * connection per configured box would multiply the battery cost of a feature that is
 * already a compromise.
 *
 * Whether the start succeeds is not entirely up to the app: Android restricts starting a
 * foreground service from the background, so a start from the boot receiver can be
 * refused outright. That is caught and logged rather than crashing, and the app starts
 * the service again the next time it is opened. This is the honest limitation the
 * settings screen warns about.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class AutoSessionStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val boxes: BoxRepository,
) {

    suspend fun startIfEnabled(): Boolean {
        if (settings.current().sessionMode != SessionMode.AUTOMATIC) return false

        val box = boxes.activeBox.first() ?: return false
        if (!box.autoSessionEnabled) return false
        if (!isOnWifi()) return false

        return start()
    }

    private fun start(): Boolean = runCatching {
        ContextCompat.startForegroundService(context, PhonieboxMediaService.automaticIntent(context))
        true
    }.getOrElse { error ->
        Log.w(TAG, "Could not start the automatic session", error)
        false
    }

    /**
     * Any Wi-Fi network counts. Matching a stored SSID would need the location permission
     * on Android 10 and later, which is a steep ask for a convenience feature — so the
     * SSID on the box row narrows the *offer* rather than gating the check.
     */
    private fun isOnWifi(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private companion object {
        const val TAG = "CoilAutoSession"
    }
}
