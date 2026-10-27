package com.serowcam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.serowcam.app.ui.CameraScreen
import com.serowcam.app.ui.theme.SerowCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SerowCamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0B)) {
                    CameraScreen()
                }
            }
        }
    }
}
