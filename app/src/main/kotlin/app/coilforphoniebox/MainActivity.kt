package app.coilforphoniebox

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import app.coilforphoniebox.media.AutoSessionStarter
import app.coilforphoniebox.media.MediaSessionBinder
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

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
