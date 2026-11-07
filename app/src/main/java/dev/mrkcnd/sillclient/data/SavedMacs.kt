package dev.mrkcnd.sillclient.data

import android.content.Context
import dev.mrkcnd.sillclient.protocol.Base64Url
import org.json.JSONArray
import org.json.JSONObject

/** A Mac this device paired with: its pinned key, the recognition key for its Bonjour tag, and where it was last reached. */
data class SavedMac(
    val macID: String,
    val fingerprint: String,          // base64url SHA-256 of the Mac's SPKI
    val name: String,
    val recognitionKey: String?,      // base64url, 32 bytes
    val remotePort: Int = 7455,
    val remoteHosts: List<String> = emptyList(),
    val manualHost: String? = null,   // typed by the person (host or host:port)
    /** The Mac's home-network addresses: tried last from away, through a VPN into the home network (WireGuard). */
    val lanHosts: List<String> = emptyList(),
) {
    val pin: ByteArray? get() = Base64Url.decode(fingerprint)
    val recognitionKeyBytes: ByteArray? get() = recognitionKey?.let { Base64Url.decode(it) }

    fun json(): JSONObject = JSONObject()
        .put("macID", macID).put("fingerprint", fingerprint).put("name", name)
        .put("recognitionKey", recognitionKey).put("remotePort", remotePort)
        .put("remoteHosts", JSONArray(remoteHosts)).put("manualHost", manualHost)
        .put("lanHosts", JSONArray(lanHosts))

    companion object {
        fun parse(o: JSONObject): SavedMac? = runCatching {
            val hosts = o.optJSONArray("remoteHosts")
            val lan = o.optJSONArray("lanHosts")
            SavedMac(
                o.getString("macID"), o.getString("fingerprint"), o.optString("name", "Mac"),
                o.optString("recognitionKey").takeIf { it.isNotEmpty() && it != "null" },
                o.optInt("remotePort", 7455),
                (0 until (hosts?.length() ?: 0)).map { hosts!!.getString(it) },
                o.optString("manualHost").takeIf { it.isNotEmpty() && it != "null" },
                (0 until (lan?.length() ?: 0)).map { lan!!.getString(it) },
            )
        }.getOrNull()
    }
}

class SavedMacs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("macs", Context.MODE_PRIVATE)

    fun all(): List<SavedMac> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).mapNotNull { SavedMac.parse(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(mac: SavedMac) {
        val list = all().filterNot { it.macID == mac.macID } + mac
        write(list)
    }

    fun update(macID: String, change: (SavedMac) -> SavedMac) {
        val list = all().map { if (it.macID == macID) change(it) else it }
        write(list)
    }

    fun remove(macID: String) = write(all().filterNot { it.macID == macID })

    private fun write(list: List<SavedMac>) {
        prefs.edit().putString("list", JSONArray(list.map { it.json() }).toString()).apply()
    }
}
