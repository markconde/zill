package dev.mrkcnd.sillclient.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.mrkcnd.sillclient.protocol.HomeDoor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress

/** A Mac found on the local network: Bonjour `_sill._tcp` with its TXT record. */
data class FoundMac(
    val serviceName: String,
    val host: String,
    val port: Int,
    val door: HomeDoor,
    val tag: String?,          // TXT r: the recognition tag a paired device matches
)

/**
 * Browses `_sill._tcp` with NsdManager. Resolves one service at a time (older Android refuses a
 * second concurrent resolve) and holds a multicast lock while browsing.
 */
class Discovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private val _found = MutableStateFlow<List<FoundMac>>(emptyList())
    val found: StateFlow<List<FoundMac>> = _found

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = wifi.createMulticastLock("sill-discovery").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { Log.w(TAG, "browse failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@Discovery) { _found.value = _found.value.filterNot { it.serviceName == info.serviceName } }
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.let { runCatching { it.release() } }
        lock = null
        synchronized(this) { pending.clear(); resolving = false }
        _found.value = emptyList()
    }

    /** Browse again from scratch (pull to refresh). */
    fun restart() { stop(); start() }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(this) {
            pending.addLast(info)
            if (resolving) return
            resolving = true
        }
        next()
    }

    private fun next() {
        val info = synchronized(this) {
            val n = pending.removeFirstOrNull()
            if (n == null) resolving = false
            n
        } ?: return
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "resolve ${serviceInfo?.serviceName} failed $errorCode")
                next()
            }

            override fun onServiceResolved(r: NsdServiceInfo) {
                val txt = r.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } }
                val address = pickAddress(r)
                if (address != null) {
                    val mac = FoundMac(r.serviceName, address, r.port, HomeDoor.from(txt), txt["r"])
                    synchronized(this@Discovery) {
                        _found.value = _found.value.filterNot { it.serviceName == mac.serviceName } + mac
                    }
                }
                next()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(r: NsdServiceInfo): String? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host)
        val a = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() ?: return null
        return a.hostAddress?.substringBefore('%')?.takeIf { a is Inet4Address } ?: a.hostAddress
    }

    companion object {
        const val SERVICE_TYPE = "_sill._tcp."
        private const val TAG = "SillDiscovery"
    }
}
