package com.securitycam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.securitycam.R
import com.securitycam.databinding.ActivityMainBinding
import com.securitycam.service.SurveillanceLiveState
import com.securitycam.service.SurveillanceService
import kotlinx.coroutines.launch

/**
 * Live Camera Preview & Surveillance Management UI.
 * Connects directly to [SurveillanceService] for CameraX preview sharing,
 * real-time motion bounding box display, and quick arm/disarm actions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startSurveillanceAndAttachPreview()
        } else {
            Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        observeSurveillanceState()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
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

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, EventHistoryActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

    private fun updateUiState(state: SurveillanceLiveState) {
        // 1. Arm / Disarm Visuals
        if (state.isArmed) {
            binding.cardStatusBadge.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.status_armed_container)
            )
            binding.cardStatusBadge.strokeColor = ContextCompat.getColor(this, R.color.status_armed)
            binding.tvStatusBadge.text = getString(R.string.status_armed)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_armed))

            binding.fabArmDisarm.text = getString(R.string.action_disarm)
            binding.fabArmDisarm.setIconResource(R.drawable.ic_shield_armed)
            binding.fabArmDisarm.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_alert)
            )
        } else {
            binding.cardStatusBadge.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.status_disarmed_container)
            )
            binding.cardStatusBadge.strokeColor = ContextCompat.getColor(this, R.color.status_disarmed)
            binding.tvStatusBadge.text = getString(R.string.status_disarmed)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_disarmed))

            binding.fabArmDisarm.text = getString(R.string.action_arm)
            binding.fabArmDisarm.setIconResource(R.drawable.ic_shield_disarmed)
            binding.fabArmDisarm.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_armed)
            )
        }

        // 2. Torch button state
        val isFrontCamera = state.cameraFacing == "FRONT"
        binding.btnToggleTorch.isEnabled = !isFrontCamera
        binding.btnToggleTorch.alpha = if (isFrontCamera) 0.35f else 1.0f
        binding.btnToggleTorch.setImageResource(
            if (state.isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )

        // 3. Bounding Box Overlay & Mirroring
        binding.boundingBoxOverlay.boxes = state.motionResult?.boundingBoxes ?: emptyList()
        binding.boundingBoxOverlay.motionScore = state.motionResult?.motionScore ?: 0f
        binding.boundingBoxOverlay.isMirrored = isFrontCamera
        binding.boundingBoxOverlay.isArmed = state.isArmed

        // 4. Status text banner
        val motionResult = state.motionResult
        if (motionResult != null && motionResult.isMotionDetected) {
            val scorePct = (motionResult.motionScore * 100).toInt()
            binding.tvDetectionStatus.text = "⚠️ MOTION DETECTED ($scorePct%)"
            binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_alert))
        } else if (state.isArmed) {
            binding.tvDetectionStatus.text = getString(R.string.status_monitoring_active)
            binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
        } else {
            binding.tvDetectionStatus.text = getString(R.string.status_standby)
            binding.tvDetectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
        }
    }
}
