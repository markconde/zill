package dev.mrkcnd.sillclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mrkcnd.sillclient.net.StreamSession
import dev.mrkcnd.sillclient.protocol.SettingsChange
import dev.mrkcnd.sillclient.protocol.SettingsChoices

/**
 * The Mac's settings (kinds 16 and 17), as its menu and Settings show them, with its own labels.
 * A change shows at once and is confirmed by the Mac's answer; one it doesn't take snaps back with a
 * notice. Settings apply to every device; muting is this device's own (the bar's speaker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(session: StreamSession) {
    val state by session.hostSettings.collectAsState()
    val pending by session.pendingSettings.collectAsState()
    val list by session.windowList.collectAsState()
    val mac = list?.macName?.ifBlank { null } ?: "the Mac"
    val h = state
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Which version runs here, and where this screen says its camera is: for checking a fix on a device.
    val about = "Zill for Android " + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?") +
        " · camera cutout: " + cutoutReport()
    if (h == null) {
        Footnote("This Mac doesn’t share its settings with devices. Update Sill on the Mac to change them from here.")
        Footnote(about)
        return
    }
    // What the Mac reported, with this device's changes still on their way laid over it.
    val s = pending.toSortedMap().values.fold(h.settings) { acc, c -> c.appliedTo(acc) }
    val set: (SettingsChange) -> Unit = { session.changeSettings(it) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        h.link?.takeIf { it.isBehind }?.let { l ->
            val fit = l.suggestedBitrate?.let { b ->
                " ${SettingsChoices.qualityName(b)}" + (l.suggestedCaptureScale?.let { " · ${SettingsChoices.resolutionName(it)}" } ?: "") + " would fit."
            } ?: ""
            Warning("This connection can’t keep up with ${SettingsChoices.qualityName(l.bitrate)}" +
                (l.carriedKbps?.let { ": it carried about ${it / 1000} Mbps." } ?: ".") + fit)
        }
        if (h.softwareEncoder) Warning("The hardware encoder is busy or not answering, so streams use the software encoder: up to 60 fps at Standard resolution.")

        Header("Streaming")
        if (h.away?.thisConnectionAway == true) Footnote("This device is away from home: Quality and Resolution set the Mac’s away quality.")
        Picker("Quality", SettingsChoices.qualityTitle(s.bitrate), SettingsChoices.quality.map { (b, _) -> SettingsChoices.qualityTitle(b) to b }) {
            set(SettingsChange(bitrate = it))
        }
        Segmented("Resolution", SettingsChoices.captureScale.map { SettingsChoices.resolutionName(it) to it }, s.captureScale) {
            set(SettingsChange(captureScale = it))
        }
        Segmented("Frame rate limit", SettingsChoices.maxFPS.map { "$it fps" to it }, s.maxFPS) { set(SettingsChange(maxFPS = it)) }
        h.stream?.let { r ->
            Footnote("Running now: ${r.width}×${r.height} at ${r.fps} fps, ${r.mbps} Mbps" + if (r.onVirtualDisplay) ", on the virtual display." else ".")
        }
        Footnote("Each device asks for its own screen’s rate, up to this limit. Quality is per 60 fps; a 120 fps stream gets twice as much. " +
            "${SettingsChoices.FAST_LINK_NOTE} Changes apply at once: the stream restarts for a moment.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Toggle("Prioritize encoding speed", s.prioritizeSpeed) { set(SettingsChange(prioritizeSpeed = it)) }
        Footnote("Lower latency when the Mac is busy, at a softer picture.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Toggle("Stream from a virtual display", s.virtualDisplay, enabled = h.virtualDisplayAvailable) {
            set(SettingsChange(virtualDisplay = it))
        }
        h.virtualDisplayNote?.let { Warning(it) }
        Footnote("Sill moves the window you pick onto an invisible display made for your device, so it keeps updating when other windows cover it and text is drawn at your device’s scale.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Toggle("Send audio (beta)", s.sendAudio == true, enabled = s.sendAudio != null) { set(SettingsChange(sendAudio = it)) }
        if (s.sendAudio == null) Footnote("Update Sill on $mac to hear its sound here.")
        else {
            if (s.sendAudio == true) h.audioNote?.let { Warning(it) }
            Footnote("Devices play the sound of what you stream: all of the streamed window’s app, or every app for the Desktop. " +
                "$mac keeps playing it too. This turns it on or off for every device; the speaker in the bar mutes it here alone.")
        }

        s.directWireless?.let { dw ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Only a paired session may change it (a Mac before 2026-10-29 says nothing and took it from any).
            Toggle("Direct wireless connection", dw, enabled = h.sessionPaired != false) { set(SettingsChange(directWireless = it)) }
            Footnote("For iPhones and iPads near $mac without a shared Wi‑Fi network, the way AirDrop does; this Android device can’t use it. " +
                "While it’s on, the Mac’s Wi‑Fi keeps stepping away from your network, so streaming over Wi‑Fi can stutter." +
                if (h.sessionPaired == false) " Only a paired device can change it." else "")
        }
        Footnote(if (h.persistent) "Changes are saved on $mac and apply to every device." else "Changes last until Sill on $mac quits, and apply to every device.")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (CameraGap.hasPreset) {
            CameraGap.load(ctx)
            val gapOn by CameraGap.on.collectAsState()
            Toggle("Leave room for the inner camera", gapOn) { CameraGap.set(ctx, it) }
            Footnote("This phone’s inner screen doesn’t tell apps where its camera is, so Zill leaves a gap in the top bar where it sits on a Galaxy Z Fold8. This device only.")
        }
        Footnote(about)
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Footnote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warning(text: String) {
    Text("⚠ $text", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFE8A33D))
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(label: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (name, value) ->
                SegmentedButton(selected = value == selected, onClick = { if (value != selected) onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name) }
            }
        }
    }
}

@Composable
private fun <T> Picker(label: String, shown: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$shown ▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(text = { Text((if (name == shown) "✓ " else "    ") + name) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}
