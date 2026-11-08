package dev.mrkcnd.zill.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mrkcnd.zill.protocol.PairLink
import dev.mrkcnd.zill.scan.QrDecode
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Whether this device has a camera to scan with. */
fun hasCamera(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Full screen: the camera, until it sees the Mac's pairing QR code. [onLink] gets the sill://pair
 * link, already checked with [PairLink.parse]. Other QR codes show a hint and scanning goes on.
 */
@Composable
fun QrScanner(onLink: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onCancel)
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; asked = true }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    // Back from Android's settings with the camera allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (granted()) allowed = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            allowed -> CameraScan(onLink, onCancel)
            asked -> NoCamera(onCancel, openSettings = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            })
        }
    }
}

@Composable
private fun CameraScan(onLink: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onLink)
    var hint by remember { mutableStateOf<String?>(null) }
    var hintAt by remember { mutableStateOf(0L) }
    val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(owner) {
        val done = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val shown = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            // One buffer for every frame. It is a whole stride per row, because the plane's last row
            // can stop short of the stride and the decoder indexes full rows.
            var y = ByteArray(0)
            analysis.setAnalyzer(worker) { image ->
                image.use {
                    if (done.get()) return@use
                    val plane = it.planes[0]
                    val buf = plane.buffer
                    val need = plane.rowStride * it.height
                    if (y.size != need) y = ByteArray(need)
                    buf.get(y, 0, minOf(buf.remaining(), need))
                    val text = QrDecode.decode(y, it.width, it.height, plane.rowStride) ?: return@use
                    if (PairLink.parse(text) != null) {
                        if (done.compareAndSet(false, true)) main.execute { deliver(text.trim()) }
                    } else {
                        main.execute { hint = "That isn't a Sill pairing code."; hintAt = System.currentTimeMillis() }
                    }
                }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            }.onFailure {
                runCatching { p.unbindAll(); p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, shown, analysis) }
                    .onFailure { hint = "Couldn't start the camera."; hintAt = Long.MAX_VALUE }
            }
        }, main)
        onDispose {
            done.set(true)
            provider?.unbindAll()
            worker.shutdown()
        }
    }
    LaunchedEffect(hintAt) {
        if (hintAt != Long.MAX_VALUE && hint != null) { delay(2500); hint = null }
    }

    AndroidView({ preview }, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(
            Modifier.align(Alignment.Center).size(260.dp)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.dp)),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                hint ?: "Point the camera at the QR code in Sill's pairing window on your Mac.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCancel) { Text("Type the Code Instead", color = Color.White) }
        }
    }
}

@Composable
private fun NoCamera(onCancel: () -> Unit, openSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Zill needs the camera to scan the code. Allow it in Settings, or type the code instead.",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = openSettings) { Text("Open Settings") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text("Type the Code Instead", color = Color.White) }
    }
}
