package app.coilforphoniebox

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.media.AutoSessionStarter
import app.coilforphoniebox.media.MediaSessionBinder
import app.coilforphoniebox.shortcuts.OpenDeepLink
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.ui.AppViewModel
import app.coilforphoniebox.ui.CoilApp
import app.coilforphoniebox.ui.theme.CoilTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var mediaSessionBinder: MediaSessionBinder

    @Inject lateinit var autoSessionStarter: AutoSessionStarter

    /** Only for `coil://open?box=…`; everything else about boxes goes through a view model. */
    @Inject lateinit var boxes: BoxRepository

    private val appViewModel: AppViewModel by viewModels()

    /** Whether the intent this instance was started with has already been acted on. */
    private var deepLinkHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restored rather than reset, so a rotation does not re-apply the link. Without this a
        // user who arrived by link, switched box, then turned the phone would be yanked back to
        // the box the link named.
        deepLinkHandled = savedInstanceState?.getBoolean(STATE_DEEP_LINK_HANDLED) == true
        if (!deepLinkHandled) applyOpenLink(intent)

        setContent {
            val state by appViewModel.state.collectAsStateWithLifecycle()

            CoilTheme(
                darkTheme = when (state.settings.themeMode) {
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = state.settings.dynamicColor,
            ) {
                Surface {
                    RequestNotificationPermission()
                    CoilApp(appViewModel)
                }
            }
        }
    }

    /**
     * A second `coil://open` while the app is already running.
     *
     * `launchMode="singleTask"` means the existing instance is reused, so this is the only place
     * a later link arrives — `onCreate` will not run again. A fresh intent is a fresh request,
     * so it applies whatever [deepLinkHandled] says about the previous one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DEEP_LINK_HANDLED, deepLinkHandled)
    }

    /**
     * Switches to the box a `coil://open` link names, if it names one.
     *
     * The id is checked against the configured boxes first: `setActive` writes it straight to
     * settings, so a stale link — one from a phone that was reset, or a hand-written id — would
     * otherwise leave the app pointed at a box that does not exist. Saying so is better than
     * opening onto an empty screen, and better than silently ignoring it.
     */
    private fun applyOpenLink(intent: Intent?) {
        val request = OpenDeepLink.parse(intent?.data) ?: return
        deepLinkHandled = true

        val boxId = request.boxId ?: return
        lifecycleScope.launch {
            if (boxes.box(boxId) == null) {
                Toast.makeText(this@MainActivity, R.string.deeplink_box_unknown, Toast.LENGTH_SHORT).show()
                return@launch
            }
            boxes.setActive(boxId)
        }
    }

    override fun onStart() {
        super.onStart()
        // The UI is one of the two things that can hold the connection open; the media
        // service is the other (§8.3).
        connectionManager.acquire()
        mediaSessionBinder.bind()

        // Starting a foreground service is reliably permitted while the app is in the
        // foreground, which is why the automatic mode is also (re)started from here and
        // not only from the boot receiver.
        lifecycleScope.launch { autoSessionStarter.startIfEnabled() }
    }

    override fun onStop() {
        mediaSessionBinder.unbind()
        connectionManager.release()
        super.onStop()
    }
}

private const val STATE_DEEP_LINK_HANDLED = "deepLinkHandled"

/**
 * Asked for on first launch, because without it the media notification — the whole point
 * of the media session — cannot be shown (§8.2).
 */
@androidx.compose.runtime.Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
