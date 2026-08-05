package app.coilforphoniebox.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.transport.di.TransportScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Brings the automatic session back after a reboot.
 *
 * Whether Android permits a foreground service to start from here depends on the version
 * and the manufacturer; [AutoSessionStarter] treats a refusal as a normal outcome.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var starter: AutoSessionStarter

    @Inject @TransportScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // The receiver returns immediately; the settings lookup is a suspend call, so it
        // continues on the process-wide transport scope.
        scope.launch { starter.startIfEnabled() }
    }
}
