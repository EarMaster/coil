package app.coilforphoniebox.shortcut

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.media.PhonieboxMediaService
import app.coilforphoniebox.shortcuts.PlayDeepLink
import app.coilforphoniebox.shortcuts.ShortcutPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `coil://play?box=…`, which is what a home screen shortcut points at.
 *
 * No UI at all: it connects to the box named in the link, fires one `play_folder`, starts
 * the media service and finishes. Tapping the icon starts the audiobook without the app
 * ever becoming visible; on failure there is a toast rather than an empty screen (§9).
 *
 * The box comes from the link rather than from whatever is currently active, and becomes
 * the active box afterwards so that the app and the media notification agree (§7.3).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayShortcutActivity : ComponentActivity() {

    @Inject lateinit var player: PlayerRepository

    @Inject lateinit var boxes: BoxRepository

    @Inject lateinit var favorites: FavoriteRepository

    @Inject lateinit var shortcuts: ShortcutPublisher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = PlayDeepLink.parse(intent?.data)
        if (request == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            // No cache lookup: the link already carries the folder path that play_folder
            // takes, so this is exactly one RPC (§6.5).
            val result = player.playOn(request.boxId, request.target)

            result.onSuccess {
                request.favoriteId?.let { favoriteId ->
                    favorites.recordLaunch(favoriteId)
                    // Coil ranks its own dynamic set; the launcher keeps a separate history
                    // and needs telling, which also covers pinned shortcuts.
                    shortcuts.reportUsed(favoriteId)
                }
                boxes.setActive(request.boxId)
                confirm(request.favoriteId, request.boxId)
                // Started rather than bound: this activity finishes straight away, so there
                // is nothing to hold a binding. The service stops itself again once the box
                // stops playing.
                startService(PhonieboxMediaService.serviceIntent(this@PlayShortcutActivity))
            }.onFailure {
                Toast.makeText(
                    this@PlayShortcutActivity,
                    R.string.shortcut_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            finish()
        }
    }

    /**
     * The box is usually in another room, so a tap that opens no UI needs to say something.
     * Both names are content the user chose, and go into the message as they are (§12.4).
     */
    private suspend fun confirm(favoriteId: Long?, boxId: String) {
        val label = favoriteId?.let { favorites.favorite(it)?.label } ?: return
        val boxName = boxes.box(boxId)?.displayName ?: return
        Toast.makeText(
            this,
            getString(R.string.shortcut_starting, label, boxName),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
