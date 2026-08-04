package app.coilforphoniebox

import android.app.Application
import app.coilforphoniebox.shortcuts.ShortcutSynchronizer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CoilApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var shortcutSynchronizer: ShortcutSynchronizer

    override fun onCreate() {
        super.onCreate()
        // Launcher shortcuts belong to the launcher's state rather than the app's, so they
        // are kept current from here rather than from any one screen.
        shortcutSynchronizer.start()
    }

    /**
     * The image loader — the *library* called Coil, not this app.
     *
     * Cover art is effectively immutable per file name and comes from the box over plain
     * HTTP, so a generous disk cache is the whole strategy (§6.2). The URL already contains
     * the box's host, which keeps two boxes' identically named covers apart on its own.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("cover-art"))
                    .maxSizeBytes(COVER_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        const val COVER_CACHE_BYTES = 96L * 1024 * 1024
    }
}
