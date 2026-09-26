package app.astiko.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device has a VALIDATED internet connection.
 *
 * "Validated" (NET_CAPABILITY_VALIDATED) means the OS confirmed real
 * internet access. A Wi-Fi network without internet (captive portal,
 * router down) is not online. This is the single signal behind the
 * offline banner and the cache's "skip the network, serve the cache"
 * fast path.
 *
 * Deliberately not a hard gate. The cached repository also falls back
 * on fetch exceptions, since "no validated network" does not mean the
 * API is down (rate limits, provider outages). Both paths converge on
 * the same cache.
 *
 * Requires ACCESS_NETWORK_STATE (see AndroidManifest.xml).
 */
class ConnectivityMonitor(
    context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Callbacks and [resync] both run here, so the tracked network and
     *  [online] have one writer thread. */
    private val handler = Handler(Looper.getMainLooper())

    private val _online = MutableStateFlow(isValidated(activeCapabilities()))
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var currentNetwork: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // getNetworkCapabilities is racy inside this callback, and
                // onCapabilitiesChanged follows with that network's caps.
                currentNetwork = network
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                // Only the network we're currently tracking decides the state
                // (a second interface's change must not flip it).
                if (network == currentNetwork) {
                    _online.value = isValidated(capabilities)
                }
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    _online.value = false
                }
            }

            override fun onUnavailable() {
                currentNetwork = null
                _online.value = false
            }
        }

    init {
        // The callback fires immediately for the current network and then
        // follows every change (airplane mode, Wi-Fi loss, connectivity
        // return). Registered on the main looper so resync shares its thread.
        connectivityManager.registerDefaultNetworkCallback(callback, handler)
    }

    /**
     * Re-reads the current state from the platform, the same source the
     * callbacks report. A frozen process can miss a whole network
     * transition, and the offline state would stay stale for the rest of
     * the process's life.
     */
    fun resync() {
        handler.post {
            val active = connectivityManager.activeNetwork
            currentNetwork = active
            _online.value =
                isValidated(
                    active?.let { connectivityManager.getNetworkCapabilities(it) },
                )
        }
    }

    private fun activeCapabilities(): NetworkCapabilities? =
        connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

    /** Whether the active network is validated Wi-Fi. The "download city"
     *  prefetch is Wi-Fi-only. */
    fun onValidatedWifi(): Boolean =
        activeCapabilities()?.let { caps ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

    private fun isValidated(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
