package app.coilforphoniebox.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.coilforphoniebox.domain.model.Favorite
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Favourites as launcher shortcuts (§9).
 *
 * Dynamic shortcuts come from the active box only — a long-press menu mixing boxes would
 * be confusing. Pinned shortcuts may come from any box, because the user placed them
 * deliberately (§7.3).
 */
@Singleton
class ShortcutPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Replaces the dynamic shortcut set. Depending on the launcher only about four are
     * ever visible, so the list is capped rather than published in full.
     */
    fun publishDynamic(favorites: List<Favorite>) {
        val shortcuts = favorites
            .take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_DYNAMIC))
            .mapNotNull { favorite -> buildShortcut(favorite, icon = null) }

        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onFailure { Log.w(TAG, "Could not publish dynamic shortcuts", it) }
    }

    /**
     * Asks the launcher to pin [favorite], using its cover art as an adaptive icon.
     *
     * Returns false when the launcher does not support pinning at all, which is the one
     * case worth telling the user about — the rest is up to the launcher's own dialog.
     */
    suspend fun requestPin(favorite: Favorite, coverUrl: String?): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val icon = coverUrl?.let { loadCover(it) }
        val shortcut = buildShortcut(favorite, icon) ?: return false

        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
            .onFailure { Log.w(TAG, "Could not request pinned shortcut", it) }
            .getOrDefault(false)
    }

    /**
     * Tells the launcher that a shortcut was actually used.
     *
     * Coil ranks its own dynamic set by [Favorite.launchCount], but the launcher keeps its
     * own activation history and uses it to decide what to surface — including for pinned
     * shortcuts, which Coil does not order at all.
     */
    fun reportUsed(favoriteId: Long) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(favoriteId)) }
    }

    fun removeDynamic() {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    private fun buildShortcut(favorite: Favorite, icon: IconCompat?): ShortcutInfoCompat? {
        val intent = PlayDeepLink.intentFor(favorite, context.packageName) ?: return null

        return ShortcutInfoCompat.Builder(context, shortcutId(favorite.id))
            // Labels are the favourite's own name: content from the box, shown as it is
            // and never translated (§12.4).
            .setShortLabel(favorite.label)
            .setLongLabel(favorite.label)
            .setIcon(icon ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_coil))
            .setIntent(intent)
            .build()
    }

    /**
     * Uses the app's shared image loader, so a cover already on screen comes straight
     * from its disk cache rather than over the network again.
     */
    private suspend fun loadCover(url: String): IconCompat? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ICON_SIZE_PX)
            .allowHardware(false)
            .build()

        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable
            ?.let { (it as? BitmapDrawable)?.bitmap }
            ?: return null

        return IconCompat.createWithAdaptiveBitmap(bitmap.ensureSoftware())
    }

    /** Adaptive icons need a software bitmap; a hardware one cannot be read back. */
    private fun Bitmap.ensureSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) ?: this else this

    private fun shortcutId(favoriteId: Long) = "favorite-$favoriteId"

    private companion object {
        const val TAG = "CoilShortcuts"
        const val MAX_DYNAMIC = 4
        const val ICON_SIZE_PX = 256
    }
}
