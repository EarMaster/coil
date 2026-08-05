package app.coilforphoniebox.domain.usecase

import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import javax.inject.Inject

/**
 * Fills in the cover art a favourite was saved without, and stores it.
 *
 * Favourites are saved from wherever the user happens to be, and most of those places do
 * not know a cover: the player's star knows the playing song's, an album row knows its
 * own, and a folder or track row in the library knows none at all. Without a backfill the
 * favourites tab is a wall of placeholder icons — which is what it was.
 *
 * Deliberately one favourite at a time and driven by what is on screen, never by a sweep
 * over the table: every resolution is at least one RPC on the socket the box shares with
 * its card reader (§6). The file name is persisted, so a favourite costs this once ever and
 * its cover then renders from the image loader's disk cache with the box switched off.
 */
class ResolveFavoriteCoverUseCase @Inject constructor(
    private val favorites: FavoriteRepository,
    private val library: LibraryRepository,
) {
    /** No-op for a favourite that already has a cover, or has no target to resolve one from. */
    suspend operator fun invoke(favorite: Favorite) {
        if (favorite.coverFile != null) return
        val target = favorite.toPlayTarget() ?: return
        val coverFile = library.coverFileFor(favorite.boxId, target) ?: return
        favorites.setCover(favorite.id, coverFile)
    }
}
