package com.securitycam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.securitycam.core.alerting.CallMeBotWhatsAppDispatcher
import com.securitycam.core.alerting.NtfyPushDispatcher
import com.securitycam.core.alerting.WhatsAppContact
import com.securitycam.core.remote.ViewerServer
import java.net.Inet4Address
import java.net.NetworkInterface
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarSettings.padTopForSystemBars()
        BottomNav.setup(this, binding.bottomNav.root, R.id.nav_settings)
        keepFocusedFieldAboveKeyboard()

        loadCurrentSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
        updateKnownPeopleSummary()
        updateRemoteViewStatus()
    }

    override fun onPause() {
        super.onPause()
        persistTextAndSmtpSettings()
    }

    /**
     * The screen draws edge to edge, so the keyboard no longer resizes it automatically:
     * pad the scrolling area by however much the keyboard rises above the bottom bar.
     */
    private fun keepFocusedFieldAboveKeyboard() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollSettings) { view, insets ->
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val overlap = (keyboard - binding.bottomNav.root.height).coerceAtLeast(0)
            view.updatePadding(bottom = overlap)
            insets
        }
    }

    private fun loadCurrentSettings() {
        val settings = app.settingsRepository.getSettings()

        // 1. Sensitivity
        binding.sliderSensitivity.value = settings.sensitivity.toFloat()
        binding.tvSensitivityValue.text = getString(R.string.value_percent, settings.sensitivity)

        // 2. Cooldown
        binding.sliderCooldown.value = settings.cooldownSeconds.toFloat()
        binding.tvCooldownValue.text = getString(R.string.value_seconds, settings.cooldownSeconds)

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

        // 6. Night vision, remote viewing, known people
        binding.switchNightVision.isChecked = settings.nightVisionEnabled
        binding.etRemotePassword.setText(settings.remoteViewPassword)
        binding.switchRemoteView.isChecked = settings.remoteViewEnabled &&
            ViewerServer.isAcceptablePassword(settings.remoteViewPassword)
        binding.groupRemoteFields.visibility = View.VISIBLE
        binding.switchKnownPeople.isChecked = settings.knownPeopleEnabled

        // WhatsApp
        binding.switchWhatsApp.isChecked = settings.whatsappEnabled
        binding.groupWhatsAppFields.visibility = if (settings.whatsappEnabled) View.VISIBLE else View.GONE
        binding.chipGroupWhatsApp.removeAllViews()
        for (contact in settings.whatsappContacts) {
            addWhatsAppChip(contact)
        }

        // Phone notifications
        binding.etPushTopic.setText(settings.pushTopic)
        binding.etPushServer.setText(settings.pushServerUrl)
        binding.switchPushKnown.isChecked = settings.pushNotifyKnownPeople
        binding.switchPush.isChecked = settings.pushEnabled && NtfyPushDispatcher.isValidTopic(settings.pushTopic)
        binding.groupPushFields.visibility = if (binding.switchPush.isChecked) View.VISIBLE else View.GONE

        // 7. Only show a channel's fields while that channel is switched on
        binding.groupSmsFields.visibility = if (settings.smsEnabled) View.VISIBLE else View.GONE
        binding.groupEmailFields.visibility = if (settings.emailEnabled) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        // Sensitivity Slider
        binding.sliderSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sensitivity = value.toInt()
                binding.tvSensitivityValue.text = getString(R.string.value_percent, sensitivity)
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
                binding.tvCooldownValue.text = getString(R.string.value_seconds, seconds)
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
            binding.groupSmsFields.visibility = if (isChecked) View.VISIBLE else View.GONE
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
                binding.tilPhoneInput.error = getString(R.string.error_invalid_phone)
            }
        }

        // Email Enabled Switch
        binding.switchEmailEnabled.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(emailEnabled = isChecked))
            binding.groupEmailFields.visibility = if (isChecked) View.VISIBLE else View.GONE
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
                binding.tilEmailInput.error = getString(R.string.error_invalid_email)
            }
        }

        // Night vision
        binding.switchNightVision.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(nightVisionEnabled = isChecked))
        }

        // Remote viewing: can only be switched on once a long enough password is set
        binding.switchRemoteView.setOnCheckedChangeListener { button, isChecked ->
            val password = binding.etRemotePassword.text?.toString().orEmpty()
            if (isChecked && !ViewerServer.isAcceptablePassword(password)) {
                binding.tilRemotePassword.error = getString(R.string.error_remote_password_short)
                button.isChecked = false
                return@setOnCheckedChangeListener
            }
            binding.tilRemotePassword.error = null
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(
                cur.copy(remoteViewEnabled = isChecked, remoteViewPassword = password)
            )
            updateRemoteViewStatus()
        }
        binding.etRemotePassword.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveRemotePassword()
        }
        binding.btnShareRemoteLink.setOnClickListener { shareRemoteLink() }

        // Phone notifications: switching on creates a private topic if there isn't one yet
        binding.switchPush.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !NtfyPushDispatcher.isValidTopic(binding.etPushTopic.text?.toString().orEmpty())) {
                binding.etPushTopic.setText(NtfyPushDispatcher.generateTopic())
            }
            binding.groupPushFields.visibility = if (isChecked) View.VISIBLE else View.GONE
            savePushSettings()
        }
        binding.btnNewPushTopic.setOnClickListener {
            binding.etPushTopic.setText(NtfyPushDispatcher.generateTopic())
            savePushSettings()
        }
        binding.btnSharePushTopic.setOnClickListener {
            if (savePushSettings()) {
                val topic = binding.etPushTopic.text?.toString().orEmpty()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.push_share_text, topic))
                }
                startActivity(Intent.createChooser(intent, getString(R.string.action_share_topic)))
            }
        }
        binding.switchPushKnown.setOnCheckedChangeListener { _, _ -> savePushSettings() }
        binding.etPushTopic.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) savePushSettings() }
        binding.etPushServer.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) savePushSettings() }

        // WhatsApp
        binding.switchWhatsApp.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(whatsappEnabled = isChecked))
            binding.groupWhatsAppFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnAddWhatsApp.setOnClickListener { addWhatsAppContact() }

        // Known people
        binding.switchKnownPeople.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(knownPeopleEnabled = isChecked))
        }
        binding.btnManagePeople.setOnClickListener {
            startActivity(Intent(this, KnownPeopleActivity::class.java))
        }

        // Mail server settings expander
        binding.rowSmtpAdvanced.setOnClickListener {
            val expand = binding.groupSmtpAdvanced.visibility != View.VISIBLE
            binding.groupSmtpAdvanced.visibility = if (expand) View.VISIBLE else View.GONE
            binding.ivSmtpChevron.rotation = if (expand) 180f else 0f
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
        addRecipientChip(binding.chipGroupPhones, phone) {
            val currentPhones = app.settingsRepository.getPhoneNumbers().toMutableList()
            currentPhones.remove(phone)
            app.settingsRepository.setPhoneNumbers(currentPhones)
        }
    }

    private fun addEmailChip(email: String) {
        addRecipientChip(binding.chipGroupEmails, email) {
            val currentEmails = app.settingsRepository.getEmailAddresses().toMutableList()
            currentEmails.remove(email)
            app.settingsRepository.setEmailAddresses(currentEmails)
        }
    }

    private fun addWhatsAppContact() {
        val phone = binding.etWhatsAppPhone.text?.toString()?.trim().orEmpty()
        val key = binding.etWhatsAppKey.text?.toString()?.trim().orEmpty()
        val contact = WhatsAppContact(phone, key)

        val phoneOk = RecipientValidator.isValidE164(phone)
        val keyOk = CallMeBotWhatsAppDispatcher.isValidApiKey(key)
        binding.tilWhatsAppPhone.error = if (phoneOk) null else getString(R.string.error_invalid_phone)
        binding.tilWhatsAppKey.error = if (keyOk) null else getString(R.string.error_whatsapp_key)
        if (!phoneOk || !keyOk || !CallMeBotWhatsAppDispatcher.isValidContact(contact)) return

        val cur = app.settingsRepository.getSettings()
        // Adding a number again replaces its key.
        val contacts = cur.whatsappContacts.filter { it.phone != phone } + contact
        app.settingsRepository.updateSettings(cur.copy(whatsappContacts = contacts))

        binding.chipGroupWhatsApp.removeAllViews()
        contacts.forEach { addWhatsAppChip(it) }
        binding.etWhatsAppPhone.text?.clear()
        binding.etWhatsAppKey.text?.clear()
    }

    private fun addWhatsAppChip(contact: WhatsAppContact) {
        addRecipientChip(binding.chipGroupWhatsApp, contact.phone) {
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(
                cur.copy(whatsappContacts = cur.whatsappContacts.filter { it.phone != contact.phone })
            )
        }
    }

    private fun addRecipientChip(group: ChipGroup, label: String, onRemoved: () -> Unit) {
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_recipient_chip, group, false) as Chip
        chip.text = label
        chip.isCheckable = false
        chip.isCloseIconVisible = true
        chip.setOnCloseIconClickListener {
            group.removeView(chip)
            onRemoved()
        }
        group.addView(chip)
    }

    /**
     * Stores the viewing password. If it has become too short while the live view is on,
     * the live view is switched off rather than left running with a weak password.
     */
    private fun saveRemotePassword() {
        val password = binding.etRemotePassword.text?.toString().orEmpty()
        val cur = app.settingsRepository.getSettings()
        val acceptable = ViewerServer.isAcceptablePassword(password)
        if (cur.remoteViewPassword == password && (acceptable || !cur.remoteViewEnabled)) return

        app.settingsRepository.updateSettings(
            cur.copy(remoteViewPassword = password, remoteViewEnabled = cur.remoteViewEnabled && acceptable)
        )
        if (!acceptable && binding.switchRemoteView.isChecked) {
            binding.switchRemoteView.isChecked = false
        }
        updateRemoteViewStatus()
    }

    /**
     * Stores the phone-notification settings. Returns false, and keeps notifications off,
     * while the topic or server address is not usable.
     */
    private fun savePushSettings(): Boolean {
        val topic = binding.etPushTopic.text?.toString()?.trim().orEmpty()
        val server = NtfyPushDispatcher.normalizeServerUrl(binding.etPushServer.text?.toString().orEmpty())
        val wantsPush = binding.switchPush.isChecked
        val topicOk = NtfyPushDispatcher.isValidTopic(topic)

        binding.tilPushTopic.error = if (wantsPush && !topicOk) getString(R.string.error_push_topic) else null
        binding.tilPushServer.error = if (server == null) getString(R.string.error_push_server) else null

        val cur = app.settingsRepository.getSettings()
        val updated = cur.copy(
            pushEnabled = wantsPush && topicOk && server != null,
            pushTopic = if (topicOk) topic else cur.pushTopic,
            pushServerUrl = server ?: cur.pushServerUrl,
            pushNotifyKnownPeople = binding.switchPushKnown.isChecked
        )
        if (updated != cur) {
            app.settingsRepository.updateSettings(updated)
        }
        return topicOk && server != null
    }

    private fun remoteLinks(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { "http://${it.hostAddress}:${ViewerServer.DEFAULT_PORT}" }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateRemoteViewStatus() {
        val enabled = binding.switchRemoteView.isChecked
        val links = if (enabled) remoteLinks() else emptyList()
        binding.tvRemoteStatus.text = when {
            !enabled -> getString(R.string.remote_status_needs_password)
            links.isEmpty() -> getString(R.string.remote_status_no_network)
            else -> getString(R.string.remote_status_links, links.joinToString("\n"))
        }
        binding.btnShareRemoteLink.visibility = if (links.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shareRemoteLink() {
        val link = remoteLinks().firstOrNull() ?: return
        // The password is deliberately not included; share it separately.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.remote_share_text, link))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share_link)))
    }

    private fun updateKnownPeopleSummary() {
        val count = app.knownPeopleStore.current().size
        binding.tvKnownPeopleSummary.text = if (count == 0) {
            getString(R.string.people_summary_none)
        } else {
            resources.getQuantityString(R.plurals.people_summary_count, count, count)
        }
    }

    private fun persistTextAndSmtpSettings() {
        saveRemotePassword()
        savePushSettings()
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
        binding.tvTestAlertResult.visibility = View.VISIBLE
        binding.tvTestAlertResult.setText(R.string.test_alert_sending)

        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                app.alertCoordinator.sendTestAlert()
            }

            binding.progressTestAlert.visibility = View.GONE
            binding.btnSendTestAlert.isEnabled = true

            val smsInfo = when {
                summary.smsResult == null -> getString(R.string.test_sms_not_configured)
                summary.smsResult.isSuccess ->
                    getString(R.string.test_sms_ok, summary.smsResult.deliveredCount)
                else -> getString(R.string.test_sms_failed, summary.smsResult.errorMessage.orEmpty())
            }

            val emailInfo = when {
                summary.emailResult == null -> getString(R.string.test_email_not_configured)
                summary.emailResult.isSuccess ->
                    getString(R.string.test_email_ok, summary.emailResult.deliveredCount)
                else -> getString(R.string.test_email_failed, summary.emailResult.errorMessage.orEmpty())
            }

            val pushInfo = when {
                summary.pushResult == null -> null
                summary.pushResult.isSuccess -> getString(R.string.test_push_ok)
                else -> getString(R.string.test_push_failed, summary.pushResult.errorMessage.orEmpty())
            }

            val whatsappInfo = when {
                summary.whatsappResult == null -> null
                summary.whatsappResult.isSuccess ->
                    getString(R.string.test_whatsapp_ok, summary.whatsappResult.deliveredCount)
                else -> getString(R.string.test_whatsapp_failed, summary.whatsappResult.errorMessage.orEmpty())
            }

            binding.tvTestAlertResult.text =
                listOfNotNull(pushInfo, whatsappInfo, smsInfo, emailInfo).joinToString("\n")
        }
    }

    private fun isBatteryExempt(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryStatus() {
        val exempt = isBatteryExempt()
        binding.tvBatteryStatus.setText(
            if (exempt) R.string.battery_status_exempt else R.string.battery_status_restricted
        )
        binding.btnBatteryWhitelist.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun requestBatteryOptimizationExemption() {
        if (isBatteryExempt()) {
            updateBatteryStatus()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Unable to request battery exemption: ${e.message}", e)
            Toast.makeText(this, R.string.battery_request_failed, Toast.LENGTH_LONG).show()
        }
    }
}
