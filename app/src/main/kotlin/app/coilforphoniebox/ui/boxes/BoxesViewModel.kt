package app.coilforphoniebox.ui.boxes

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.shortcuts.OpenDeepLink
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs both box management screens — the list and one box's own page.
 *
 * Every box is addressed by id rather than through "the active box", which is the difference
 * from the settings screen this used to live on: a box that is not the active one can be
 * renamed, re-addressed and removed without first switching to it.
 */
@HiltViewModel
class BoxesViewModel @Inject constructor(
    private val boxes: BoxRepository,
) : ViewModel() {

    data class State(
        val boxes: List<Box> = emptyList(),
        val activeBoxId: String? = null,
    )

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    val state: StateFlow<State> = combine(boxes.boxes, boxes.activeBox) { all, active ->
        State(boxes = all, activeBoxId = active?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /**
     * Makes another box the active one. The connection follows on its own — the transport
     * watches the active box and rebuilds its sockets (§7.3).
     */
    fun selectBox(boxId: String) {
        if (boxId == state.value.activeBoxId) return
        viewModelScope.launch { boxes.setActive(boxId) }
    }

    fun updateBox(boxId: String, host: String, rpcPort: Int, pubPort: Int, displayName: String) {
        val box = state.value.boxes.firstOrNull { it.id == boxId } ?: return
        viewModelScope.launch {
            boxes.update(
                box.copy(
                    host = host.trim().ifBlank { box.host },
                    rpcPort = rpcPort,
                    pubPort = pubPort,
                    displayName = displayName.trim().ifBlank { box.displayName },
                ),
            )
        }
    }

    fun removeBox(boxId: String) {
        viewModelScope.launch { boxes.delete(boxId) }
    }

    /**
     * `coil://open?box=…` for [box] — a link that opens Coil showing that box.
     *
     * Handing it out is the only way to get at it, for the same reason a favourite's link has
     * to be copyable: the box id is a UUID that appears nowhere in the UI, so a link naming a
     * particular box cannot be written by hand. Unlike a favourite's link this one sends the
     * box nothing at all; it only decides which box the app comes up on.
     */
    fun openLinkFor(box: Box): String = OpenDeepLink.uriFor(box.id).toString()

    /**
     * Android 13 shows its own confirmation whenever an app writes to the clipboard, so saying
     * it twice is worse than not saying it at all. Same reasoning as the favourites screen,
     * and the same string: what the user did is identical, whichever link it was.
     */
    fun onLinkCopied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        viewModelScope.launch { messageChannel.emit(UiMessage(R.string.favourites_link_copied)) }
    }
}
