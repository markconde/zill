package dev.mrkcnd.zill.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mrkcnd.zill.net.StreamSession
import dev.mrkcnd.zill.protocol.MenuItem

/**
 * The streamed app's menu bar (kinds 24, 25, 27), as a sheet: the top-level menus, then each menu's
 * items fetched from the Mac when it opens, submenus one level deeper with a back arrow. Choosing
 * an item presses it on the Mac and closes the sheet; the Mac shows the result.
 */
@Composable
fun MenuSheet(session: StreamSession, onDone: () -> Unit) {
    val top by session.macMenu.collectAsState()
    val main = remember { Handler(Looper.getMainLooper()) }

    /** One open level: the menu it is, the version its id belongs to, and what the Mac answered. */
    class Level(val menu: MenuItem, val version: Int?) {
        var contents by mutableStateOf<StreamSession.MenuContents?>(null)
    }
    val stack = remember { mutableStateListOf<Level>() }

    fun open(item: MenuItem, version: Int?) {
        val level = Level(item, version)
        stack += level
        session.fetchMenu(item, version) { c -> main.post { level.contents = c } }
    }

    // Another app came to the front (a new version): the open levels belong to the old menus.
    val version = top?.version
    if (stack.isNotEmpty() && stack.first().version != version) stack.clear()

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.isNotEmpty()) {
                IconButton(onClick = { stack.removeAt(stack.size - 1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            } else Spacer(Modifier.width(16.dp))
            Text(
                stack.lastOrNull()?.menu?.title ?: (top?.app ?: "Menus"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        val level = stack.lastOrNull()
        val t = top
        when {
            t == null -> Note("Not connected.")
            level == null -> {
                // The top level: each a menu of its own.
                if (t.stale || (t.menus.isNullOrEmpty() && t.note != null)) Note(t.note ?: "The app isn't answering.")
                if (t.menus.isNullOrEmpty() && t.note == null) Note("No menus.")
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(t.menus.orEmpty()) { m -> Row(m, enabled = m.enabled) { open(m, t.version) } }
                }
            }
            else -> {
                val c = level.contents
                if (c == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    c.note?.let { Note(it) }
                    if (c.items.isEmpty() && c.note == null && c.more == 0) Note("No items")
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(c.items) { item ->
                            if (item.separator) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            else Row(item, enabled = item.enabled && !c.stale) {
                                if (item.submenu) open(item, level.version)
                                else { session.pressMenuItem(item, level.version); onDone() }
                            }
                        }
                        if (c.more > 0) item { Note("${c.more} more on the Mac") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Row(item: MenuItem, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The Mac's mark: ✓ on, – mixed, or the app's own character.
        Text(
            when { item.mark == null -> ""; item.mixed -> "–"; else -> item.mark },
            Modifier.width(18.dp), style = MaterialTheme.typography.bodyLarge,
        )
        Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.key?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (item.submenu) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
