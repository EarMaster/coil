package app.coilforphoniebox.shortcuts

import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.transport.di.TransportScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the launcher's dynamic shortcuts in step with the favourites of the active box.
 *
 * Started once from the application object: shortcuts are part of the launcher's state
 * rather than the app's, so they should be right even if the user never opens Coil after
 * switching boxes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ShortcutSynchronizer @Inject constructor(
    private val boxes: BoxRepository,
    private val favorites: FavoriteRepository,
    private val publisher: ShortcutPublisher,
    @TransportScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            boxes.activeBox
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { boxId ->
                    if (boxId == null) flowOf(emptyList())
                    else favorites.mostLaunched(boxId, limit = DYNAMIC_LIMIT)
                }
                .distinctUntilChanged()
                .collect { publisher.publishDynamic(it) }
        }
    }

    private companion object {
        /** Launchers show about four; asking for more just gets them dropped. */
        const val DYNAMIC_LIMIT = 4
    }
}
