package app.coilforphoniebox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.LibraryIndexResult
import app.coilforphoniebox.domain.model.LibraryIndexState
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
import kotlinx.coroutines.Job
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
        /** Every configured box, so the row leading to box management can name them. */
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

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { settings.setDynamicColor(enabled) }

    fun setLoadExternalCoverArt(enabled: Boolean) =
        viewModelScope.launch { settings.setLoadExternalCoverArt(enabled) }

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

    /** Progress of the library crawl, so the row can show a count and a way to stop. */
    val indexState: StateFlow<LibraryIndexState> = library.indexState

    private var indexJob: Job? = null

    /**
     * Walks the whole folder tree so that search covers folders nobody has opened.
     *
     * Started by hand and never on its own: it occupies the box's sequential RPC loop, which
     * its card reader shares, so the user decides when that is acceptable (§6). Every outcome
     * is reported — including a library too large for the crawl's cap, which leaves search
     * genuinely incomplete and must not look like success.
     */
    fun indexLibrary() {
        val boxId = state.value.activeBox?.id ?: return
        if (indexJob?.isActive == true) return

        indexJob = viewModelScope.launch {
            when (val result = library.indexLibrary(boxId)) {
                is LibraryIndexResult.Finished -> messageChannel.emit(
                    if (result.stoppedAtCap) {
                        UiMessage(R.string.settings_index_capped, result.foldersScanned.toString())
                    } else {
                        UiMessage(R.string.settings_index_done, result.foldersScanned.toString())
                    },
                )

                LibraryIndexResult.BoxBusy ->
                    messageChannel.emit(UiMessage(R.string.settings_index_box_busy))

                is LibraryIndexResult.Interrupted -> messageChannel.emit(
                    UiMessage(R.string.settings_index_interrupted, result.foldersScanned.toString()),
                )
            }
        }
    }

    /** Stops the crawl. Whatever it reached is already cached and stays searchable. */
    fun stopIndexing() {
        indexJob?.cancel()
        indexJob = null
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
