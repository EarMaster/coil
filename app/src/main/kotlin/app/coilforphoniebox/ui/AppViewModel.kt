package app.coilforphoniebox.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.ConnectionTestResult
import app.coilforphoniebox.domain.model.FavoritesLayout
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State the whole shell needs: which box is active, whether it answers, and what is
 * playing for the mini player.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val boxes: BoxRepository,
    private val player: PlayerRepository,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class State(
        val settings: AppSettings = AppSettings(),
        val boxes: List<Box> = emptyList(),
        val activeBox: Box? = null,
        val connection: ConnectionState = ConnectionState.DISCONNECTED,
        val status: PlayerStatus = PlayerStatus.Idle,
        val coverUrl: String? = null,
        /** False until the first read from storage, so no screen flashes the wrong way. */
        val loaded: Boolean = false,
    ) {
        val needsOnboarding: Boolean get() = loaded && boxes.isEmpty()

        val showMiniPlayer: Boolean get() = status.hasContent

        val isOffline: Boolean
            get() = activeBox != null && connection == ConnectionState.DISCONNECTED
    }

    val state: StateFlow<State> = combine(
        settings.settings,
        boxes.boxes,
        boxes.activeBox,
        combine(player.connectionState, player.status, player.coverUrl) { connection, status, cover ->
            Triple(connection, status, cover)
        },
    ) { appSettings, allBoxes, activeBox, live ->
        State(
            settings = appSettings,
            boxes = allBoxes,
            activeBox = activeBox,
            connection = live.first,
            status = live.second,
            coverUrl = live.third,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /** Reachability of each configured box, filled in when the switcher is opened. */
    private val _reachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val reachability: StateFlow<Map<String, Boolean>> = _reachability.asStateFlow()

    init {
        // Opportunistic library refresh, which the repository only carries out when the
        // cache is old and the box is idle (§6.4).
        viewModelScope.launch {
            boxes.activeBox
                .map { it?.id }
                .distinctUntilChanged()
                .collect { boxId -> if (boxId != null) library.refreshIfStaleAndIdle(boxId) }
        }
    }

    fun selectBox(boxId: String) {
        viewModelScope.launch { boxes.setActive(boxId) }
    }

    /**
     * Probes every box in parallel. The active one is answered by its live connection, so
     * only the others cost a request — and they are one `core.version` call each.
     */
    fun probeBoxes() {
        viewModelScope.launch {
            val all = boxes.boxes.first()
            val active = state.value.activeBox?.id
            val results = all.map { box ->
                async {
                    if (box.id == active && state.value.connection.isUsable) {
                        box.id to true
                    } else {
                        box.id to (boxes.testConnection(box.host, box.rpcPort) is ConnectionTestResult.Reachable)
                    }
                }
            }.awaitAll()
            _reachability.value = results.toMap()
        }
    }

    fun togglePlayback() {
        viewModelScope.launch { player.toggle() }
    }

    /**
     * Switches the favourites tab between covers and rows.
     *
     * Lives here rather than in `FavoritesViewModel` because the control is in the top bar,
     * which the shell owns — and it is a stored preference, so the tab comes back the way it
     * was left.
     */
    fun toggleFavoritesLayout() {
        val next = when (state.value.settings.favoritesLayout) {
            FavoritesLayout.GRID -> FavoritesLayout.LIST
            FavoritesLayout.LIST -> FavoritesLayout.GRID
        }
        viewModelScope.launch { settings.setFavoritesLayout(next) }
    }
}
