package dev.mrkcnd.zill.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Mac's menus (Sill's MacMenu.swift, kinds 24, 25 and 27): the streamed app's menu bar, read on
 * the Mac over Accessibility one menu at a time when the device opens it, and an item chosen here
 * pressed there. Ids are good only within one `version`; a fetch and a press carry the title shown,
 * and the Mac acts only while the item at the id still has it.
 */
data class MenuItem(
    val id: String?,
    val title: String,
    val separator: Boolean,
    val enabled: Boolean,
    /** "✓" on, "-" mixed, another single character an app set; null unmarked. */
    val mark: String?,
    /** The shortcut as the Mac draws it ("⇧⌘S"): display only. */
    val key: String?,
    val submenu: Boolean,
) {
    val mixed get() = mark == "-" || mark == "–"

    companion object {
        fun parse(o: JSONObject) = MenuItem(
            id = o.optString("id").takeIf { o.has("id") && it.isNotEmpty() },
            title = clean(o.optString("title"), 100),
            separator = o.optBoolean("separator", false),
            enabled = o.optBoolean("enabled", true),
            mark = o.optString("mark").takeIf { o.has("mark") && it.isNotEmpty() }?.take(1),
            key = o.optString("key").takeIf { o.has("key") && it.isNotEmpty() }?.let { clean(it, 16) },
            submenu = o.optBoolean("submenu", false),
        )
    }
}

/** Kind 24, any of its three shapes; which fields are set says which. */
data class MacMenu(
    val version: Int?,
    val app: String?,
    val menus: List<MenuItem>?,
    val answering: Int?,
    val menu: String?,
    val items: List<MenuItem>?,
    val more: Int,
    val pressed: Boolean?,
    val stale: Boolean,
    val note: String?,
) {
    /** A top level: the menu bar (it may answer the kind 27 that asked for it, too). */
    val isTopLevel get() = menus != null

    companion object {
        fun parse(bytes: ByteArray): MacMenu? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun list(name: String): List<MenuItem>? = o.optJSONArray(name)?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MenuItem::parse) }
            }
            // At most 32 top-level menus, each with a one-part id (1–4 digits, no leading zero)
            // that no earlier one has, as Sill's MacMenuState keeps them.
            val seen = HashSet<String>()
            val menus = list("menus")?.filter { m ->
                val id = m.id ?: return@filter false
                Regex("[1-9][0-9]{0,3}").matches(id) && m.title.isNotEmpty() && seen.add(id)
            }?.take(32)
            MacMenu(
                version = if (o.has("version")) o.optInt("version") else null,
                app = o.optString("app").takeIf { o.has("app") }?.let { clean(it, 100) },
                menus = menus,
                answering = if (o.has("answering")) o.optInt("answering") else null,
                menu = o.optString("menu").takeIf { o.has("menu") },
                items = list("items")?.take(500),
                more = o.optInt("more", 0).coerceAtLeast(0),
                pressed = if (o.has("pressed")) o.optBoolean("pressed") else null,
                stale = o.optBoolean("stale", false),
                note = o.optString("note").takeIf { o.has("note") && it.isNotEmpty() }?.let { clean(it, 300) },
            )
        }.getOrNull()
    }
}

/** Kind 27: no id asks for the top level and subscribes; with an id, that menu's items. */
fun fetchMenuJson(token: Int, version: Int? = null, id: String? = null, title: String? = null): JSONObject =
    JSONObject().put("token", token).apply {
        if (id != null) { put("id", id); put("title", title); if (version != null) put("version", version) }
    }

/** Kind 25: choose one item, by the id and title shown in `version`. */
fun pressMenuItemJson(token: Int, version: Int?, id: String, title: String): JSONObject =
    JSONObject().put("token", token).put("id", id).put("title", title).apply { if (version != null) put("version", version) }

/**
 * A menu's items as rows: separators split them into sections (leading, trailing and doubled ones
 * dropped), an item without a title is dropped (Sill's MacMenuState rule 10).
 */
fun menuRows(items: List<MenuItem>): List<MenuItem> {
    val out = ArrayList<MenuItem>()
    for (it in items) {
        if (it.separator) {
            if (out.isNotEmpty() && !out.last().separator) out += it
        } else if (it.title.isNotEmpty()) out += it
    }
    while (out.lastOrNull()?.separator == true) out.removeAt(out.size - 1)
    return out
}

/** Text from the Mac, shown as it is: control characters out, cut to `max` characters. */
private fun clean(s: String, max: Int): String =
    s.filter { !it.isISOControl() || it == ' ' }.trim().take(max)
