package dev.mrkcnd.sillclient.ui

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrkcnd.sillclient.data.SavedMac
import dev.mrkcnd.sillclient.data.SavedMacs
import dev.mrkcnd.sillclient.net.DeviceIdentity
import dev.mrkcnd.sillclient.net.Discovery
import dev.mrkcnd.sillclient.net.FoundMac
import dev.mrkcnd.sillclient.net.PairingClient
import dev.mrkcnd.sillclient.net.Route
import dev.mrkcnd.sillclient.net.StreamSession
import dev.mrkcnd.sillclient.net.Tls
import dev.mrkcnd.sillclient.protocol.HomeDoor
import dev.mrkcnd.sillclient.protocol.MacAddress
import dev.mrkcnd.sillclient.protocol.PairLink
import dev.mrkcnd.sillclient.protocol.PairingCode
import dev.mrkcnd.sillclient.protocol.RecognitionTag
import dev.mrkcnd.sillclient.protocol.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** One row of the connect screen. */
data class MacRow(
    val title: String,
    val found: FoundMac?,
    val saved: SavedMac?,
) {
    val key: String get() = saved?.macID ?: "found:${found?.serviceName}"
    val subtitle: String get() = when {
        found != null && saved != null -> "Paired · on this network"
        found != null && found.door == HomeDoor.PLAIN -> "On this network · unencrypted (an older Mac host)"
        found != null && found.door == HomeDoor.OPEN -> "On this network · pairing not required"
        found != null -> "On this network · not paired"
        saved?.manualHost != null || saved?.remoteHosts?.isNotEmpty() == true || saved?.lanHosts?.isNotEmpty() == true -> "Paired · away (Remote Access)"
        else -> "Paired · not seen nearby"
    }
}

sealed class PairingUi {
    object None : PairingUi()
    data class Busy(val text: String) : PairingUi()
    /** The Mac shows a code: type it here, or paste its pairing link. */
    data class EnterCode(
        val macName: String,
        val host: String,
        val port: Int,
        val pin: ByteArray?,
        val error: String? = null,
        val fromAsk: Boolean,
    ) : PairingUi()
    data class Failed(val text: String) : PairingUi()
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val store = SavedMacs(app)
    private val identity by lazy { DeviceIdentity.get(app) }
    val discovery = Discovery(app)

    val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    private val appVersion = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "0.1.0"

    private val saved = MutableStateFlow(store.all())
    val pairing = MutableStateFlow<PairingUi>(PairingUi.None)
    val session = MutableStateFlow<StreamSession?>(null)
    val sessionTitle = MutableStateFlow("")

    val rows: StateFlow<List<MacRow>> = combine(saved, discovery.found) { s, f ->
        val matched = HashSet<String>()
        val nearby = f.sortedBy { it.serviceName.lowercase() }.map { fm ->
            val mac = fm.tag?.let { tag -> s.firstOrNull { m -> m.recognitionKeyBytes?.let { RecognitionTag.matches(tag, it) } == true } }
            if (mac != null) matched += mac.macID
            MacRow(mac?.name ?: fm.serviceName, fm, mac)
        }
        nearby + s.filter { it.macID !in matched }.sortedBy { it.name.lowercase() }.map { MacRow(it.name, null, it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var macInfoJob: Job? = null

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun refresh() = discovery.restart()

    // MARK: connecting

    fun connect(row: MacRow) {
        val f = row.found
        val s = row.saved
        when {
            f != null && s != null -> open(Route(f.host, f.port, f.door != HomeDoor.PLAIN, s.pin, "Wi-Fi"), s.name, s.macID)
            f != null && f.door == HomeDoor.PLAIN -> open(Route(f.host, f.port, false, null, "Wi-Fi"), f.serviceName, null)
            f != null && f.door == HomeDoor.OPEN -> open(Route(f.host, f.port, true, null, "Wi-Fi"), f.serviceName, null)
            f != null -> ask(f)
            s != null -> connectAway(s)
        }
    }

    private fun ask(f: FoundMac) {
        pairing.value = PairingUi.Busy("Asking ${f.serviceName} to pair…")
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { PairingClient(identity, deviceName).ask(f.host, f.port) }
                    .getOrElse { PairingClient.Outcome.Failed("Couldn't reach ${f.serviceName}: ${it.message}") }
            }
            pairing.value = when (outcome) {
                is PairingClient.Outcome.CodeShown -> PairingUi.EnterCode(f.serviceName, f.host, f.port, outcome.macFingerprint, fromAsk = true)
                is PairingClient.Outcome.Failed -> PairingUi.Failed(outcome.text)
                is PairingClient.Outcome.Paired -> { paired(outcome.mac, null); PairingUi.None }
            }
        }
    }

    /** The code (or a pasted sill://pair link) typed into the pairing dialog. */
    fun submitCode(input: String) {
        val p = pairing.value as? PairingUi.EnterCode ?: return
        val link = PairLink.parse(input)
        val code = if (link == null) {
            when (val c = PairingCode.check(input)) {
                is PairingCode.Check.Ok -> c.code
                PairingCode.Check.Length -> { pairing.value = p.copy(error = "The code is 12 digits."); return }
                PairingCode.Check.Typo -> { pairing.value = p.copy(error = "That code has a typo. Check the digits."); return }
            }
        } else null
        pairing.value = PairingUi.Busy(if (link != null) "Pairing with ${link.name}…" else "Checking the code…")
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val client = PairingClient(identity, deviceName)
                    if (link != null) client.pairWithLink(link, p.host, p.port)
                    else client.pairWithCode(p.host, p.port, code!!, p.pin)
                }.getOrElse { PairingClient.Outcome.Failed("Pairing failed: ${it.message}") }
            }
            when (outcome) {
                is PairingClient.Outcome.Paired -> {
                    pairing.value = PairingUi.None
                    val manual = if (p.fromAsk) null else hostPort(p.host, p.port)
                    paired(outcome.mac.copy(manualHost = manual, remotePort = if (p.fromAsk) 7455 else p.port),
                        if (p.fromAsk) Route(p.host, p.port, true, outcome.mac.pin, "Wi-Fi") else null)
                }
                is PairingClient.Outcome.Failed -> {
                    val r = outcome.result
                    pairing.value = if (r?.reason == "code") p.copy(error = outcome.text) else PairingUi.Failed(outcome.text)
                }
                is PairingClient.Outcome.CodeShown -> pairing.value = p
            }
        }
    }

    fun cancelPairing() {
        val p = pairing.value
        pairing.value = PairingUi.None
        if (p is PairingUi.EnterCode && p.fromAsk) {
            viewModelScope.launch(Dispatchers.IO) { PairingClient(identity, deviceName).cancel(p.host, p.port, p.pin) }
        }
    }

    /** Add a Mac by its address (Remote Access over a VPN, or a Mac Bonjour can't see). */
    fun addByAddress(address: String) {
        val trimmed = address.trim()
        val link = PairLink.parse(trimmed)
        if (link != null) {
            val host = link.addresses.firstOrNull() ?: run {
                pairing.value = PairingUi.Failed("That link has no address. Type the Mac's address instead."); return
            }
            val (h, port) = splitHostPort(host, link.port)
            pairing.value = PairingUi.EnterCode(link.name, h, port, link.fingerprint, fromAsk = false)
            submitCode(trimmed)
            return
        }
        val (h, port) = splitHostPort(trimmed, 7455)
        if (h.isEmpty()) { pairing.value = PairingUi.Failed("Type the Mac's address, such as 100.101.102.103 or mac.tailnet.ts.net."); return }
        val known = saved.value
        if (known.isEmpty()) { pairing.value = PairingUi.EnterCode(h, h, port, null, fromAsk = false); return }
        // A Mac this device already paired with, at an address it didn't know (its home address
        // through WireGuard, say): one handshake reads its key, and a match connects without a code.
        pairing.value = PairingUi.Busy("Looking for a Mac at ${hostPort(h, port)}…")
        viewModelScope.launch {
            val seen = withContext(Dispatchers.IO) {
                runCatching { Tls.connect(h, port, Tls.SESSION_ALPN, null, identity).let { c -> runCatching { c.socket.close() }; c.macFingerprint } }
            }
            val fp = seen.getOrNull()
            val mac = fp?.let { f -> known.firstOrNull { m -> m.pin?.let { MessageDigest.isEqual(it, f) } == true } }
            when {
                mac != null -> {
                    pairing.value = PairingUi.None
                    store.update(mac.macID) { it.copy(manualHost = hostPort(h, port)) }
                    saved.value = store.all()
                    open(Route(h, port, true, mac.pin, "Remote"), mac.name, mac.macID)
                }
                seen.isFailure -> pairing.value = PairingUi.Failed("Couldn't reach a Mac at ${hostPort(h, port)}: ${seen.exceptionOrNull()?.message}. " +
                    "Is Remote Access on in Sill on the Mac, and is the VPN connected?")
                else -> pairing.value = PairingUi.EnterCode(h, h, port, fp, fromAsk = false)
            }
        }
    }

    private fun paired(mac: SavedMac, then: Route?) {
        store.save(mac)
        saved.value = store.all()
        if (then != null) open(then, mac.name, mac.macID)
        else connectAway(mac)
    }

    private fun connectAway(mac: SavedMac) {
        val candidates = buildList {
            mac.manualHost?.let { add(splitHostPort(it, mac.remotePort)) }
            mac.remoteHosts.forEach { add(splitHostPort(it, mac.remotePort)) }
            // Last, as Sill's iPhone app does: the home addresses, reached through a VPN into the home network.
            mac.lanHosts.forEach { add(splitHostPort(it, mac.remotePort)) }
        }.distinct()
        if (candidates.isEmpty()) {
            pairing.value = PairingUi.Failed("${mac.name} isn't on this network, and it hasn't told this device how to reach it from afar. " +
                "Turn on Remote Access in Sill on the Mac and connect once at home, or use Add a Mac with its address.")
            return
        }
        // One address: dial it. Several: a short TCP probe picks the first that answers (a Mac's door
        // counts a connection that leaves unadmitted against its address, so probe only when needed).
        if (candidates.size == 1) {
            val (h, p) = candidates.single()
            open(Route(h, p, true, mac.pin, "Remote"), mac.name, mac.macID)
            return
        }
        pairing.value = PairingUi.Busy("Looking for ${mac.name}…")
        viewModelScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                candidates.firstOrNull { (h, p) ->
                    runCatching { Socket().use { it.connect(InetSocketAddress(h, p), 3000) }; true }.getOrDefault(false)
                }
            }
            pairing.value = PairingUi.None
            if (reachable == null) {
                pairing.value = PairingUi.Failed("Couldn't reach ${mac.name} at ${candidates.joinToString { hostPort(it.first, it.second) }}.")
            } else {
                open(Route(reachable.first, reachable.second, true, mac.pin, "Remote"), mac.name, mac.macID)
            }
        }
    }

    private fun open(route: Route, title: String, macID: String?) {
        session.value?.close()
        val s = StreamSession(route, identity, deviceName, appVersion, "1")
        sessionTitle.value = title
        session.value = s
        s.start(StreamSource.Desktop)
        macInfoJob?.cancel()
        if (macID != null) {
            macInfoJob = viewModelScope.launch {
                s.macInfo.collect { info ->
                    if (info == null || info.macID != macID) return@collect
                    fun text(a: MacAddress) = a.port?.let { hostPort(a.host, it) } ?: a.host
                    val (lan, away) = info.addresses.partition { it.kind == "lan" }
                    store.update(macID) {
                        it.copy(remoteHosts = away.map(::text), lanHosts = lan.map(::text), remotePort = info.remotePort, name = info.name.ifBlank { it.name })
                    }
                    saved.value = store.all()
                }
            }
        }
    }

    fun disconnect() {
        macInfoJob?.cancel()
        session.value?.close()
        session.value = null
    }

    fun forget(mac: SavedMac) {
        store.remove(mac.macID)
        saved.value = store.all()
    }

    fun dismissPairing() { pairing.value = PairingUi.None }

    override fun onCleared() {
        disconnect()
        discovery.stop()
    }

    companion object {
        fun splitHostPort(text: String, defaultPort: Int): Pair<String, Int> {
            val t = text.trim()
            if (t.startsWith("[")) {
                val end = t.indexOf(']')
                if (end > 0) {
                    val host = t.substring(1, end)
                    val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                    return host to port
                }
            }
            val colons = t.count { it == ':' }
            if (colons == 1) {
                val (h, p) = t.split(':')
                return h to (p.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort)
            }
            return t to defaultPort
        }

        fun hostPort(host: String, port: Int) = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }
}
