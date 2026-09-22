package com.omsingh.telepad.core.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Optional mDNS / DNS-SD-based discovery via Android's NsdManager.
 *
 * **Why a third discovery channel** (on top of multicast + manual subnet scan)?
 *  - The mDNS multicast address `224.0.0.251` is special-cased by every Wi-Fi
 *    AP and router in production — packets there are reliably forwarded.
 *  - Android's NsdManager handles MulticastLock acquisition, interface
 *    selection, and lifecycle internally — fewer things we can get wrong.
 *  - Many users already run mDNS-aware tools (printer apps, Bonjour, etc.),
 *    so service-discovery infrastructure is "warm" on most home networks.
 *
 * **Service type:** `_telepad._udp.` — IANA hasn't registered this so the
 * underscore prefix is conventionally what you use for unregistered service
 * types (per RFC 6335 §5.1).
 *
 * The desktop server doesn't *have* to publish via mDNS for Telepad to work —
 * multicast + manual scan still find it. mDNS is the icing on the cake.
 */
class NsdHelper(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _services = MutableStateFlow<List<ServerInfo>>(emptyList())
    val services: StateFlow<List<ServerInfo>> = _services.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        if (listener != null) return
        listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, code: Int) {
                Log.w(TAG, "discovery start failed: $code")
            }
            override fun onStopDiscoveryFailed(t: String?, code: Int) {
                Log.w(TAG, "discovery stop failed: $code")
            }
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}

            override fun onServiceFound(svc: NsdServiceInfo) {
                if (!svc.serviceType.contains(SERVICE_TYPE_FRAGMENT)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(svc, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo?, c: Int) {
                        Log.w(TAG, "resolve failed code=$c")
                    }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = if (android.os.Build.VERSION.SDK_INT >= 34) {
                            s.hostAddresses.firstOrNull()?.hostAddress
                        } else {
                            @Suppress("DEPRECATION")
                            s.host?.hostAddress
                        } ?: return
                        val info = ServerInfo(
                            name = s.serviceName.ifBlank { host },
                            host = host,
                            port = s.port
                        )
                        _services.update { cur ->
                            if (cur.any { it.host == host }) cur else cur + info
                        }
                    }
                })
            }

            override fun onServiceLost(svc: NsdServiceInfo) {
                _services.update { it.filterNot { existing -> existing.name == svc.serviceName } }
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD discoverServices threw", t)
            listener = null
        }
    }

    fun stopDiscovery() {
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        listener = null
        _services.value = emptyList()
    }

    private companion object {
        const val TAG = "NsdHelper"
        const val SERVICE_TYPE = "_telepad._udp."
        const val SERVICE_TYPE_FRAGMENT = "_telepad"
    }
}
