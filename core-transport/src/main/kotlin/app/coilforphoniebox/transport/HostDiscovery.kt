package app.coilforphoniebox.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS scan for boxes on the local network.
 *
 * Convenience only — manual entry stays the primary path (§4.4). Avahi runs on
 * Raspberry Pi OS by default, so a Phoniebox usually announces itself as a workstation
 * and, if its web server registers one, as an HTTP service.
 *
 * Nothing here can tell a Phoniebox from any other Raspberry Pi. Candidates are
 * confirmed by asking them for `core.version` — see [BoxProbe] — which is both cheap
 * and conclusive.
 */
@Singleton
class HostDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Candidate(val serviceName: String, val host: String)

    fun discover(): Flow<Candidate> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            close()
            return@callbackFlow
        }

        // NsdManager tolerates exactly one resolve at a time on older releases, so
        // requests are funnelled through a single consumer rather than fired at once.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = HashSet<String>()

        launch {
            for (service in toResolve) {
                resolve(nsdManager, service)?.let { candidate ->
                    if (seen.add(candidate.host)) trySend(candidate)
                }
            }
        }

        val listeners = SERVICE_TYPES.map { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    toResolve.trySend(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

                override fun onDiscoveryStopped(serviceType: String?) = Unit

                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "Discovery of $serviceType failed with $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            }
            runCatching {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(TAG, "Could not start discovery for $type", it) }
            type to listener
        }

        awaitClose {
            listeners.forEach { (_, listener) ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            toResolve.close()
        }
    }

    private suspend fun resolve(nsdManager: NsdManager, service: NsdServiceInfo): Candidate? {
        val resolved = Channel<NsdServiceInfo?>(1)

        @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34; minSdk is 26.
        nsdManager.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    resolved.trySend(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolved.trySend(serviceInfo)
                }
            },
        )

        val info = resolved.receive() ?: return null

        // IPv4 only: the box's address is typed into a text field and shown back to the
        // user, and a link-local IPv6 address with a scope id is neither.
        @Suppress("DEPRECATION") // getHostAddresses() needs API 34.
        val address = info.host as? Inet4Address ?: return null
        return Candidate(
            serviceName = info.serviceName?.takeIf { it.isNotBlank() } ?: address.hostAddress.orEmpty(),
            host = address.hostAddress ?: return null,
        )
    }

    private companion object {
        const val TAG = "CoilDiscovery"

        /** Avahi on Raspberry Pi OS registers the first; a web server may add the second. */
        val SERVICE_TYPES = listOf("_workstation._tcp.", "_http._tcp.")
    }
}
