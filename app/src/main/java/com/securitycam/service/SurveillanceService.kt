package com.securitycam.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
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
import com.securitycam.core.surveillance.LuminanceFrame
import com.securitycam.core.surveillance.MotionResult
import com.securitycam.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val lastEventTimestamp: Long = 0L
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

    companion object {
        const val TAG = "SurveillanceService"

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

    private fun switchCameraLens() {
        val currentFacing = _liveState.value.cameraFacing
        val newFacing = if (currentFacing == "FRONT") "BACK" else "FRONT"
        try {
            val currentSettings = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(currentSettings.copy(cameraFacing = newFacing))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update cameraFacing settings: ${e.message}")
        }
        _liveState.value = _liveState.value.copy(
            cameraFacing = newFacing,
            isTorchOn = false
        )
        initCameraX()
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
                .setTargetResolution(Size(160, 120))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .apply {
                    setAnalyzer(executor) { image ->
                        processCameraFrame(image)
                    }
                }

            camera = provider.bindToLifecycle(this, cameraSelector, previewUseCase, imageAnalysis)
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

            val yBytes = ByteArray(width * height)
            if (rowStride == width) {
                buffer.get(yBytes)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(yBytes, row * width, width)
                }
            }

            val frame = LuminanceFrame(yBytes, width, height, timestamp)
            val result = app.motionDetector.processFrame(frame)

            _liveState.value = _liveState.value.copy(motionResult = result)

            if (result.isMotionDetected && _liveState.value.isArmed) {
                val bitmap = try {
                    image.toBitmap()
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    onMotionIntrusionDetected(bitmap, result, timestamp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in frame analysis: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    private fun onMotionIntrusionDetected(bitmap: Bitmap, result: MotionResult, timestamp: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val screenshotFile = app.imageStorageManager.saveScreenshot(bitmap, timestamp, result.boundingBoxes)
                val payload = AlertPayload(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    motionScore = result.motionScore,
                    screenshotFile = screenshotFile,
                    cameraFacing = _liveState.value.cameraFacing
                )
                app.alertCoordinator.onMotionDetected(payload)
                _liveState.value = _liveState.value.copy(lastEventTimestamp = timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Intrusion capture or alert dispatch failed: ${e.message}", e)
            }
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
                    _liveState.value = _liveState.value.copy(
                        isArmed = settings.isArmed,
                        cameraFacing = settings.cameraFacing
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Settings observer stopped: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstance = null
        activeSurfaceProvider = null
        releaseWakeLock()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        Log.i(TAG, "SurveillanceService destroyed.")
    }
}
