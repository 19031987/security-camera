package com.securitycam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.remote.AlertEventClient
import com.securitycam.core.remote.ViewerServer
import com.securitycam.databinding.ActivityReceiverBinding
import com.securitycam.service.ReceiverService
import com.securitycam.service.ReceiverState
import kotlinx.coroutines.launch

/**
 * Home screen of a phone used to receive alerts. Holds the camera phone's address and viewing
 * password, shows whether the direct connection is up, and opens the live view.
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiverBinding
    private val app by lazy { SecurityCamApp.getInstance() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateNotificationWarning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarReceiver.padTopForSystemBars()
        binding.scrollReceiver.padBottomForSystemBarsAndKeyboard()

        val settings = app.settingsRepository.getSettings()
        binding.etReceiverAddress.setText(settings.receiverCameraAddress)
        binding.etReceiverPassword.setText(settings.receiverPassword)

        binding.btnReceiverConnect.setOnClickListener {
            if (app.settingsRepository.getSettings().receiverEnabled) disconnect() else connect()
        }
        binding.btnOpenLiveView.setOnClickListener { openLiveView() }
        binding.btnReceiverBattery.setOnClickListener { requestBatteryExemption() }
        binding.btnUseAsCamera.setOnClickListener { switchToCamera() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReceiverService.state.collect { render(it) }
            }
        }

        // Reconnect after the app was closed while the receiver was meant to be on.
        if (settings.receiverEnabled && ReceiverService.state.value.status == ReceiverState.Status.OFF) {
            ReceiverService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationWarning()
        val exempt = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        binding.btnReceiverBattery.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun connect() {
        val address = binding.etReceiverAddress.text?.toString()?.trim().orEmpty()
        val password = binding.etReceiverPassword.text?.toString().orEmpty()
        val addressOk = AlertEventClient.parseAddress(address) != null
        val passwordOk = ViewerServer.isAcceptablePassword(password)
        binding.tilReceiverAddress.error = if (addressOk) null else getString(R.string.error_receiver_address)
        binding.tilReceiverPassword.error = if (passwordOk) null else getString(R.string.error_receiver_password)
        if (!addressOk || !passwordOk) return

        val cur = app.settingsRepository.getSettings()
        app.settingsRepository.updateSettings(
            cur.copy(
                deviceRole = SecuritySettings.ROLE_RECEIVER,
                receiverCameraAddress = address,
                receiverPassword = password,
                receiverEnabled = true
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReceiverService.start(this)
        render(ReceiverService.state.value)
    }

    private fun disconnect() {
        val cur = app.settingsRepository.getSettings()
        app.settingsRepository.updateSettings(cur.copy(receiverEnabled = false))
        ReceiverService.stop(this)
        render(ReceiverService.state.value)
    }

    private fun render(state: ReceiverState) {
        val enabled = app.settingsRepository.getSettings().receiverEnabled
        binding.btnReceiverConnect.setText(
            if (enabled) R.string.action_receiver_disconnect else R.string.action_receiver_connect
        )
        binding.etReceiverAddress.isEnabled = !enabled
        binding.etReceiverPassword.isEnabled = !enabled

        binding.tvReceiverStatus.text = when (state.status) {
            ReceiverState.Status.OFF ->
                getString(if (enabled) R.string.receiver_status_connecting else R.string.receiver_status_off)
            ReceiverState.Status.CONNECTING -> getString(R.string.receiver_status_connecting)
            ReceiverState.Status.CONNECTED -> getString(R.string.receiver_status_connected)
            ReceiverState.Status.UNREACHABLE -> getString(R.string.receiver_status_unreachable, state.detail)
            ReceiverState.Status.WRONG_PASSWORD -> getString(R.string.receiver_status_wrong_password)
            ReceiverState.Status.LOCKED_OUT -> getString(R.string.receiver_status_locked_out)
            ReceiverState.Status.BAD_ADDRESS -> getString(R.string.receiver_status_bad_address)
        }
        binding.tvReceiverStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.status) {
                    ReceiverState.Status.CONNECTED -> R.color.status_armed
                    ReceiverState.Status.UNREACHABLE, ReceiverState.Status.WRONG_PASSWORD,
                    ReceiverState.Status.LOCKED_OUT, ReceiverState.Status.BAD_ADDRESS -> R.color.status_disarmed
                    else -> R.color.on_surface
                }
            )
        )

        val lastTitle = state.lastAlertTitle
        binding.tvReceiverLastAlert.visibility = if (lastTitle == null) View.GONE else View.VISIBLE
        if (lastTitle != null) {
            binding.tvReceiverLastAlert.text =
                getString(R.string.receiver_last_alert, lastTitle, EventAdapter.formatTime(state.lastAlertTime))
        }
    }

    private fun updateNotificationWarning() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        binding.tvReceiverNotificationsOff.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun openLiveView() {
        val base = AlertEventClient.parseAddress(binding.etReceiverAddress.text?.toString().orEmpty())
        if (base == null) {
            binding.tilReceiverAddress.error = getString(R.string.error_receiver_address)
            return
        }
        try {
            // Opens in the browser, which asks for the viewing password itself.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base.toString())))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.battery_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun switchToCamera() {
        val cur = app.settingsRepository.getSettings()
        app.settingsRepository.updateSettings(
            cur.copy(deviceRole = SecuritySettings.ROLE_CAMERA, receiverEnabled = false)
        )
        ReceiverService.stop(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
