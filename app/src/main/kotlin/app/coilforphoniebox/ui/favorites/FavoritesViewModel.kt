package app.coilforphoniebox.ui.favorites

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import app.coilforphoniebox.domain.usecase.PlayFavoriteUseCase
import app.coilforphoniebox.domain.usecase.ResolveFavoriteCoverUseCase
import app.coilforphoniebox.shortcuts.PlayDeepLink
import app.coilforphoniebox.shortcuts.ShortcutPublisher
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val player: PlayerRepository,
    private val playFavorite: PlayFavoriteUseCase,
    private val resolveCover: ResolveFavoriteCoverUseCase,
    private val shortcuts: ShortcutPublisher,
    settings: SettingsRepository,
) : ViewModel() {

    data class State(
        val favorites: List<Favorite> = emptyList(),
        val activeBox: Box? = null,
        val connection: ConnectionState = ConnectionState.DISCONNECTED,
        /** Favourites whose cover lookup has finished — see [coverPending]. */
        val coversSettled: Set<Long> = emptySet(),
        /** Whether a cover held somewhere other than the box may be loaded (§16). */
        val allowExternalCovers: Boolean = false,
    ) {
        /**
         * Whether [favorite] is still waiting to hear whether it has artwork.
         *
         * A favourite with no `coverFile` is not necessarily a favourite without a cover: it
         * may simply be one nobody has asked about yet, and asking costs up to three RPCs
         * (see `LibraryRepository.coverFileFor`). Putting stand-in artwork on those would
         * fill the tab with abstract covers on first visit and then swap them out one by one
         * as the real ones arrived. With the box unreachable there is no lookup to wait for
         * at all, so the stand-in is the honest thing to show straight away.
         */
        fun coverPending(favorite: Favorite): Boolean =
            favorite.coverFile == null &&
                connection.isUsable &&
                favorite.id !in coversSettled
    }

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    /**
     * Favourites this session has already asked the box about, so a scroll back up the grid
     * does not ask again for the ones that genuinely have no artwork.
     *
     * Deliberately not persisted, and cleared whenever the box or the connection changes:
     * a lookup that came back empty because the box was unreachable must be allowed a second
     * chance once it is back, or a missing cover would stay missing until the app restarts.
     */
    private val coverAttempts = mutableSetOf<Long>()

    /**
     * Which of those attempts have come back. Separate from [coverAttempts] because the screen
     * has to observe it — "asked and told there is nothing" is what licenses stand-in artwork,
     * and "asked, still waiting" must not.
     */
    private val coversSettled = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<State> = combine(
        boxes.activeBox,
        boxes.activeBox.map { it?.id }.distinctUntilChanged().flatMapLatest { boxId ->
            if (boxId == null) flowOf(emptyList()) else favorites.favorites(boxId)
        },
        player.connectionState,
        coversSettled,
        settings.settings.map { it.loadExternalCoverArt }.distinctUntilChanged(),
    ) { box, list, connection, settled, allowExternalCovers ->
        State(
            favorites = list,
            activeBox = box,
            connection = connection,
            coversSettled = settled,
            allowExternalCovers = allowExternalCovers,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    init {
        viewModelScope.launch {
            combine(
                boxes.activeBox.map { it?.id },
                player.connectionState.map { it.isUsable },
            ) { boxId, usable -> boxId to usable }
                .distinctUntilChanged()
                .collect {
                    coverAttempts.clear()
                    coversSettled.value = emptySet()
                }
        }
    }

    /**
     * Resolves the cover art for a favourite that was saved without one, once it is on
     * screen. Called from the entry itself rather than for the whole list, because each
     * lookup is at least one RPC on the socket the box shares with its card reader (§6) —
     * and the answer is stored, so it happens once per favourite rather than per visit.
     */
    fun ensureCover(favorite: Favorite) {
        if (favorite.coverFile != null) return
        // Asking an unreachable box only burns the one attempt this favourite gets.
        if (!state.value.connection.isUsable) return
        if (!coverAttempts.add(favorite.id)) return

        viewModelScope.launch {
            try {
                resolveCover(favorite)
            } finally {
                // However it went — a cover, no cover, or a box that stopped answering
                // halfway — this favourite is no longer waiting on an answer.
                coversSettled.value = coversSettled.value + favorite.id
            }
        }
    }

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
