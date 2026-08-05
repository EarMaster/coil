package app.coilforphoniebox.ui.favorites

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.usecase.PlayFavoriteUseCase
import app.coilforphoniebox.shortcuts.PlayDeepLink
import app.coilforphoniebox.shortcuts.ShortcutPublisher
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favorites: FavoriteRepository,
    private val boxes: BoxRepository,
    private val playFavorite: PlayFavoriteUseCase,
    private val shortcuts: ShortcutPublisher,
) : ViewModel() {

    data class State(
        val favorites: List<Favorite> = emptyList(),
        val activeBox: Box? = null,
    )

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    val state: StateFlow<State> = combine(
        boxes.activeBox,
        boxes.activeBox.map { it?.id }.distinctUntilChanged().flatMapLatest { boxId ->
            if (boxId == null) flowOf(emptyList()) else favorites.favorites(boxId)
        },
    ) { box, list -> State(favorites = list, activeBox = box) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun play(favorite: Favorite) {
        viewModelScope.launch {
            playFavorite(favorite)
                .onFailure { messageChannel.emit(UiMessage(R.string.shortcut_failed)) }
        }
    }

    fun remove(favorite: Favorite) {
        viewModelScope.launch {
            favorites.remove(favorite.id)
            messageChannel.emit(UiMessage(R.string.favourites_removed))
        }
    }

    /** Drag-free reordering: one step at a time, which is also reachable with TalkBack. */
    fun move(favorite: Favorite, up: Boolean) {
        val current = state.value.favorites
        val index = current.indexOfFirst { it.id == favorite.id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return

        val reordered = current.toMutableList().apply {
            add(target, removeAt(index))
        }
        viewModelScope.launch { favorites.reorder(reordered.map { it.id }) }
    }

    /**
     * The `coil://play` link for [favorite], or null for a row that cannot be played.
     *
     * This is the same URI a home screen shortcut carries, box id and all, so anything that
     * can fire a VIEW intent — an automation app, an NFC tag, a link in a note — starts this
     * favourite on *its* box regardless of which one is active (§7.3). Handing it over is the
     * only way to get at it: the box id is a UUID that appears nowhere in the UI.
     */
    fun linkFor(favorite: Favorite): String? = PlayDeepLink.uriFor(favorite)?.toString()

    /**
     * Android 13 shows its own confirmation whenever an app writes to the clipboard, so
     * saying it twice is worse than not saying it at all.
     */
    fun onLinkCopied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        viewModelScope.launch { messageChannel.emit(UiMessage(R.string.favourites_link_copied)) }
    }

    fun requestPin(favorite: Favorite, coverUrl: String?) {
        viewModelScope.launch {
            val requested = shortcuts.requestPin(favorite, coverUrl)
            if (requested) {
                favorites.setPinned(favorite.id, true)
            } else {
                messageChannel.emit(UiMessage(R.string.shortcut_pin_unsupported))
            }
        }
    }
}
