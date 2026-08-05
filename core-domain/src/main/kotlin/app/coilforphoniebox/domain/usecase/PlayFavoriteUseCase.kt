package app.coilforphoniebox.domain.usecase

import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Starts a favourite on the box it belongs to.
 *
 * A home screen shortcut must start *its* box regardless of which one is currently
 * active (§7.3), so this switches the active box on success — otherwise the media
 * notification and the app would disagree about what is playing.
 *
 * No cache lookup is involved: the favourite already carries the folder path that
 * `play_folder` takes, which makes this exactly one RPC call (§6.5).
 */
class PlayFavoriteUseCase @Inject constructor(
    private val favorites: FavoriteRepository,
    private val boxes: BoxRepository,
    private val player: PlayerRepository,
) {
    suspend operator fun invoke(favoriteId: Long): Result<Favorite> {
        val favorite = favorites.favorite(favoriteId)
            ?: return Result.failure(IllegalArgumentException("Unknown favourite $favoriteId"))
        return invoke(favorite)
    }

    suspend operator fun invoke(favorite: Favorite): Result<Favorite> {
        val target = favorite.toPlayTarget()
            ?: return Result.failure(IllegalStateException("Favourite ${favorite.id} has no target"))

        return player.playOn(favorite.boxId, target)
            .onSuccess {
                favorites.recordLaunch(favorite.id)
                boxes.setActive(favorite.boxId)
            }
            .map { favorite }
    }
}
