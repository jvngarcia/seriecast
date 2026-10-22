package com.seriecast.app.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** The LAN interface SSDP multicast must be bound to. */
data class LanBinding(
    val interfaceName: String,
    val address: Inet4Address,
    val networkInterface: NetworkInterface,
    val transport: String,
) {
    val hostAddress: String get() = address.hostAddress ?: "0.0.0.0"
}

sealed interface LanState {
    data class Ready(val binding: LanBinding) : LanState
    data object NoNetwork : LanState
    data class NotLocalNetwork(val transport: String) : LanState
    data class NoIpv4(val interfaceName: String) : LanState
}

object LanInfo {

    /**
     * Resolves the IPv4 address and NetworkInterface of the active Wi-Fi/Ethernet network.
     *
     * A television on the same access point needs a real LAN address; mobile data is rejected
     * because SSDP multicast does not work there and the TV would never be able to reach us.
     */
    fun resolve(context: Context): LanState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return LanState.NoNetwork
        val network = cm.activeNetwork ?: return LanState.NoNetwork
        val caps = cm.getNetworkCapabilities(network) ?: return LanState.NoNetwork

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> return LanState.NotLocalNetwork(
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "unknown"
                },
            )
        }

        val linkProperties = cm.getLinkProperties(network) ?: return LanState.NoNetwork
        val ifName = linkProperties.interfaceName ?: return LanState.NoNetwork
        val nif = NetworkInterface.getByName(ifName) ?: return LanState.NoIpv4(ifName)

        // Prefer the address the platform associates with this Network, not the first on the NIC.
        val address = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: return LanState.NoIpv4(ifName)

        return LanState.Ready(LanBinding(ifName, address, nif, transport))
    }
}
