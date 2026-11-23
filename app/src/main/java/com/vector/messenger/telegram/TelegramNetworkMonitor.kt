package com.vector.messenger.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Transport hints only. TDLib's connectionState remains the authority on Telegram reachability. */
internal data class TelegramNetworkHint(val network: Long?, val type: String, val validated: Boolean)

internal fun telegramNetworkChanges(context: Context): Flow<TelegramNetworkHint> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val blockedNetworks = mutableSetOf<Network>()
    fun publish(lost: Network? = null) {
        val network = manager.activeNetwork?.takeUnless { it == lost }
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val internet = network !in blockedNetworks && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            !internet -> "networkTypeNone"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "networkTypeOther"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "networkTypeWiFi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "networkTypeMobile"
            else -> "networkTypeOther"
        }
        // An unvalidated network can still reach Telegram (for example through a proxy).
        // Do not synthesize Ready or block TDLib's own connectivity checks on validation.
        trySend(TelegramNetworkHint(network?.networkHandle, type,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { publish() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == manager.activeNetwork) publish()
        }
        override fun onLost(network: Network) { blockedNetworks.remove(network); publish(lost = network) }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (blocked) blockedNetworks.add(network) else blockedNetworks.remove(network)
            if (network == manager.activeNetwork) publish()
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    try { publish(); awaitClose() }
    finally { manager.unregisterNetworkCallback(callback) }
}.buffer(Channel.CONFLATED).distinctUntilChanged { previous, current ->
    previous.network == current.network && previous.type == current.type
}
