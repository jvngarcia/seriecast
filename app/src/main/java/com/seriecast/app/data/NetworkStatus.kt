package com.seriecast.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Download-side connectivity check. Being on Wi-Fi without internet is fine for the server but not for downloading. */
object NetworkStatus {

    data class Snapshot(val online: Boolean, val unmetered: Boolean, val description: String)

    fun check(context: Context): Snapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return Snapshot(false, false, "sin servicio de conectividad")
        val network = cm.activeNetwork ?: return Snapshot(false, false, "sin red activa")
        val caps = cm.getNetworkCapabilities(network)
            ?: return Snapshot(false, false, "sin capacidades de red")
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "red desconocida"
        }
        return Snapshot(online, unmetered, transport)
    }
}
