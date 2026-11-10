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
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.push.PushMessage
import com.securitycam.core.push.ReceivedAlert
import com.securitycam.databinding.ActivityReceiverBinding
import com.securitycam.databinding.ItemReceivedAlertBinding
import com.securitycam.service.AlertMessagingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen of a phone that receives the camera's notifications. The phone is linked by
 * entering the camera's link code, which subscribes it to that camera's push messages.
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

        binding.etReceiverCode.setText(app.settingsRepository.getSettings().receiverLinkCode)
        binding.btnReceiverLink.setOnClickListener { if (isLinked()) unlink() else link() }
        binding.btnReceiverBattery.setOnClickListener { requestBatteryExemption() }
        binding.btnUseAsCamera.setOnClickListener { switchToCamera() }

        binding.btnWatchLive.setOnClickListener {
            if (isLinked()) {
                startActivity(Intent(this, LiveViewActivity::class.java))
            } else {
                Toast.makeText(this, R.string.live_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnClearReceived.setOnClickListener { app.receivedAlerts.clear() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.receivedAlerts.alerts.collect { renderReceived(it) }
            }
        }

        AlertMessagingService.loadLastAlert(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlertMessagingService.lastAlert.collect { last ->
                    binding.tvReceiverLastAlert.visibility = if (last == null) View.GONE else View.VISIBLE
                    if (last != null) {
                        binding.tvReceiverLastAlert.text =
                            getString(R.string.receiver_last_alert, last.title, EventAdapter.formatTimestamp(last.time))
                    }
                }
            }
        }
        renderLinked()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationWarning()
        val exempt = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        binding.btnReceiverBattery.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun renderReceived(alerts: List<ReceivedAlert>) {
        binding.tvReceivedEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearReceived.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        binding.listReceived.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (alert in alerts) {
            val row = ItemReceivedAlertBinding.inflate(inflater, binding.listReceived, false)
            row.tvReceivedTitle.text = alert.title
            row.tvReceivedTime.text = EventAdapter.formatTimestamp(alert.time)
            val picture = app.receivedAlerts.pictureOf(alert)
            if (picture != null) {
                row.ivReceivedPicture.setImageBitmap(BitmapFactory.decodeFile(picture.absolutePath))
            } else {
                row.ivReceivedPicture.setImageDrawable(null)
            }
            if (picture != null || alert.pictureId != null) {
                row.root.setOnClickListener { openPicture(alert) }
            }
            binding.listReceived.addView(row.root)
        }
    }

    /**
     * Shows an alert's picture large. If only the thumbnail is on this phone and the camera
     * stored a full-size one, that is fetched first.
     */
    private fun openPicture(alert: ReceivedAlert) {
        val pictureId = alert.pictureId
        if (alert.isFullPicture || pictureId == null) {
            showPictureDialog(alert)
            return
        }
        lifecycleScope.launch {
            val linkCode = app.settingsRepository.getSettings().receiverLinkCode
            val full = withContext(Dispatchers.IO) {
                AlertMessagingService.fetchFullPicture(this@ReceiverActivity, linkCode, pictureId)
            }
            val updated = if (full != null) app.receivedAlerts.setFullPicture(alert.id, full) else null
            showPictureDialog(updated ?: alert)
        }
    }

    private fun showPictureDialog(alert: ReceivedAlert) {
        val file = app.receivedAlerts.pictureOf(alert) ?: return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = getString(R.string.cd_alert_picture)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(alert.title)
            .setMessage(EventAdapter.formatTimestamp(alert.time))
            .setView(image)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun isLinked(): Boolean = app.settingsRepository.getSettings().receiverLinkCode.isNotEmpty()

    private fun link() {
        val code = binding.etReceiverCode.text?.toString().orEmpty().filterNot { it.isWhitespace() }
        val topic = PushMessage.topicFor(code)
        if (topic == null) {
            binding.tilReceiverCode.error = getString(R.string.error_receiver_code)
            return
        }
        binding.tilReceiverCode.error = null
        if (FirebaseApp.getApps(this).isEmpty()) {
            showStatus(getString(R.string.push_unavailable), isError = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnReceiverLink.isEnabled = false
        showStatus(getString(R.string.receiver_status_linking), isError = false)
        FirebaseMessaging.getInstance().subscribeToTopic(topic).addOnCompleteListener { task ->
            binding.btnReceiverLink.isEnabled = true
            if (task.isSuccessful) {
                val cur = app.settingsRepository.getSettings()
                app.settingsRepository.updateSettings(
                    cur.copy(deviceRole = SecuritySettings.ROLE_RECEIVER, receiverLinkCode = code)
                )
                renderLinked()
            } else {
                showStatus(
                    getString(R.string.receiver_status_failed, task.exception?.message.orEmpty()), isError = true
                )
            }
        }
    }

    private fun unlink() {
        val cur = app.settingsRepository.getSettings()
        PushMessage.topicFor(cur.receiverLinkCode)?.let { topic ->
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            }
        }
        // Clearing the stored code is what stops notifications being shown, even if the
        // unsubscribe request hasn't reached Google yet.
        app.settingsRepository.updateSettings(cur.copy(receiverLinkCode = ""))
        renderLinked()
    }

    private fun renderLinked() {
        val linked = isLinked()
        binding.btnReceiverLink.setText(
            if (linked) R.string.action_receiver_unlink else R.string.action_receiver_link
        )
        binding.etReceiverCode.isEnabled = !linked
        showStatus(
            getString(if (linked) R.string.receiver_status_linked else R.string.receiver_status_unlinked),
            isError = false,
            isGood = linked
        )
    }

    private fun showStatus(message: String, isError: Boolean, isGood: Boolean = false) {
        binding.tvReceiverStatus.text = message
        binding.tvReceiverStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    isError -> R.color.status_disarmed
                    isGood -> R.color.status_armed
                    else -> R.color.on_surface
                }
            )
        )
    }

    private fun updateNotificationWarning() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        binding.tvReceiverNotificationsOff.visibility = if (allowed) View.GONE else View.VISIBLE
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
        unlink()
        val cur = app.settingsRepository.getSettings()
        app.settingsRepository.updateSettings(cur.copy(deviceRole = SecuritySettings.ROLE_CAMERA))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
