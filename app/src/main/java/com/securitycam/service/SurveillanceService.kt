package com.securitycam.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.recognition.FaceMatcher
import com.securitycam.core.recognition.FaceVerdict
import com.securitycam.core.recognition.PresenceGate
import com.securitycam.core.remote.ViewerServer
import com.securitycam.core.surveillance.FrameSampler
import com.securitycam.core.surveillance.LowLightEnhancer
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.MotionResult
import com.securitycam.core.surveillance.NightVisionController
import com.securitycam.core.surveillance.NormalizedRect
import com.securitycam.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live surveillance state published to UI and background observers.
 */
data class SurveillanceLiveState(
    val isRunning: Boolean = false,
    val isArmed: Boolean = true,
    val cameraFacing: String = "BACK",
    val isTorchOn: Boolean = false,
    val motionResult: MotionResult? = null,
    val lastEventTimestamp: Long = 0L,
    val isNightVisionActive: Boolean = false
)

/**
 * 24/7 Foreground Service running under Android 14 camera foregroundServiceType.
 * Manages CameraX Preview & ImageAnalysis use cases, wake lock management, and
 * background motion detection with automated screenshot capture and alert routing.
 */
class SurveillanceService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private val app by lazy { SecurityCamApp.getInstance() }

    @Volatile
    private var lastSnapshotTimestamp: Long = 0L

    private var patrolJob: Job? = null

    @Volatile
    private var currentSettings: SecuritySettings = SecuritySettings()

    // Night vision: the sensor's own exposure for the latest frame, and the mode it drives.
    private val nightVision = NightVisionController()
    @Volatile
    private var lastIso: Int? = null
    @Volatile
    private var lastExposureTimeNs: Long? = null
    private var nightBoostApplied = false

    // Live web viewer
    private var lastStreamFrameAt = 0L

    // Known-people recognition: one capture may be held back while the camera looks for a face.
    private class Capture(
        val bitmap: Bitmap,
        val result: MotionResult,
        val boxes: List<NormalizedRect>,
        val timestamp: Long,
        val isLowLight: Boolean
    )

    private val presenceGate = PresenceGate()
    private val gateMutex = Mutex()
    private var heldCapture: Capture? = null
    private var holdTimer: Job? = null
    @Volatile
    private var isHoldingAlert = false

    companion object {
        const val TAG = "SurveillanceService"
        const val SNAPSHOT_BURST_THROTTLE_MS = 3000L // Maximum 1 saved snapshot per 3000ms during continuous motion

        /** While an alert is held waiting for a face, look more often so the hold is used well. */
        const val SNAPSHOT_HOLD_THROTTLE_MS = 1000L

        /** Frames are captured at this size; motion detection runs on a reduced copy of it. */
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
        const val ANALYSIS_TARGET_WIDTH = 160

        /** At most one frame per this many milliseconds is encoded for the web viewer. */
        const val STREAM_FRAME_INTERVAL_MS = 200L
        const val STREAM_JPEG_QUALITY = 70

        // Actions
        const val ACTION_START = "com.securitycam.service.ACTION_START"
        const val ACTION_STOP = "com.securitycam.service.ACTION_STOP"
        const val ACTION_ARM = "com.securitycam.service.ACTION_ARM"
        const val ACTION_DISARM = "com.securitycam.service.ACTION_DISARM"
        const val ACTION_TOGGLE_ARM = "com.securitycam.service.ACTION_TOGGLE_ARM"
        const val ACTION_SWITCH_CAMERA = "com.securitycam.service.ACTION_SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.securitycam.service.ACTION_TOGGLE_TORCH"

        private val _liveState = MutableStateFlow(SurveillanceLiveState())
        val liveState: StateFlow<SurveillanceLiveState> = _liveState.asStateFlow()

        @Volatile
        var activeSurfaceProvider: Preview.SurfaceProvider? = null
            set(value) {
                field = value
                try {
                    activeInstance?.previewUseCase?.setSurfaceProvider(value)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to set surface provider: ${e.message}")
                }
            }

        @Volatile
        private var activeInstance: SurveillanceService? = null

        fun startService(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun armCamera(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_ARM
            }
            context.startService(intent)
        }

        fun disarmCamera(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_DISARM
            }
            context.startService(intent)
        }

        fun toggleArm(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_TOGGLE_ARM
            }
            context.startService(intent)
        }

        fun switchCamera(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_SWITCH_CAMERA
            }
            context.startService(intent)
        }

        fun toggleTorch(context: Context) {
            val intent = Intent(context, SurveillanceService::class.java).apply {
                action = ACTION_TOGGLE_TORCH
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        cameraExecutor = Executors.newSingleThreadExecutor()
        acquireWakeLock()
        observeSettingsChanges()
        Log.i(TAG, "SurveillanceService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSurveillance()
                return START_NOT_STICKY
            }
            ACTION_ARM -> setArmed(true)
            ACTION_DISARM -> setArmed(false)
            ACTION_TOGGLE_ARM -> setArmed(!_liveState.value.isArmed)
            ACTION_SWITCH_CAMERA -> switchCameraLens()
            ACTION_TOGGLE_TORCH -> toggleTorchState()
            ACTION_START, null -> startSurveillance()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SecurityCam:SurveillanceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "PARTIAL_WAKE_LOCK acquired.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "PARTIAL_WAKE_LOCK released.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun startSurveillance() {
        val initialArmed = try {
            app.settingsRepository.isArmed()
        } catch (_: Exception) {
            true
        }
        val initialFacing = try {
            app.settingsRepository.getSettings().cameraFacing
        } catch (_: Exception) {
            "BACK"
        }

        val notification = buildOngoingNotification(initialArmed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                SecurityCamApp.NOTIFICATION_ID_SURVEILLANCE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(SecurityCamApp.NOTIFICATION_ID_SURVEILLANCE, notification)
        }

        _liveState.value = _liveState.value.copy(
            isRunning = true,
            isArmed = initialArmed,
            cameraFacing = initialFacing
        )

        initCameraX()
    }

    private fun stopSurveillance() {
        stopPatrolMode()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding CameraX: ${e.message}")
        }
        _liveState.value = _liveState.value.copy(isRunning = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setArmed(armed: Boolean) {
        try {
            app.settingsRepository.setArmed(armed)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save armed state to repository: ${e.message}")
        }
        _liveState.value = _liveState.value.copy(isArmed = armed)
        val notification = buildOngoingNotification(armed)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SecurityCamApp.NOTIFICATION_ID_SURVEILLANCE, notification)
    }

    fun switchCameraLens(persistSetting: Boolean = true) {
        val currentFacing = _liveState.value.cameraFacing
        val newFacing = if (currentFacing == "FRONT") "BACK" else "FRONT"
        if (persistSetting) {
            try {
                val currentSettings = app.settingsRepository.getSettings()
                app.settingsRepository.updateSettings(currentSettings.copy(cameraFacing = newFacing))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to update cameraFacing settings: ${e.message}")
            }
        }

        // R7: Clean background model reset to prevent false motion alarm on lens switch
        try {
            app.motionDetector.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset motion detector on lens switch: ${e.message}")
        }
        lastSnapshotTimestamp = 0L

        _liveState.value = _liveState.value.copy(
            cameraFacing = newFacing,
            isTorchOn = false,
            motionResult = null
        )
        initCameraX()
    }

    private fun updatePatrolState(enabled: Boolean, intervalSeconds: Int) {
        patrolJob?.cancel()
        if (!enabled) return

        val intervalMs = intervalSeconds.coerceAtLeast(10) * 1000L
        patrolJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(intervalMs)
                if (_liveState.value.isRunning) {
                    Log.i(TAG, "Patrol timer triggered: alternating camera lens.")
                    switchCameraLens(persistSetting = false)
                }
            }
        }
    }

    private fun stopPatrolMode() {
        patrolJob?.cancel()
        patrolJob = null
    }

    private fun toggleTorchState() {
        val currentTorch = _liveState.value.isTorchOn
        val newTorch = !currentTorch
        camera?.cameraControl?.let { control ->
            try {
                control.enableTorch(newTorch)
                _liveState.value = _liveState.value.copy(isTorchOn = newTorch)
            } catch (e: Exception) {
                Log.w(TAG, "Error toggling torch: ${e.message}")
            }
        }
    }

    private fun initCameraX() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases()
                } catch (e: Exception) {
                    Log.w(TAG, "CameraX initialization skipped or unavailable: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(TAG, "ProcessCameraProvider unavailable: ${e.message}")
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val facing = _liveState.value.cameraFacing
            val cameraSelector = if (facing == "FRONT") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // 1. Preview Use Case
            previewUseCase = Preview.Builder().build().also { preview ->
                activeSurfaceProvider?.let { surface ->
                    preview.setSurfaceProvider(surface)
                }
            }

            // 2. ImageAnalysis Use Case (24/7 background motion pipeline)
            val executor = cameraExecutor ?: ContextCompat.getMainExecutor(this)
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .also { attachExposureListener(it) }
                .build()
                .apply {
                    setAnalyzer(executor) { image ->
                        processCameraFrame(image)
                    }
                }

            camera = provider.bindToLifecycle(this, cameraSelector, previewUseCase, imageAnalysis)
            // A freshly bound camera starts in its normal configuration.
            nightVision.reset()
            nightBoostApplied = false
            lastIso = null
            lastExposureTimeNs = null
            _liveState.value = _liveState.value.copy(isNightVisionActive = false)
            Log.i(TAG, "CameraX use cases successfully bound to LifecycleService.")
        } catch (e: Exception) {
            Log.w(TAG, "CameraX binding error: ${e.message}")
        }
    }

    fun processCameraFrame(image: ImageProxy) {
        val timestamp = System.currentTimeMillis()
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            // Motion detection runs on a reduced copy (about 160 px wide) of the luminance plane,
            // so capturing at a higher resolution doesn't change how the detector behaves.
            val frame = FrameSampler.sampleLuminance(
                buffer, width, height, rowStride, timestamp, ANALYSIS_TARGET_WIDTH
            )
            val yBytes = frame.data

            val result = app.motionDetector.processFrame(frame)

            val averageLuminance = LowLightEnhancer.calculateAverageLuminance(yBytes)
            val nightVisionEnabled = currentSettings.nightVisionEnabled
            val nightActive = updateNightVision(averageLuminance, nightVisionEnabled)
            val brighten = nightVisionEnabled &&
                averageLuminance < NightVisionController.DIGITAL_BOOST_BELOW_LUMINANCE

            _liveState.value = _liveState.value.copy(
                motionResult = result,
                isNightVisionActive = nightActive
            )

            val isFrontCamera = _liveState.value.cameraFacing.equals("FRONT", ignoreCase = true)
            publishStreamFrame(image, timestamp, isFrontCamera, brighten)

            if (result.isMotionDetected && _liveState.value.isArmed) {
                val now = timestamp
                // R1: Throttle frame captures to 1 snapshot per SNAPSHOT_BURST_THROTTLE_MS during continuous motion
                val throttle = if (isHoldingAlert) SNAPSHOT_HOLD_THROTTLE_MS else SNAPSHOT_BURST_THROTTLE_MS
                if (now - lastSnapshotTimestamp >= throttle) {
                    lastSnapshotTimestamp = now
                    val rawBitmap = try {
                        image.toBitmap()
                    } catch (e: Exception) {
                        null
                    }
                    if (rawBitmap != null) {
                        val rotationDegrees = try {
                            image.imageInfo.rotationDegrees
                        } catch (_: Exception) {
                            0
                        }
                        var orientedBitmap = orientBitmap(rawBitmap, rotationDegrees, isFrontCamera)
                        if (brighten) {
                            orientedBitmap = BitmapEnhancer.brighten(orientedBitmap)
                        }
                        val orientedBoxes = orientBoundingBoxes(result.boundingBoxes, rotationDegrees, isFrontCamera)
                        onMotionIntrusionDetected(
                            Capture(orientedBitmap, result, orientedBoxes, now, result.isLowLight || brighten)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in frame analysis: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    /** Encodes the frame for the web viewer, but only while somebody is watching. */
    private fun publishStreamFrame(
        image: ImageProxy,
        timestamp: Long,
        isFrontCamera: Boolean,
        brighten: Boolean
    ) {
        val hub = app.frameHub
        if (!hub.hasViewers || timestamp - lastStreamFrameAt < STREAM_FRAME_INTERVAL_MS) return
        lastStreamFrameAt = timestamp
        try {
            val rotationDegrees = image.imageInfo.rotationDegrees
            var bitmap = orientBitmap(image.toBitmap(), rotationDegrees, isFrontCamera)
            if (brighten) {
                bitmap = BitmapEnhancer.brighten(bitmap)
            }
            val out = ByteArrayOutputStream(64 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, STREAM_JPEG_QUALITY, out)
            hub.publish(out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to encode frame for the viewer: ${e.message}")
        }
    }

    /** Records the ISO and exposure time the sensor used, which is how darkness is measured. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun attachExposureListener(builder: ImageAnalysis.Builder) {
        try {
            Camera2Interop.Extender(builder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        lastExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Exposure metadata unavailable: ${e.message}")
        }
    }

    private fun updateNightVision(averageLuminance: Float, enabled: Boolean): Boolean {
        if (!enabled) {
            if (nightBoostApplied || nightVision.isActive) {
                nightVision.reset()
                applyNightBoost(false)
            }
            return false
        }
        val brightness = NightVisionController.sceneBrightness(averageLuminance, lastIso, lastExposureTimeNs)
        val active = nightVision.update(brightness)
        if (active != nightBoostApplied) {
            applyNightBoost(active)
        }
        return active
    }

    /**
     * Night configuration: let auto-exposure use its slowest frame rate, which allows much longer
     * exposures, and bias exposure up by about one stop. Cleared again when the scene is bright.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyNightBoost(active: Boolean) {
        nightBoostApplied = active
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val exposureState = cam.cameraInfo.exposureState
            if (active) {
                val ranges: Array<Range<Int>>? = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val slowest = ranges?.minWithOrNull(
                    compareBy<Range<Int>> { it.lower }.thenByDescending { it.upper }
                )
                if (slowest != null) {
                    camera2Control.setCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slowest)
                            .build()
                    )
                }
                if (exposureState.isExposureCompensationSupported) {
                    val step = exposureState.exposureCompensationStep.toFloat()
                    if (step > 0f) {
                        val oneStop = Math.round(1f / step)
                            .coerceAtMost(exposureState.exposureCompensationRange.upper)
                        cam.cameraControl.setExposureCompensationIndex(oneStop)
                    }
                }
            } else {
                camera2Control.clearCaptureRequestOptions()
                if (exposureState.isExposureCompensationSupported) {
                    cam.cameraControl.setExposureCompensationIndex(0)
                }
            }
            Log.i(TAG, "Night vision ${if (active) "on" else "off"}")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to change night configuration: ${e.message}")
        }
    }

    fun orientBitmap(rawBitmap: Bitmap, rotationDegrees: Int, isFrontFacing: Boolean): Bitmap {
        if (rotationDegrees == 0 && !isFrontFacing) {
            return rawBitmap
        }
        return try {
            val matrix = Matrix()
            if (isFrontFacing) {
                // Mirror horizontally across center axis
                matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
            }
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees.toFloat())
            }
            val transformed = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            transformed
        } catch (e: Exception) {
            Log.w(TAG, "Matrix rotation/mirroring failed, fallback to raw: ${e.message}")
            rawBitmap
        }
    }

    fun orientBoundingBoxes(
        boxes: List<NormalizedRect>,
        rotationDegrees: Int,
        isFrontFacing: Boolean
    ): List<NormalizedRect> {
        if ((rotationDegrees == 0 && !isFrontFacing) || boxes.isEmpty()) {
            return boxes
        }
        return boxes.map { box ->
            var left = box.left
            var top = box.top
            var right = box.right
            var bottom = box.bottom

            if (isFrontFacing) {
                val mirrorLeft = 1f - right
                val mirrorRight = 1f - left
                left = minOf(mirrorLeft, mirrorRight)
                right = maxOf(mirrorLeft, mirrorRight)
            }

            when (rotationDegrees) {
                90 -> {
                    val newLeft = 1f - bottom
                    val newTop = left
                    val newRight = 1f - top
                    val newBottom = right
                    left = minOf(newLeft, newRight)
                    right = maxOf(newLeft, newRight)
                    top = minOf(newTop, newBottom)
                    bottom = maxOf(newTop, newBottom)
                }
                180 -> {
                    val newLeft = 1f - right
                    val newTop = 1f - bottom
                    val newRight = 1f - left
                    val newBottom = 1f - top
                    left = minOf(newLeft, newRight)
                    right = maxOf(newLeft, newRight)
                    top = minOf(newTop, newBottom)
                    bottom = maxOf(newTop, newBottom)
                }
                270 -> {
                    val newLeft = top
                    val newTop = 1f - right
                    val newRight = bottom
                    val newBottom = 1f - left
                    left = minOf(newLeft, newRight)
                    right = maxOf(newLeft, newRight)
                    top = minOf(newTop, newBottom)
                    bottom = maxOf(newTop, newBottom)
                }
            }

            NormalizedRect(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                right = right.coerceIn(0f, 1f),
                bottom = bottom.coerceIn(0f, 1f)
            )
        }
    }

    private fun onMotionIntrusionDetected(capture: Capture) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                gateMutex.withLock { routeCapture(capture) }
            } catch (e: Exception) {
                Log.e(TAG, "Intrusion capture or alert dispatch failed: ${e.message}", e)
            }
        }
    }

    /**
     * Sends the capture straight to alerting, unless known-people recognition is on, in which
     * case [PresenceGate] decides between alerting, holding briefly, and staying quiet.
     */
    private suspend fun routeCapture(capture: Capture) {
        val people = if (currentSettings.knownPeopleEnabled) app.knownPeopleStore.current() else emptyList()
        if (people.isEmpty()) {
            dispatchCapture(capture, recognizedPeople = null, suppressAlert = false)
            return
        }

        // If the analysis fails, treat it as "no face seen": the alert is held, then sent.
        val embeddings = try {
            app.faceAnalyzer.analyze(capture.bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Face analysis failed: ${e.message}")
            null
        }
        val verdict = if (embeddings == null) FaceVerdict.NO_FACES else FaceMatcher.verdict(embeddings, people)

        when (val decision = presenceGate.evaluate(capture.timestamp, verdict)) {
            is PresenceGate.Decision.Alert -> {
                // Alert with the first picture of this motion if one was being held.
                val first = takeHeldCapture() ?: capture
                dispatchCapture(first, recognizedPeople = null, suppressAlert = false)
            }
            is PresenceGate.Decision.Hold -> {
                if (heldCapture == null) {
                    heldCapture = capture
                    isHoldingAlert = true
                    holdTimer = lifecycleScope.launch(Dispatchers.IO) {
                        delay(presenceGate.holdMs + 250)
                        gateMutex.withLock {
                            if (presenceGate.expireHold(System.currentTimeMillis())) {
                                takeHeldCapture(cancelTimer = false)?.let {
                                    dispatchCapture(it, recognizedPeople = null, suppressAlert = false)
                                }
                            }
                        }
                    }
                }
            }
            is PresenceGate.Decision.Suppress -> {
                takeHeldCapture()
                dispatchCapture(
                    capture,
                    recognizedPeople = decision.names.joinToString(", ").ifEmpty { null },
                    suppressAlert = true
                )
            }
        }
    }

    private fun takeHeldCapture(cancelTimer: Boolean = true): Capture? {
        val held = heldCapture
        heldCapture = null
        isHoldingAlert = false
        if (cancelTimer) {
            holdTimer?.cancel()
        }
        holdTimer = null
        return held
    }

    private suspend fun dispatchCapture(capture: Capture, recognizedPeople: String?, suppressAlert: Boolean) {
        val screenshotFile = app.imageStorageManager.saveScreenshot(capture.bitmap, capture.timestamp, capture.boxes)
        val payload = AlertPayload(
            eventId = UUID.randomUUID().toString(),
            timestamp = capture.timestamp,
            motionScore = capture.result.motionScore,
            screenshotFile = screenshotFile,
            cameraFacing = _liveState.value.cameraFacing,
            isLowLight = capture.isLowLight,
            recognizedPeople = recognizedPeople,
            suppressAlert = suppressAlert
        )
        app.alertCoordinator.onMotionDetected(payload)
        _liveState.value = _liveState.value.copy(lastEventTimestamp = capture.timestamp)
    }

    /** Starts or stops the live web viewer to match the settings. */
    @Synchronized
    private fun syncViewerServer(settings: SecuritySettings) {
        val server = app.viewerServer
        val shouldRun = settings.remoteViewEnabled &&
            ViewerServer.isAcceptablePassword(settings.remoteViewPassword)
        try {
            if (shouldRun && !server.isRunning) {
                server.start(ViewerServer.DEFAULT_PORT)
                Log.i(TAG, "Live viewer listening on port ${server.port}")
            } else if (!shouldRun && server.isRunning) {
                server.stop()
                Log.i(TAG, "Live viewer stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start the live viewer: ${e.message}")
        }
    }

    fun buildOngoingNotification(isArmed: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleArmIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SurveillanceService::class.java).apply { action = ACTION_TOGGLE_ARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isArmed) "🔴 Camera ARMED — Surveillance Active" else "⚪ Camera DISARMED — Standby"
        val armActionText = if (isArmed) "Disarm" else "Arm Camera"

        return NotificationCompat.Builder(this, SecurityCamApp.CHANNEL_SURVEILLANCE_ID)
            .setContentTitle(title)
            .setContentText("Continuous background motion detection active.")
            .setSmallIcon(R.drawable.ic_stat_surveillance)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stat_alert, armActionText, toggleArmIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun observeSettingsChanges() {
        lifecycleScope.launch {
            try {
                app.settingsRepository.observeSettings().collect { settings ->
                    currentSettings = settings
                    launch(Dispatchers.IO) { syncViewerServer(settings) }
                    _liveState.value = _liveState.value.copy(
                        isArmed = settings.isArmed,
                        cameraFacing = settings.cameraFacing
                    )
                    updatePatrolState(settings.isPatrolModeEnabled, settings.patrolIntervalSeconds)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Settings observer stopped: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPatrolMode()
        try {
            app.viewerServer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping the live viewer: ${e.message}")
        }
        activeInstance = null
        activeSurfaceProvider = null
        releaseWakeLock()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        Log.i(TAG, "SurveillanceService destroyed.")
    }
}
