package com.serowcam.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serowcam.app.camera.CameraPrefs
import com.serowcam.app.camera.SerowCamera
import com.serowcam.app.service.BackgroundVideoService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.getSharedPreferences("serowcam", Application.MODE_PRIVATE)
    var status by mutableStateOf("Allow the camera to begin")
    var recording by mutableStateOf(false)
    var facingFront by mutableStateOf(store.getBoolean("front", false))
    var frame by mutableStateOf(store.getString("frame", "full") ?: "full")
    var quality by mutableStateOf(store.getString("quality", "fhd") ?: "fhd")
    var whiteBalance by mutableStateOf(store.getString("wb", "auto") ?: "auto")
    var mirror by mutableStateOf(store.getBoolean("mirror", true))
    var mic by mutableStateOf(store.getBoolean("mic", true))
    var grid by mutableStateOf(store.getString("grid", "thirds") ?: "thirds")
    var timer by mutableIntStateOf(store.getInt("timer", 0))
    var background by mutableStateOf(store.getBoolean("background", false))
    var zoom by mutableStateOf(1f)
    var zoomMin by mutableStateOf(1f)
    var zoomMax by mutableStateOf(1f)
    var exposure by mutableIntStateOf(0)
    var evMin by mutableIntStateOf(0)
    var evMax by mutableIntStateOf(0)
    var torch by mutableStateOf(false)
    private var refresh: (() -> Unit)? = null

    val engine = SerowCamera(
        app,
        onStatus = {
            status = it
            if (it == "Camera ready") refresh?.invoke()
        },
        onRecording = { recording = it },
    )

    init {
        refresh = {
            zoomMin = engine.zoomMin
            zoomMax = engine.zoomMax.coerceAtLeast(engine.zoomMin)
            evMin = engine.evMin
            evMax = engine.evMax
            zoom = zoom.coerceIn(zoomMin, zoomMax)
        }
    }

    fun prefs() = CameraPrefs(facingFront, frame, quality, whiteBalance, mirror, mic)

    fun persist() {
        store.edit()
            .putBoolean("front", facingFront)
            .putString("frame", frame)
            .putString("quality", quality)
            .putString("wb", whiteBalance)
            .putBoolean("mirror", mirror)
            .putBoolean("mic", mic)
            .putString("grid", grid)
            .putInt("timer", timer)
            .putBoolean("background", background)
            .apply()
    }

    override fun onCleared() {
        engine.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf("photo") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result[Manifest.permission.CAMERA] == true }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.engine.onHostPause(vm.background)
            if (event == Lifecycle.Event.ON_RESUME) vm.engine.onHostResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun afterTimer(block: () -> Unit) {
        if (vm.timer == 0 || vm.recording) {
            block()
            return
        }
        scope.launch {
            for (n in vm.timer downTo 1) {
                countdown = n
                delay(1000)
            }
            countdown = null
            block()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090A0B))) {
        if (!granted) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("SerowCam", color = Color(0xFFD7A15A), fontSize = 32.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Photos and video stay on this phone.",
                    color = Color(0xFFF3EFE6),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(neededPermissions()) }) {
                    Text("Allow camera")
                }
            }
        } else {
            val frameMod = when (vm.frame) {
                "4:3" -> Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                "16:9" -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                "1:1" -> Modifier.fillMaxWidth().aspectRatio(1f)
                else -> Modifier.fillMaxSize()
            }
            Box(Modifier.align(Alignment.Center).then(frameMod)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            vm.engine.start(lifecycleOwner, this, vm.prefs())
                        }
                    },
                )
                if (vm.grid != "off") {
                    Canvas(Modifier.fillMaxSize()) {
                        val ink = Color(0x59F3EFE6)
                        if (vm.grid == "thirds") {
                            drawLine(ink, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 2f)
                            drawLine(ink, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 2f)
                            drawLine(ink, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 2f)
                            drawLine(ink, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 2f)
                        } else {
                            drawLine(ink, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 2f)
                            drawLine(ink, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> vm.engine.focusAt(offset.x, offset.y) }
                        },
                )
            }

            countdown?.let {
                Text("$it", modifier = Modifier.align(Alignment.Center), color = Color(0xFFF3EFE6), fontSize = 72.sp)
            }

            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SEROW", color = Color(0xFFF3EFE6), fontSize = 18.sp)
                    Text("  CAM", color = Color(0xFFD7A15A), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFF3EFE6))
                    }
                }
                Text(vm.status, color = Color(0xFFA39E93), fontSize = 12.sp)
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xE6161513))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (vm.zoomMax > vm.zoomMin) {
                    Text("Zoom ${"%.1f".format(vm.zoom)}×", color = Color(0xFFA39E93), fontSize = 12.sp)
                    Slider(
                        value = vm.zoom.coerceIn(vm.zoomMin, vm.zoomMax),
                        onValueChange = {
                            vm.zoom = it
                            vm.engine.setZoom(it)
                        },
                        valueRange = vm.zoomMin..vm.zoomMax,
                    )
                }
                if (vm.evMax > vm.evMin) {
                    Text("Exposure ${vm.exposure}", color = Color(0xFFA39E93), fontSize = 12.sp)
                    Slider(
                        value = vm.exposure.toFloat().coerceIn(vm.evMin.toFloat(), vm.evMax.toFloat()),
                        onValueChange = {
                            vm.exposure = it.toInt()
                            vm.engine.setExposure(vm.exposure)
                        },
                        valueRange = vm.evMin.toFloat()..vm.evMax.toFloat(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "photo", onClick = { if (!vm.recording) mode = "photo" }, label = { Text("Photo") })
                    FilterChip(selected = mode == "video", onClick = { if (!vm.recording) mode = "video" }, label = { Text("Video") })
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        vm.torch = !vm.torch
                        vm.engine.setTorch(vm.torch)
                    }) {
                        Icon(
                            if (vm.torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = Color(0xFFF3EFE6),
                        )
                    }
                    IconButton(onClick = {
                        if (!vm.recording) {
                            vm.facingFront = !vm.facingFront
                            vm.persist()
                            vm.engine.update(vm.prefs())
                        }
                    }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color(0xFFF3EFE6))
                    }
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(if (vm.recording) Color(0xFFFF3B30) else Color(0xFFD7A15A))
                            .clickable {
                                if (countdown != null) return@clickable
                                if (mode == "photo") {
                                    afterTimer { vm.engine.takePhoto() }
                                } else if (vm.recording) {
                                    vm.engine.stopRecording()
                                    context.startService(
                                        Intent(context, BackgroundVideoService::class.java)
                                            .setAction(BackgroundVideoService.ACTION_STOP),
                                    )
                                } else {
                                    afterTimer {
                                        if (vm.background) {
                                            ContextCompat.startForegroundService(
                                                context,
                                                Intent(context, BackgroundVideoService::class.java),
                                            )
                                        }
                                        vm.engine.startRecording(vm.background)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (vm.recording) "Stop" else if (mode == "photo") "Shot" else "Rec",
                            color = Color(0xFF1C1408),
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                settingsOpen = false
                vm.persist()
                vm.engine.update(vm.prefs())
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsBody(vm)
        }
    }
}

@Composable
private fun SettingsBody(vm: CameraViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", color = Color(0xFFF3EFE6), fontSize = 22.sp)
        Text("Grid", color = Color(0xFFA39E93))
        ChipRow(vm.grid, listOf("off" to "Off", "thirds" to "Thirds", "cross" to "Cross")) { vm.grid = it }
        Text("Self-timer", color = Color(0xFFA39E93))
        ChipRow(vm.timer.toString(), listOf("0" to "Off", "3" to "3s", "10" to "10s")) { vm.timer = it.toInt() }
        Text("Frame", color = Color(0xFFA39E93))
        ChipRow(vm.frame, listOf("full" to "Full", "4:3" to "4:3", "16:9" to "16:9", "1:1" to "1:1")) { vm.frame = it }
        Text("White balance", color = Color(0xFFA39E93))
        ChipRow(
            vm.whiteBalance,
            listOf(
                "auto" to "Auto",
                "daylight" to "Day",
                "cloudy" to "Cloud",
                "tungsten" to "Tungsten",
                "fluorescent" to "Fluor",
            ),
        ) { vm.whiteBalance = it }
        Text("Video quality", color = Color(0xFFA39E93))
        ChipRow(vm.quality, listOf("sd" to "SD", "hd" to "HD", "fhd" to "Full HD")) { vm.quality = it }
        ToggleRow("Mirror front camera", vm.mirror) { vm.mirror = it }
        ToggleRow("Microphone", vm.mic) { vm.mic = it }
        ToggleRow("Keep recording in background", vm.background) { vm.background = it }
        Text(
            "Background recording keeps a notification up so video can continue after you leave the app. Files are saved in Pictures and Movies.",
            color = Color(0xFFA39E93),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChipRow(selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFF3EFE6), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

private fun hasPermissions(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
