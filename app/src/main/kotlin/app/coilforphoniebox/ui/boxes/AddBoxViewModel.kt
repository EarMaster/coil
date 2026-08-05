package app.coilforphoniebox.ui.boxes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionTestResult
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.transport.HostDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Adding a box: an mDNS scan, a manual entry field, a connection test, and a name.
 *
 * The audience already owns a Phoniebox and set it up themselves, so this explains the
 * one thing genuinely specific to the app — how to point it at the box — and nothing else
 * (§11.2).
 */
@HiltViewModel
class AddBoxViewModel @Inject constructor(
    private val boxes: BoxRepository,
    private val discovery: HostDiscovery,
) : ViewModel() {

    data class Candidate(val host: String, val serviceName: String)

    enum class TestState { IDLE, RUNNING, REACHABLE, UNREACHABLE }

    data class State(
        val host: String = "",
        val displayName: String = "",
        val rpcPort: String = Box.DEFAULT_RPC_PORT.toString(),
        val pubPort: String = Box.DEFAULT_PUB_PORT.toString(),
        val scanning: Boolean = false,
        val candidates: List<Candidate> = emptyList(),
        val testState: TestState = TestState.IDLE,
        val reportedVersion: String? = null,
        val hostError: Boolean = false,
        val portError: Boolean = false,
        val saved: Boolean = false,
    ) {
        val canSave: Boolean get() = host.isNotBlank() && !portError
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun onHostChange(value: String) = _state.update {
        it.copy(host = value, hostError = false, testState = TestState.IDLE, reportedVersion = null)
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value) }

    fun onRpcPortChange(value: String) = _state.update {
        it.copy(rpcPort = value, portError = !value.isValidPort())
    }

    fun onPubPortChange(value: String) = _state.update {
        it.copy(pubPort = value, portError = !value.isValidPort())
    }

    /**
     * Candidates are anything Avahi announces, which cannot be told apart from any other
     * Raspberry Pi by name alone — so each one is asked for `core.version` and only the
     * ones that answer are offered (§4.4).
     */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, candidates = emptyList()) }

        scanJob = viewModelScope.launch {
            discovery.discover().collect { candidate ->
                val reachable = boxes.testConnection(candidate.host, Box.DEFAULT_RPC_PORT)
                if (reachable is ConnectionTestResult.Reachable) {
                    _state.update { current ->
                        if (current.candidates.any { it.host == candidate.host }) {
                            current
                        } else {
                            current.copy(
                                candidates = current.candidates + Candidate(
                                    host = candidate.host,
                                    serviceName = candidate.serviceName,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    fun selectCandidate(candidate: Candidate) = _state.update {
        it.copy(
            host = candidate.host,
            displayName = it.displayName.ifBlank { candidate.serviceName },
            testState = TestState.REACHABLE,
        )
    }

    fun testConnection() {
        val current = _state.value
        val host = current.host.trim()
        if (host.isEmpty()) {
            _state.update { it.copy(hostError = true) }
            return
        }
        val port = current.rpcPort.toIntOrNull()
        if (port == null || !current.rpcPort.isValidPort()) {
            _state.update { it.copy(portError = true) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(testState = TestState.RUNNING) }
            when (val result = boxes.testConnection(host, port)) {
                is ConnectionTestResult.Reachable -> _state.update {
                    it.copy(testState = TestState.REACHABLE, reportedVersion = result.version)
                }

                ConnectionTestResult.Unreachable -> _state.update {
                    it.copy(testState = TestState.UNREACHABLE, reportedVersion = null)
                }
            }
        }
    }

    fun save() {
        val current = _state.value
        val host = current.host.trim()
        if (host.isEmpty()) {
            _state.update { it.copy(hostError = true) }
            return
        }
        val rpcPort = current.rpcPort.toIntOrNull() ?: Box.DEFAULT_RPC_PORT
        val pubPort = current.pubPort.toIntOrNull() ?: Box.DEFAULT_PUB_PORT

        viewModelScope.launch {
            boxes.add(
                // An unnamed box takes its host as its name, which is better than a blank
                // row in the switcher.
                displayName = current.displayName.trim().ifBlank { host },
                host = host,
                rpcPort = rpcPort,
                pubPort = pubPort,
            )
            _state.update { it.copy(saved = true) }
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    private fun String.isValidPort(): Boolean = toIntOrNull()?.let { it in 1..65535 } == true
}
