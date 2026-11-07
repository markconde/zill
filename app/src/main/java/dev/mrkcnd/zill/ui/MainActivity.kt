package dev.mrkcnd.zill.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.mrkcnd.zill.net.ClockSync.load(this)
        dev.mrkcnd.zill.net.ClipboardShare.load(this)
        enableEdgeToEdge()
        // The volume keys set the Mac sound's volume here, not the ringer's.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(model)
            }
        }
    }

    override fun onStart() { super.onStart(); model.startDiscovery() }
    override fun onStop() { super.onStop(); model.stopDiscovery() }
}

@Composable
private fun App(model: AppModel) {
    val session by model.session.collectAsState()
    val pairing by model.pairing.collectAsState()
    val s = session
    if (s != null) {
        BackHandler { model.disconnect() }
        StreamScreen(s, model.sessionTitle.collectAsState().value, onClose = model::disconnect)
    } else {
        ConnectScreen(model)
    }
    PairingDialog(pairing, model)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConnectScreen(model: AppModel) {
    val rows by model.rows.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Zill")
                        Text(
                            "Use your Mac from your phone, through Sill",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { IconButton(onClick = model::refresh) { Icon(Icons.Default.Refresh, "Look again") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add a Mac") })
        },
    ) { pad ->
        if (rows.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(24.dp))
                Text("Looking for Macs running Sill on this network…", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open Sill on your Mac. Away from home, use Add a Mac with its Remote Access address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(rows, key = { it.key }) { row ->
                    var menu by remember { mutableStateOf(false) }
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .combinedClickable(onClick = { model.connect(row) }, onLongClick = { if (row.saved != null) menu = true }),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, null, Modifier.size(36.dp))
                            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Forget This Mac") }, onClick = { menu = false; row.saved?.let(model::forget) })
                        }
                    }
                }
            }
        }
    }
    if (adding) AddMacDialog(onDismiss = { adding = false }, onAdd = { adding = false; model.addByAddress(it) })
}

@Composable
private fun AddMacDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Mac") },
        text = {
            Column {
                Text(
                    "On your Mac, choose Pair iPhone or iPad… in Sill's menu. Type the Mac's address (its Remote Access " +
                        "address, e.g. over Tailscale), or paste the pairing link.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, label = { Text("Address or sill://pair link") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairingDialog(p: PairingUi, model: AppModel) {
    when (p) {
        PairingUi.None -> Unit
        is PairingUi.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(p.text, Modifier.padding(start = 16.dp))
                }
            },
        )
        is PairingUi.Failed -> AlertDialog(
            onDismissRequest = model::dismissPairing,
            title = { Text("Couldn't connect") },
            text = { Text(p.text) },
            confirmButton = { TextButton(onClick = model::dismissPairing) { Text("OK") } },
        )
        is PairingUi.EnterCode -> {
            var code by remember(p) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = model::cancelPairing,
                title = { Text("Pair with ${p.macName}") },
                text = {
                    Column {
                        Text(
                            if (p.fromAsk) "Your Mac shows a pairing code now. Type its 12 digits, or paste its pairing link."
                            else "Type the 12-digit code your Mac shows, or paste its pairing link.",
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            code, { code = it },
                            label = { Text("Code") },
                            singleLine = true,
                            isError = p.error != null,
                            supportingText = p.error?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { model.submitCode(code) }, enabled = code.isNotBlank()) { Text("Pair") } },
                dismissButton = { TextButton(onClick = model::cancelPairing) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun KeepScreenOn() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center) { content() }
