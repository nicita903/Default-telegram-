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
    val guard = Any()
    // Seed before registration; never overwrite a newer callback with a startup snapshot.
    var current = manager.activeNetwork
    var capabilities = current?.let(manager::getNetworkCapabilities)
    var blocked = false
    fun publish() {
        val caps = capabilities
        val type = when {
            current == null || blocked -> "networkTypeNone"
            caps == null -> "networkTypeOther" // Unknown capabilities are not evidence of no network.
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "networkTypeNone"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "networkTypeOther"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "networkTypeWiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "networkTypeMobile"
            else -> "networkTypeOther"
        }
        trySend(TelegramNetworkHint(current?.networkHandle, type, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
    }
    publish()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(guard) {
            if (current != network) { current = network; capabilities = null; blocked = false }
            // Android guarantees a capability callback next. Do not query synchronous snapshots here
            // or synthesize None between the old and new default network.
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = synchronized(guard) {
            if (network == current) { capabilities = caps; publish() }
        }
        override fun onLost(network: Network) = synchronized(guard) {
            // Losing the OLD default during a handover must not disconnect the NEW one.
            if (network == current) { current = null; capabilities = null; blocked = false; publish() }
        }
        override fun onBlockedStatusChanged(network: Network, value: Boolean) = synchronized(guard) {
            if (network == current) { blocked = value; if (capabilities != null || blocked) publish() }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.buffer(Channel.CONFLATED).distinctUntilChanged { previous, current ->
    // Validation may be absent on a VPN/proxy network that still reaches Telegram.
    previous.network == current.network && previous.type == current.type
}
