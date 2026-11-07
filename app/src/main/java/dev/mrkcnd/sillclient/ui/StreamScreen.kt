package dev.mrkcnd.sillclient.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.mrkcnd.sillclient.input.Hid
import dev.mrkcnd.sillclient.input.Mods
import dev.mrkcnd.sillclient.net.StreamSession
import dev.mrkcnd.sillclient.protocol.Gestures
import dev.mrkcnd.sillclient.protocol.StreamSource
import dev.mrkcnd.sillclient.protocol.WindowInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(session: StreamSession, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val list by session.windowList.collectAsState()
    val fps by session.fps.collectAsState()
    val rtt by session.rttMs.collectAsState()
    val frameAge by session.frameAgeMs.collectAsState()
    val macPointer by session.macPointer.collectAsState()
    val cursorShape by session.cursorShape.collectAsState()
    var muted by remember { mutableStateOf(session.audio.muted) }
    val macMenu by session.macMenu.collectAsState()
    val menuNotice by session.menuNotice.collectAsState()
    var showMenus by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<StreamView?>(null) }
    var showBar by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    var latched by remember { mutableLongStateOf(0L) }
    // Aa, as in Sill's own app: Off (null) leaves the Mac window alone; a scale fits it to this panel.
    var textScale by remember { mutableStateOf<Double?>(null) }
    val windowStreams = list?.active is StreamSource.Window

    KeepScreenOn()
    Immersive()

    // Text copied on the Mac becomes this device's clipboard while the session lasts.
    LaunchedEffect(session) {
        session.clipboardFromMac.collect { text ->
            if (text != null) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("From the Mac", text))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        if (showBar) {
            TopBar(
                title = list?.macName?.ifBlank { null } ?: title,
                // The sound's state rides along: the line recomposes every second with the fps.
                status = if (state is StreamSession.State.Live) listOfNotNull("$fps fps · " + (frameAge?.let { "$it ms delay" } ?: "$rtt ms"), session.audio.statusText()).joinToString(" · ") else "",
                onClose = onClose,
                onWindows = { showPicker = true },
                onKeyboard = { view?.showKeyboard() },
                muted = muted,
                onMute = { muted = !muted; session.audio.muted = muted },
                textScale = textScale,
                textScaleEnabled = windowStreams,
                onTextScale = { textScale = it },
                onPaste = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrEmpty()) {
                        Toast.makeText(context, "Nothing to paste", Toast.LENGTH_SHORT).show()
                    } else {
                        session.sendClipboard(text)
                        session.sendInputs(Hid.press(Hid.V, Mods.COMMAND))
                    }
                },
                onHide = { showBar = false },
            )
            KeyRow(
                // The Mac's menus lead the row, as its menu bar does; only once the Mac sent a top level.
                menuApp = macMenu?.takeIf { !it.menus.isNullOrEmpty() || it.note != null }?.let { it.app ?: "Menus" },
                onMenus = { showMenus = true },
                latched = latched,
                gestures = (list?.gestures ?: 0) >= 1,
                onLatch = { flag -> latched = latched xor flag; view?.latched = latched },
                onKey = { usage -> view?.typeKey(usage) },
                onGesture = { name -> session.gesture(name, 3) },
            )
        }
        // Clipped: a zoomed picture is larger than its box and would otherwise cover the bars.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    StreamView(ctx).also { v ->
                        v.session = session
                        v.onLatchSpent = { latched = 0 }
                        view = v
                    }
                },
                update = { v ->
                    v.session = session
                    v.gesturesAllowed = (list?.gestures ?: 0) >= 1
                    v.latched = latched
                    v.textScale = textScale
                    v.cursorShape = cursorShape
                    v.macPointer = macPointer
                },
            )
            if (!showBar) {
                IconButton(
                    onClick = { showBar = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color(0x66000000), RoundedCornerShape(50)),
                ) { Icon(Icons.Default.Visibility, "Show controls", tint = Color.White) }
            }
            if (state is StreamSession.State.Connecting) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("Connecting to $title…", color = Color.White)
                }
            }
            val l = list
            if (l != null && l.locked) {
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val name = l.macName.ifBlank { "The Mac" }
                        Text(
                            if (l.offConsole) "Another user started controlling this Mac." else "$name is locked",
                            color = Color.White, style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (l.offConsole) "Log in to your account to reactivate screen sharing with Sill."
                            else "To unlock it from here, show the keyboard, type its password and press Return.",
                            color = Color.White,
                        )
                        if (!l.offConsole) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { view?.showKeyboard() }) { Text("Show Keyboard", color = Color.White) }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(view, list?.active) {
        view?.resendViewport()
        onDispose {}
    }

    (state as? StreamSession.State.Ended)?.let { ended ->
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(if (ended.error) "Disconnected" else "Session ended") },
            text = { Text(ended.message) },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
    }

    val settingsNotice by session.settingsNotice.collectAsState()
    LaunchedEffect(settingsNotice) {
        settingsNotice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); session.settingsNotice.value = null }
    }

    // A choice the Mac refused (or the app not answering): one line, then gone.
    LaunchedEffect(menuNotice) {
        menuNotice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); session.menuNotice.value = null }
    }

    if (showMenus) {
        ModalBottomSheet(onDismissRequest = { showMenus = false }) {
            MenuSheet(session, onDone = { showMenus = false })
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }) {
            Picker(session, onPicked = { showPicker = false })
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    status: String,
    onClose: () -> Unit,
    onWindows: () -> Unit,
    onKeyboard: () -> Unit,
    muted: Boolean,
    onMute: () -> Unit,
    textScale: Double?,
    textScaleEnabled: Boolean,
    onTextScale: (Double?) -> Unit,
    onPaste: () -> Unit,
    onHide: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1C1C1E)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Disconnect", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (status.isNotEmpty()) Text(status, color = Color(0xFF9A9AA0), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = MaterialTheme.typography.labelSmall)
        }
        TextScaleButton(textScale, textScaleEnabled, onTextScale)
        IconButton(onClick = onMute, modifier = Modifier.size(40.dp)) {
            Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                if (muted) "Unmute the Mac's sound" else "Mute the Mac's sound", tint = Color.White)
        }
        IconButton(onClick = onWindows, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ViewQuilt, "Windows and apps", tint = Color.White) }
        IconButton(onClick = onPaste, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ContentPaste, "Paste on the Mac", tint = Color.White) }
        IconButton(onClick = onKeyboard, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White) }
        IconButton(onClick = onHide, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.VisibilityOff, "Hide controls", tint = Color.White) }
    }
}

/** Sill's Aa detents: Off, then 0.5× to 1.5× in 0.25 steps. */
private val TEXT_SCALES: List<Double?> = listOf(null, 0.5, 0.75, 1.0, 1.25, 1.5)

private fun scaleTitle(s: Double?): String =
    if (s == null) "Off" else (if (s == s.toLong().toDouble()) s.toLong().toString() else s.toString()) + "×"

/**
 * Aa: how the Mac sizes the window it streams. Off leaves it alone; a scale resizes it to this
 * panel divided by the scale, so a larger one shows its text larger here. Sill sizes windows only,
 * never the Desktop, so it is dimmed unless a window streams.
 */
@Composable
private fun TextScaleButton(scale: Double?, enabled: Boolean, onPick: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(
                if (scale == null) "Aa" else "Aa ${scaleTitle(scale)}",
                color = if (enabled) Color.White else Color(0xFF5A5A60),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Fit the window to this screen",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            TEXT_SCALES.forEach { s ->
                DropdownMenuItem(
                    text = { Text((if (s == scale) "✓ " else "    ") + scaleTitle(s)) },
                    onClick = { onPick(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun KeyRow(menuApp: String?, onMenus: () -> Unit, latched: Long, gestures: Boolean, onLatch: (Long) -> Unit, onKey: (Int) -> Unit, onGesture: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2C2C2E)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (menuApp != null) Cap("≡ $menuApp") { onMenus() }
        Cap("esc") { onKey(Hid.ESCAPE) }
        Cap("tab") { onKey(Hid.TAB) }
        Latch("⌘", latched and Mods.COMMAND != 0L) { onLatch(Mods.COMMAND) }
        Latch("⌥", latched and Mods.OPTION != 0L) { onLatch(Mods.OPTION) }
        Latch("⌃", latched and Mods.CONTROL != 0L) { onLatch(Mods.CONTROL) }
        Latch("⇧", latched and Mods.SHIFT != 0L) { onLatch(Mods.SHIFT) }
        Cap("←") { onKey(Hid.LEFT) }
        Cap("↑") { onKey(Hid.UP) }
        Cap("↓") { onKey(Hid.DOWN) }
        Cap("→") { onKey(Hid.RIGHT) }
        Cap("⌫") { onKey(Hid.BACKSPACE) }
        Cap("⏎") { onKey(Hid.RETURN) }
        if (gestures) {
            Cap("Mission Control") { onGesture(Gestures.SWIPE_UP) }
            Cap("App Exposé") { onGesture(Gestures.SWIPE_DOWN) }
            Cap("Desktop") { onGesture(Gestures.SPREAD) }
        }
    }
}

@Composable
private fun Cap(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Color.White) }
}

@Composable
private fun Latch(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleMedium) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedContainerColor = Color(0xFF8AB4F8),
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Picker(session: StreamSession, onPicked: () -> Unit) {
    val list by session.windowList.collectAsState()
    val thumbs by session.thumbnails.collectAsState()
    val icons by session.icons.collectAsState()
    val apps by session.apps.collectAsState()
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().height(520.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Windows") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Apps") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Settings") })
        }
        if (tab == 2) { SettingsPanel(session); return@Column }
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PickerRow(
                        title = "Desktop", subtitle = "The Mac's whole screen", selected = list?.active == StreamSource.Desktop,
                        image = null, onClick = { session.select(StreamSource.Desktop); onPicked() },
                    )
                    HorizontalDivider()
                }
                items(list?.windows ?: emptyList(), key = { it.id }) { w ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        PickerRow(
                            title = w.title.ifBlank { w.appName },
                            subtitle = if (w.title.isBlank()) "${w.width}×${w.height}" else w.appName,
                            selected = (list?.active as? StreamSource.Window)?.id == w.id,
                            image = thumbs[w.id] ?: icons[w.bundleID],
                            onClick = { session.select(StreamSource.Window(w.id)); onPicked() },
                            onLongClick = { menu = true },
                        )
                        WindowMenu(w, menu, onDismiss = { menu = false }) { action -> session.windowCommand(w.id, action) }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(apps, key = { it.bundleID }) { a ->
                    PickerRow(a.name, a.bundleID, false, icons[a.bundleID], onClick = { session.launch(a.bundleID); onPicked() })
                }
            }
        }
    }
}

@Composable
private fun WindowMenu(w: WindowInfo, open: Boolean, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Close ${w.appName}'s window") }, onClick = { onDismiss(); onAction("close") })
        DropdownMenuItem(text = { Text("Minimize") }, onClick = { onDismiss(); onAction("minimize") })
        DropdownMenuItem(text = { Text("Full Screen") }, onClick = { onDismiss(); onAction("fullScreen") })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    image: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 10f), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(image.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.DesktopMac, null, Modifier.size(40.dp))
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Immersive() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val window = activity.window
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
