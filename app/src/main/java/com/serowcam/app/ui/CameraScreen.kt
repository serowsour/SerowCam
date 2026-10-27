package com.serowcam.app.ui

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.serowcam.app.camera.CameraManager

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    var isRecording by remember { mutableStateOf(false) }
    var isBackground by remember { mutableStateOf(false) }

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (!permissions.allPermissionsGranted) {
            permissions.launchMultiplePermissionRequest()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissions.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        CameraManager.startCamera(
                            context = ctx,
                            lifecycleOwner = lifecycleOwner,
                            previewView = previewView
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Controlli inferiori
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = { /* Switch camera */ }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch", tint = Color.White)
                    }
                    FloatingActionButton(
                        onClick = {
                            if (isRecording) {
                                CameraManager.stopRecording()
                                isRecording = false
                            } else {
                                CameraManager.startRecording(context, isBackground)
                                isRecording = true
                            }
                        },
                        containerColor = if (isRecording) Color.Red else Color.White
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                            contentDescription = "Record",
                            tint = if (isRecording) Color.White else Color.Black
                        )
                    }
                    IconButton(onClick = { CameraManager.takePhoto(context) }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Photo", tint = Color.White)
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Background", color = Color.White)
                    Switch(
                        checked = isBackground,
                        onCheckedChange = { isBackground = it }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Permessi necessari per la fotocamera e il microfono")
                Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
                    Text("Concedi permessi")
                }
            }
        }
    }
}
