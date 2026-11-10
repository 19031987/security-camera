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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.alerting.AlertPolicy
import com.securitycam.core.surveillance.MotionZone
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

    /** True while the mode selector is being set from state rather than by a tap. */
    private var syncingMode = false

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

    /** True when this phone is set up to receive notifications rather than to be the camera. */
    private var isReceiverPhone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val role = SecurityCamApp.getInstance().settingsRepository.getSettings().deviceRole
        if (role == SecuritySettings.ROLE_RECEIVER) {
            // A receiver phone never opens its own camera.
            isReceiverPhone = true
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topBar.padTopForSystemBars()
        BottomNav.setup(this, binding.bottomNav.root, R.id.nav_live)

        setupClickListeners()
        observeSurveillanceState()
        observeLatestEvent()
        if (role.isEmpty()) {
            askHowThisPhoneIsUsed()
        } else {
            checkAndRequestPermissions()
        }
    }

    /** First launch: the same app is installed on the camera phone and on the family's phones. */
    private fun askHowThisPhoneIsUsed() {
        val repository = SecurityCamApp.getInstance().settingsRepository
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.role_dialog_title)
            .setMessage(R.string.role_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.role_camera) { _, _ ->
                repository.updateSettings(repository.getSettings().copy(deviceRole = SecuritySettings.ROLE_CAMERA))
                checkAndRequestPermissions()
            }
            .setNegativeButton(R.string.role_receiver) { _, _ ->
                repository.updateSettings(repository.getSettings().copy(deviceRole = SecuritySettings.ROLE_RECEIVER))
                isReceiverPhone = true
                SurveillanceService.stopService(this)
                startActivity(Intent(this, ReceiverActivity::class.java))
                finish()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (isReceiverPhone) return
        binding.bottomNav.root.menu.findItem(R.id.nav_live).isChecked = true
        updatePermissionCard()
        if (!binding.motionZoneView.isEditing) loadZone()
        updateUiState(SurveillanceService.liveState.value)
        if (hasCameraPermission()) {
            startSurveillanceAndAttachPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isReceiverPhone) return
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
        binding.btnModeAway.isEnabled = granted
        binding.btnModeHome.isEnabled = granted
        binding.btnModeOff.isEnabled = granted
        binding.btnZone.isEnabled = granted
        binding.btnSwitchCamera.isEnabled = granted
        if (!granted) {
            binding.btnToggleTorch.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            // Only react to the person's own taps, not to the selection being synced from state.
            if (!isChecked || syncingMode) return@addOnButtonCheckedListener
            val repository = SecurityCamApp.getInstance().settingsRepository
            when (checkedId) {
                R.id.btnModeOff -> SurveillanceService.disarmCamera(this)
                R.id.btnModeAway, R.id.btnModeHome -> {
                    val mode = if (checkedId == R.id.btnModeHome) AlertPolicy.MODE_HOME else AlertPolicy.MODE_AWAY
                    repository.updateSettings(repository.getSettings().copy(alertMode = mode, isArmed = true))
                    SurveillanceService.armCamera(this)
                    // Switching between Away and Home doesn't change the armed state, so refresh here.
                    updateUiState(SurveillanceService.liveState.value.copy(isArmed = true))
                }
            }
        }

        binding.btnZone.setOnClickListener { setZoneEditing(!binding.motionZoneView.isEditing) }
        binding.btnZoneReset.setOnClickListener { binding.motionZoneView.zone = MotionZone.WHOLE_PICTURE }
        binding.btnZoneDone.setOnClickListener { setZoneEditing(false) }

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
            state.isArmed && isHomeMode() -> Triple(
                R.string.status_mode_home, R.color.secondary, R.color.secondary_container
            )
            state.isArmed -> Triple(
                R.string.status_mode_away, R.color.status_armed, R.color.status_armed_container
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

        // 2. Mode selector follows the real state (it can also change from the notification).
        val mode = SecurityCamApp.getInstance().settingsRepository.getSettings().alertMode
        val modeButton = when {
            !state.isArmed -> R.id.btnModeOff
            mode == AlertPolicy.MODE_HOME -> R.id.btnModeHome
            else -> R.id.btnModeAway
        }
        if (binding.toggleMode.checkedButtonId != modeButton) {
            syncingMode = true
            binding.toggleMode.check(modeButton)
            syncingMode = false
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
                binding.tvDetectionStatus.setText(
                    if (isHomeMode()) R.string.status_home_quiet else R.string.status_away_watching
                )
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            }
            else -> {
                binding.tvDetectionStatus.setText(R.string.status_standby)
                binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
        }
    }

    private fun isHomeMode(): Boolean =
        SecurityCamApp.getInstance().settingsRepository.getSettings().alertMode == AlertPolicy.MODE_HOME

    /** Swaps the control panel for the zone editor and saves the zone when editing ends. */
    private fun setZoneEditing(editing: Boolean) {
        val repository = SecurityCamApp.getInstance().settingsRepository
        if (!editing && binding.motionZoneView.isEditing) {
            val zone = binding.motionZoneView.zone
            repository.updateSettings(
                repository.getSettings().copy(
                    zoneLeft = zone.left, zoneTop = zone.top, zoneRight = zone.right, zoneBottom = zone.bottom
                )
            )
        }
        binding.motionZoneView.isEditing = editing
        binding.zoneControls.visibility = if (editing) View.VISIBLE else View.GONE
        binding.bottomControls.visibility = if (editing) View.GONE else View.VISIBLE
    }

    private fun loadZone() {
        val settings = SecurityCamApp.getInstance().settingsRepository.getSettings()
        binding.motionZoneView.zone =
            MotionZone(settings.zoneLeft, settings.zoneTop, settings.zoneRight, settings.zoneBottom)
    }
}
