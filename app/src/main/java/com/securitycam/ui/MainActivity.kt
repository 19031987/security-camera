package com.securitycam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.ActivityMainBinding
import com.securitycam.service.SurveillanceLiveState
import com.securitycam.service.SurveillanceService
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live Camera Preview & Surveillance Management UI.
 * Connects directly to [SurveillanceService] for CameraX preview sharing,
 * real-time motion bounding box display, and quick arm/disarm actions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Other permissions (notifications, SMS) are optional; only the camera gates the feed.
        if (hasCameraPermission()) {
            startSurveillanceAndAttachPreview()
        }
        updatePermissionCard()
        updateUiState(SurveillanceService.liveState.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topBar.padTopForSystemBars()
        BottomNav.setup(this, binding.bottomNav.root, R.id.nav_live)

        setupClickListeners()
        observeSurveillanceState()
        observeLatestEvent()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNav.root.menu.findItem(R.id.nav_live).isChecked = true
        updatePermissionCard()
        updateUiState(SurveillanceService.liveState.value)
        if (hasCameraPermission()) {
            startSurveillanceAndAttachPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        // Detach surface provider when UI is not visible, allowing background service to continue ImageAnalysis
        SurveillanceService.activeSurfaceProvider = null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsToRequest.add(Manifest.permission.SEND_SMS)

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        } else {
            startSurveillanceAndAttachPreview()
        }
    }

    private fun startSurveillanceAndAttachPreview() {
        SurveillanceService.startService(this)
        SurveillanceService.activeSurfaceProvider = binding.previewView.surfaceProvider
    }

    private fun updatePermissionCard() {
        val granted = hasCameraPermission()
        binding.cardPermission.visibility = if (granted) View.GONE else View.VISIBLE
        binding.fabArmDisarm.isEnabled = granted
        binding.btnSwitchCamera.isEnabled = granted
        if (!granted) {
            binding.btnToggleTorch.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        binding.fabArmDisarm.setOnClickListener {
            SurveillanceService.toggleArm(this)
        }

        binding.btnSwitchCamera.setOnClickListener {
            SurveillanceService.switchCamera(this)
        }

        binding.btnToggleTorch.setOnClickListener {
            SurveillanceService.toggleTorch(this)
        }

        binding.rowLatestEvent.setOnClickListener {
            startActivity(Intent(this, EventHistoryActivity::class.java))
        }

        binding.btnGrantPermission.setOnClickListener {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }

        binding.btnOpenAppSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    private fun observeSurveillanceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SurveillanceService.liveState.collect { state ->
                    updateUiState(state)
                }
            }
        }
    }

    private fun observeLatestEvent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SecurityCamApp.getInstance().eventRepository.observeEvents().collect { events ->
                    updateLatestEvent(events)
                }
            }
        }
    }

    private fun updateLatestEvent(events: List<MotionEventEntity>) {
        val latest = events.maxByOrNull { it.timestamp }
        if (latest == null) {
            binding.ivLatestThumb.setImageDrawable(null)
            binding.tvLatestTitle.setText(R.string.latest_event_none_title)
            binding.tvLatestSubtitle.setText(R.string.latest_event_none_subtitle)
            return
        }

        binding.ivLatestThumb.load(File(latest.imagePath)) {
            crossfade(true)
            error(R.drawable.thumbnail_placeholder)
        }

        val now = System.currentTimeMillis()
        val isToday = EventGrouping.dayKind(EventGrouping.startOfDay(latest.timestamp), now) ==
            EventGrouping.DayKind.TODAY
        val whenText = if (isToday) {
            EventAdapter.formatTime(latest.timestamp)
        } else {
            EventAdapter.formatTimestamp(latest.timestamp)
        }
        binding.tvLatestTitle.text = getString(R.string.latest_event_title, whenText)

        val todayCount = EventGrouping.countToday(events, now)
        binding.tvLatestSubtitle.text = if (todayCount == 0) {
            getString(R.string.events_today_none)
        } else {
            resources.getQuantityString(R.plurals.events_today, todayCount, todayCount)
        }
    }

    private fun updateUiState(state: SurveillanceLiveState) {
        val motionResult = state.motionResult
        val isMotion = motionResult != null && motionResult.isMotionDetected

        // 1. Status pill
        val (pillText, pillColor, pillContainer) = when {
            isMotion && state.isArmed -> Triple(
                R.string.status_motion_detected, R.color.status_alert, R.color.status_alert_container
            )
            state.isArmed -> Triple(
                R.string.status_armed, R.color.status_armed, R.color.status_armed_container
            )
            else -> Triple(
                R.string.status_disarmed, R.color.status_disarmed, R.color.status_disarmed_container
            )
        }
        val pillTint = ContextCompat.getColor(this, pillColor)
        binding.cardStatusBadge.setCardBackgroundColor(ContextCompat.getColor(this, pillContainer))
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(pillTint)
        binding.tvStatusBadge.setText(pillText)
        binding.tvStatusBadge.setTextColor(pillTint)

        binding.tvNightBadge.visibility = if (state.isNightVisionActive) View.VISIBLE else View.GONE

        // 2. Arm / disarm button. Arming is the highlighted action; disarming is deliberately quieter.
        if (state.isArmed) {
            binding.fabArmDisarm.setText(R.string.action_disarm)
            binding.fabArmDisarm.setIconResource(R.drawable.ic_shield_disarmed)
            styleArmButton(R.color.surface_container_highest, R.color.on_surface)
        } else {
            binding.fabArmDisarm.setText(R.string.action_arm)
            binding.fabArmDisarm.setIconResource(R.drawable.ic_shield_armed)
            styleArmButton(R.color.primary, R.color.on_primary)
        }

        // 3. Torch is only available on the back camera
        val isFrontCamera = state.cameraFacing == "FRONT"
        binding.btnToggleTorch.isEnabled = !isFrontCamera && hasCameraPermission()
        binding.btnToggleTorch.setIconResource(
            if (state.isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )

        // 4. Bounding Box Overlay & Mirroring
        binding.boundingBoxOverlay.boxes = motionResult?.boundingBoxes ?: emptyList()
        binding.boundingBoxOverlay.motionScore = motionResult?.motionScore ?: 0f
        binding.boundingBoxOverlay.isMirrored = isFrontCamera
        binding.boundingBoxOverlay.isArmed = state.isArmed

        // 5. Status line
        when {
            !hasCameraPermission() -> {
                binding.tvDetectionStatus.setText(R.string.permission_camera_title)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
            isMotion -> {
                val scorePct = ((motionResult?.motionScore ?: 0f) * 100).toInt()
                binding.tvDetectionStatus.text =
                    getString(R.string.status_motion_detected_score, scorePct)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
            }
            !state.isRunning -> {
                binding.tvDetectionStatus.setText(R.string.status_camera_starting)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
            state.isArmed -> {
                binding.tvDetectionStatus.setText(R.string.status_monitoring_active)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            }
            else -> {
                binding.tvDetectionStatus.setText(R.string.status_standby)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
        }
    }

    private fun styleArmButton(backgroundColor: Int, contentColor: Int) {
        val content = ColorStateList.valueOf(ContextCompat.getColor(this, contentColor))
        binding.fabArmDisarm.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColor))
        binding.fabArmDisarm.setTextColor(content)
        binding.fabArmDisarm.iconTint = content
    }
}
