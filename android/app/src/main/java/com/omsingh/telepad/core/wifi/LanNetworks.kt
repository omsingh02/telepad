package com.omsingh.telepad.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramSocket

/**
 * Finds the local network (Wi-Fi or Ethernet) and pins sockets to it.
 *
 * Without this, a phone on a Wi-Fi network that has no internet (a common home
 * router or office setup) sends traffic for the PC out of mobile data, because Android
 * routes unpinned sockets over whatever network it considers "default". The PC is then
 * never reached. A VPN does the same by swallowing local traffic. Pinning the socket to
 * the Wi-Fi network avoids both.
 */
class LanNetworks(context: Context) {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** The Wi-Fi network (else Ethernet) that is not a VPN, or null if there is none. */
    @Suppress("DEPRECATION")
    fun current(): Network? {
        val cm = connectivity ?: return null
        return try {
            val candidates = cm.allNetworks.mapNotNull { network ->
                cm.getNetworkCapabilities(network)?.let { network to it }
            }.filter { (_, caps) -> !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            candidates.firstOrNull { (_, caps) -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.first
                ?: candidates.firstOrNull { (_, caps) -> caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }?.first
        } catch (_: SecurityException) {
            null
        }
    }

    /** Pins [socket] to the local network. A failure is harmless: the socket just stays unpinned. */
    fun bind(socket: DatagramSocket) {
        val network = current() ?: return
        try {
            network.bindSocket(socket)
        } catch (t: Throwable) {
            Log.d(TAG, "could not bind a socket to the local network: ${t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "LanNetworks"
    }
}
