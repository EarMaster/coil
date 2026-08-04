package app.coilforphoniebox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.domain.repository.BackupRepository
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import app.coilforphoniebox.media.AutoSessionStarter
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

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val boxes: BoxRepository,
    private val library: LibraryRepository,
    private val player: PlayerRepository,
    private val backup: BackupRepository,
    private val autoSession: AutoSessionStarter,
) : ViewModel() {

    data class State(
        val settings: AppSettings = AppSettings(),
        val activeBox: Box? = null,
        /** Every configured box, so this screen can switch between them and add one. */
        val boxes: List<Box> = emptyList(),
        val boxVersion: String? = null,
    )

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    val state: StateFlow<State> = combine(
        settings.settings,
        boxes.activeBox,
        boxes.boxes,
        player.boxVersion,
    ) { appSettings, activeBox, allBoxes, version ->
        State(
            settings = appSettings,
            activeBox = activeBox,
            boxes = allBoxes,
            boxVersion = version,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /**
     * Makes another box the active one. The connection follows on its own — the transport
     * watches the active box and rebuilds its sockets (§7.3) — and every per-box row on this
     * screen then describes the box that was just picked.
     */
    fun selectBox(boxId: String) {
        if (boxId == state.value.activeBox?.id) return
        viewModelScope.launch { boxes.setActive(boxId) }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { settings.setDynamicColor(enabled) }

    fun setSessionMode(mode: SessionMode) {
        viewModelScope.launch {
            settings.setSessionMode(mode)
            // Switching to automatic should take effect now, not after the next reboot.
            if (mode == SessionMode.AUTOMATIC) autoSession.startIfEnabled()
        }
    }

    fun setAutoSessionForActiveBox(enabled: Boolean) {
        val box = state.value.activeBox ?: return
        viewModelScope.launch {
            boxes.update(box.copy(autoSessionEnabled = enabled))
            if (enabled) autoSession.startIfEnabled()
        }
    }

    /**
     * Fires `player.ctrl.update` so the box rescans its own database. `update_wait` would
     * block the box's RPC loop until it finished, which is why it stays unused (§6.4).
     */
    fun rescanLibrary() {
        val boxId = state.value.activeBox?.id ?: return
        viewModelScope.launch {
            library.rescanBoxLibrary(boxId)
                .onSuccess { messageChannel.emit(UiMessage(R.string.settings_rescan_started)) }
                .onFailure { messageChannel.emit(UiMessage(R.string.error_not_connected)) }
        }
    }

    fun removeActiveBox() {
        val box = state.value.activeBox ?: return
        viewModelScope.launch { boxes.delete(box.id) }
    }

    fun updateActiveBox(host: String, rpcPort: Int, pubPort: Int, displayName: String) {
        val box = state.value.activeBox ?: return
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

    /** Returns the JSON to write; the screen owns the file picker. */
    suspend fun exportSettings(): String = backup.export()

    fun importSettings(content: String) {
        viewModelScope.launch {
            backup.importFrom(content)
                .onSuccess { messageChannel.emit(UiMessage(R.string.settings_import_done)) }
                .onFailure { messageChannel.emit(UiMessage(R.string.settings_import_failed)) }
        }
    }

    fun onExported() {
        viewModelScope.launch { messageChannel.emit(UiMessage(R.string.settings_export_done)) }
    }
}
