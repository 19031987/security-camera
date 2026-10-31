package com.securitycam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.config.RecipientValidator
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.surveillance.MotionConfig
import com.securitycam.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings & Diagnostics Management Activity.
 * Provides recipient chip editors (E.164 phone & RFC 5322 email), sensitivity and cooldown sliders,
 * low-light night mode toggle, SMTP credential configuration, and an interactive "Send Test Alert" tool.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app by lazy { SecurityCamApp.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadCurrentSettings()
        setupListeners()
    }

    override fun onPause() {
        super.onPause()
        persistTextAndSmtpSettings()
    }

    private fun setupToolbar() {
        binding.toolbarSettings.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadCurrentSettings() {
        val settings = app.settingsRepository.getSettings()

        // 1. Sensitivity
        binding.sliderSensitivity.value = settings.sensitivity.toFloat()
        binding.tvSensitivityValue.text = "${settings.sensitivity}%"

        // 2. Cooldown
        binding.sliderCooldown.value = settings.cooldownSeconds.toFloat()
        binding.tvCooldownValue.text = "${settings.cooldownSeconds}s"

        // 3. Low-Light
        binding.switchLowLight.isChecked = settings.lowLightCompensation

        // 4. SMS Settings
        binding.switchSmsEnabled.isChecked = settings.smsEnabled
        binding.chipGroupPhones.removeAllViews()
        for (phone in settings.phoneNumbers) {
            addPhoneChip(phone)
        }
        binding.etWebhookUrl.setText(settings.webhookUrl ?: "")

        // 5. Email Settings
        binding.switchEmailEnabled.isChecked = settings.emailEnabled
        binding.chipGroupEmails.removeAllViews()
        for (email in settings.emailAddresses) {
            addEmailChip(email)
        }
        binding.etSmtpHost.setText(settings.smtpHost)
        binding.etSmtpPort.setText(settings.smtpPort.toString())
        binding.etSmtpUsername.setText(settings.smtpUsername)
        binding.etSmtpPassword.setText(settings.smtpPassword)
        binding.etSmtpSenderEmail.setText(settings.smtpSenderEmail)
        binding.switchSmtpTls.isChecked = settings.smtpUseTls
        binding.switchSmtpSsl.isChecked = settings.smtpUseSsl
    }

    private fun setupListeners() {
        // Sensitivity Slider
        binding.sliderSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sensitivity = value.toInt()
                binding.tvSensitivityValue.text = "$sensitivity%"
                app.settingsRepository.setSensitivity(sensitivity)
                app.motionDetector.configure(
                    MotionConfig(
                        sensitivity = sensitivity,
                        lowLightCompensation = binding.switchLowLight.isChecked
                    )
                )
            }
        }

        // Cooldown Slider
        binding.sliderCooldown.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val seconds = value.toInt()
                binding.tvCooldownValue.text = "${seconds}s"
                app.settingsRepository.setCooldownSeconds(seconds)
                app.cooldownManager.cooldownSeconds = seconds
            }
        }

        // Low-Light Switch
        binding.switchLowLight.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(lowLightCompensation = isChecked))
            app.motionDetector.configure(
                MotionConfig(
                    sensitivity = cur.sensitivity,
                    lowLightCompensation = isChecked
                )
            )
        }

        // SMS Enabled Switch
        binding.switchSmsEnabled.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(smsEnabled = isChecked))
        }

        // Add Phone Number
        binding.btnAddPhone.setOnClickListener {
            val phone = binding.etPhoneInput.text?.toString()?.trim().orEmpty()
            if (RecipientValidator.isValidE164(phone)) {
                binding.tilPhoneInput.error = null
                val currentPhones = app.settingsRepository.getPhoneNumbers().toMutableList()
                if (!currentPhones.contains(phone)) {
                    currentPhones.add(phone)
                    app.settingsRepository.setPhoneNumbers(currentPhones)
                    addPhoneChip(phone)
                }
                binding.etPhoneInput.text?.clear()
            } else {
                binding.tilPhoneInput.error = "Invalid E.164 phone format (e.g. +1234567890)"
            }
        }

        // Email Enabled Switch
        binding.switchEmailEnabled.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(emailEnabled = isChecked))
        }

        // Add Email Address
        binding.btnAddEmail.setOnClickListener {
            val email = binding.etEmailInput.text?.toString()?.trim().orEmpty()
            if (RecipientValidator.isValidEmail(email)) {
                binding.tilEmailInput.error = null
                val currentEmails = app.settingsRepository.getEmailAddresses().toMutableList()
                if (!currentEmails.contains(email)) {
                    currentEmails.add(email)
                    app.settingsRepository.setEmailAddresses(currentEmails)
                    addEmailChip(email)
                }
                binding.etEmailInput.text?.clear()
            } else {
                binding.tilEmailInput.error = "Invalid RFC 5322 email format"
            }
        }

        // Interactive Send Test Alert
        binding.btnSendTestAlert.setOnClickListener {
            persistTextAndSmtpSettings()
            sendInteractiveTestAlert()
        }

        // Battery Optimization Whitelist Request
        binding.btnBatteryWhitelist.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun addPhoneChip(phone: String) {
        val chip = Chip(this).apply {
            text = phone
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                binding.chipGroupPhones.removeView(this)
                val currentPhones = app.settingsRepository.getPhoneNumbers().toMutableList()
                currentPhones.remove(phone)
                app.settingsRepository.setPhoneNumbers(currentPhones)
            }
        }
        binding.chipGroupPhones.addView(chip)
    }

    private fun addEmailChip(email: String) {
        val chip = Chip(this).apply {
            text = email
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                binding.chipGroupEmails.removeView(this)
                val currentEmails = app.settingsRepository.getEmailAddresses().toMutableList()
                currentEmails.remove(email)
                app.settingsRepository.setEmailAddresses(currentEmails)
            }
        }
        binding.chipGroupEmails.addView(chip)
    }

    private fun persistTextAndSmtpSettings() {
        try {
            val cur = app.settingsRepository.getSettings()
            val webhook = binding.etWebhookUrl.text?.toString()?.trim().let { if (it.isNullOrBlank()) null else it }
            val host = binding.etSmtpHost.text?.toString()?.trim().orEmpty()
            val port = binding.etSmtpPort.text?.toString()?.trim()?.toIntOrNull() ?: 587
            val username = binding.etSmtpUsername.text?.toString()?.trim().orEmpty()
            val password = binding.etSmtpPassword.text?.toString().orEmpty()
            val sender = binding.etSmtpSenderEmail.text?.toString()?.trim().orEmpty()

            val updated = cur.copy(
                webhookUrl = webhook,
                smtpHost = host,
                smtpPort = port,
                smtpUsername = username,
                smtpPassword = password,
                smtpSenderEmail = sender,
                smtpUseTls = binding.switchSmtpTls.isChecked,
                smtpUseSsl = binding.switchSmtpSsl.isChecked
            )
            app.settingsRepository.updateSettings(updated)
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Error saving settings: ${e.message}", e)
        }
    }

    private fun sendInteractiveTestAlert() {
        binding.progressTestAlert.visibility = View.VISIBLE
        binding.btnSendTestAlert.isEnabled = false

        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                app.alertCoordinator.sendTestAlert()
            }

            binding.progressTestAlert.visibility = View.GONE
            binding.btnSendTestAlert.isEnabled = true

            val smsInfo = when {
                summary.smsResult == null -> "SMS: Not configured or no recipients."
                summary.smsResult.isSuccess -> "SMS: Successfully delivered to ${summary.smsResult.deliveredCount} recipient(s)."
                else -> "SMS: Failed — ${summary.smsResult.errorMessage}"
            }

            val emailInfo = when {
                summary.emailResult == null -> "Email: Not configured or no recipients."
                summary.emailResult.isSuccess -> "Email: Successfully delivered to ${summary.emailResult.deliveredCount} recipient(s)."
                else -> "Email: Failed — ${summary.emailResult.errorMessage}"
            }

            val isAnySuccess = (summary.smsResult?.isSuccess == true) || (summary.emailResult?.isSuccess == true)
            val title = if (isAnySuccess) "✅ Test Alert Dispatched" else "⚠️ Test Alert Results"

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(title)
                .setMessage("$smsInfo\n\n$emailInfo")
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(
                this,
                "Security Camera is already exempted from battery optimization.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to request battery exemption: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
