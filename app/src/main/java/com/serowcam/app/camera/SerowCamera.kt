package com.serowcam.app.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.util.concurrent.TimeUnit

data class CameraPrefs(
    val facingFront: Boolean = false,
    val frame: String = "full",
    val quality: String = "fhd",
    val whiteBalance: String = "auto",
    val mirror: Boolean = true,
    val recordAudio: Boolean = true,
)

@OptIn(ExperimentalCamera2Interop::class)
class SerowCamera(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onRecording: (Boolean) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var activityOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var prefs = CameraPrefs()
    private var boundCamera: androidx.camera.core.Camera? = null
    private var session: SessionOwner? = null
    private var useSession = false

    var zoomMin = 1f
        private set
    var zoomMax = 1f
        private set
    var evMin = 0
        private set
    var evMax = 0
        private set

    fun start(owner: LifecycleOwner, view: PreviewView, initial: CameraPrefs) {
        activityOwner = owner
        previewView = view
        prefs = initial
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            bind()
        }, main)
    }

    fun update(next: CameraPrefs) {
        val rebind = next.facingFront != prefs.facingFront ||
            next.frame != prefs.frame ||
            next.quality != prefs.quality ||
            next.whiteBalance != prefs.whiteBalance
        prefs = next
        previewView?.scaleX = if (next.facingFront && next.mirror) -1f else 1f
        if (rebind && provider != null) bind()
    }

    fun setZoom(ratio: Float) {
        val camera = boundCamera ?: return
        val clamped = ratio.coerceIn(zoomMin, zoomMax)
        camera.cameraControl.setZoomRatio(clamped)
    }

    fun setExposure(index: Int) {
        val camera = boundCamera ?: return
        val clamped = index.coerceIn(evMin, evMax)
        camera.cameraControl.setExposureCompensationIndex(clamped)
    }

    fun setTorch(enabled: Boolean) {
        boundCamera?.cameraControl?.enableTorch(enabled)
    }

    fun focusAt(x: Float, y: Float) {
        val camera = boundCamera ?: return
        val factory = previewView?.meteringPointFactory ?: return
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "SerowCam_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SerowCam")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(
            options,
            main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onStatus("Photo saved")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "photo", exception)
                    onStatus("Could not save the photo")
                }
            },
        )
    }

    fun startRecording(background: Boolean) {
        if (recording != null) return
        if (background) {
            useSession = true
            ensureSession().resume()
            bind()
        }
        val capture = videoCapture ?: return
        val file = File(context.cacheDir, "serow_${System.currentTimeMillis()}.mp4")
        val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val canAudio = prefs.recordAudio &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val prepared = if (canAudio) pending.withAudioEnabled() else pending
        recording = prepared.start(main) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onRecording(true)
                is VideoRecordEvent.Finalize -> {
                    onRecording(false)
                    recording = null
                    if (event.hasError()) {
                        Log.e(TAG, "video error ${event.error}")
                        onStatus("Video was interrupted")
                        file.delete()
                    } else {
                        publishVideo(file)
                    }
                    if (useSession) {
                        useSession = false
                        session?.destroy()
                        session = null
                        bind()
                    }
                }
                else -> Unit
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun onHostPause(background: Boolean) {
        if (recording != null && background) {
            preview?.let { provider?.unbind(it) }
        }
    }

    fun onHostResume() {
        if (recording != null && useSession) {
            val view = previewView ?: return
            preview?.setSurfaceProvider(view.surfaceProvider)
            val owner = session ?: return
            val currentPreview = preview ?: return
            runCatching { provider?.bindToLifecycle(owner, selector, currentPreview) }
        }
    }

    fun shutdown() {
        recording?.stop()
        provider?.unbindAll()
        session?.destroy()
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val view = previewView ?: return
        val owner = if (useSession) ensureSession() else activityOwner ?: return
        selector = if (prefs.facingFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        view.scaleX = if (prefs.facingFront && prefs.mirror) -1f else 1f

        val previewBuilder = Preview.Builder()
        applyWhiteBalance(previewBuilder)
        val nextPreview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }

        val imageBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        applyWhiteBalance(imageBuilder)
        val nextImage = imageBuilder.build()

        val quality = when (prefs.quality) {
            "sd" -> Quality.SD
            "hd" -> Quality.HD
            else -> Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val nextVideo = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            val rational = when (prefs.frame) {
                "4:3" -> Rational(4, 3)
                "16:9" -> Rational(16, 9)
                "1:1" -> Rational(1, 1)
                else -> null
            }
            boundCamera = if (rational == null) {
                cameraProvider.bindToLifecycle(owner, selector, nextPreview, nextImage, nextVideo)
            } else {
                val group = UseCaseGroup.Builder()
                    .addUseCase(nextPreview)
                    .addUseCase(nextImage)
                    .addUseCase(nextVideo)
                    .setViewPort(ViewPort.Builder(rational, view.display?.rotation ?: 0).build())
                    .build()
                cameraProvider.bindToLifecycle(owner, selector, group)
            }
            preview = nextPreview
            imageCapture = nextImage
            videoCapture = nextVideo
            val camera = boundCamera ?: return
            zoomMin = camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
            zoomMax = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            val range = camera.cameraInfo.exposureState.exposureCompensationRange
            evMin = range.lower
            evMax = range.upper
            onStatus("Camera ready")
        } catch (error: Exception) {
            Log.e(TAG, "bind", error)
            onStatus("Could not open the camera")
        }
    }

    private fun <T> applyWhiteBalance(builder: androidx.camera.core.ExtendableBuilder<T>) {
        val mode = when (prefs.whiteBalance) {
            "daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "cloudy" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "tungsten" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
    }

    private fun publishVideo(file: File) {
        val name = "SerowCam_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SerowCam")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            onStatus("Could not save the video")
            return
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        file.delete()
        onStatus("Video saved")
    }

    private fun ensureSession(): SessionOwner {
        val current = session
        if (current != null && current.lifecycle.currentState != Lifecycle.State.DESTROYED) return current
        return SessionOwner().also { session = it }
    }

    private class SessionOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    companion object {
        private const val TAG = "SerowCamera"
    }
}
