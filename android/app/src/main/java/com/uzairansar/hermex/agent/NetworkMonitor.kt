package com.uzairansar.hermex.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MURF: system network state via ConnectivityManager.NetworkCallback.
 * [regained] fires as soon as a (new) default network becomes available, so the chat can
 * reconnect immediately instead of waiting for the next backoff step.
 * Until [init] is called (unit tests) the device is assumed online and nothing is emitted.
 */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _regained = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val regained: SharedFlow<Long> = _regained.asSharedFlow()

    @Volatile private var registered = false
    @Volatile private var currentNetwork: Network? = null

    val isOnline: Boolean get() = _online.value

    fun init(context: Context) {
        if (registered) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            val active = manager.activeNetwork
            currentNetwork = active
            _online.value = active != null && manager.getNetworkCapabilities(active).hasInternet()
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val wasOnline = _online.value
                        val changed = currentNetwork != network
                        currentNetwork = network
                        _online.value = true
                        if (!wasOnline || changed) _regained.tryEmit(System.currentTimeMillis())
                    }

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (network != currentNetwork) return
                        val nowOnline = capabilities.hasInternet()
                        val wasOnline = _online.value
                        _online.value = nowOnline
                        if (nowOnline && !wasOnline) _regained.tryEmit(System.currentTimeMillis())
                    }

                    override fun onLost(network: Network) {
                        if (network != currentNetwork) return
                        currentNetwork = null
                        _online.value = false
                    }
                },
            )
            registered = true
        }
    }

    private fun NetworkCapabilities?.hasInternet(): Boolean =
        this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** Test hook. */
    internal fun setOnlineForTest(value: Boolean) {
        val was = _online.value
        _online.value = value
        if (value && !was) _regained.tryEmit(System.currentTimeMillis())
    }
}
