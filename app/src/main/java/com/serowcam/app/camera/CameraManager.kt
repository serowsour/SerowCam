package com.serowcam.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CameraManager {
    private const val TAG = "CameraManager"
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera(context, lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
        }
    }

    fun takePhoto(context: Context) {
        // Implementazione foto (placeholder avanzabile)
        Log.d(TAG, "Take photo")
    }

    fun startRecording(context: Context, background: Boolean) {
        val videoCapture = videoCapture ?: return
        val outputOptions = FileOutputOptions.Builder(
            context.getExternalFilesDir(null)?.resolve("serowcam_${System.currentTimeMillis()}.mp4")!!
        ).build()

        recording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> Log.d(TAG, "Recording started")
                    is VideoRecordEvent.Finalize -> Log.d(TAG, "Recording finalized")
                    else -> {}
                }
            }

        if (background) {
            // Avvia Foreground Service (da implementare completamente)
            Log.d(TAG, "Background recording requested")
        }
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }
}
