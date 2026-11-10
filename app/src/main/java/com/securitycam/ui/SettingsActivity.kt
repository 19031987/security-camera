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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.securitycam.core.alerting.AlertPolicy
import com.securitycam.core.alerting.ModeSchedule
import com.google.firebase.FirebaseApp
import com.securitycam.core.push.FirestorePictures
import com.securitycam.core.push.PushMessage
import com.securitycam.core.push.ServiceAccount
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

    /** Picks the Firebase key file the owner copied onto this phone. */
    private val pushKeyPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadPushKey(uri)
    }

    /** Google's own consent screen, shown the first time Drive access is granted. */
    private val driveConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authorization = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
            onDriveAuthorized(authorization)
        } catch (e: Exception) {
            showDriveStatus(getString(R.string.drive_status_failed, e.message.orEmpty()), isError = true)
            setDriveEnabled(false)
        }
    }

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
        binding.switchSmsPicture.isChecked = settings.smsSendPicture
        binding.switchClips.isChecked = settings.recordClips

        // Mode schedule
        binding.switchSchedule.isChecked = settings.scheduleEnabled
        scheduleDayChips().forEachIndexed { index, chip ->
            chip.isChecked = (settings.scheduleAwayDays and (1 shl index)) != 0
        }
        renderSchedule()

        // Phone notifications
        val firebaseReady = FirebaseApp.getApps(this).isNotEmpty()
        binding.tvPushUnavailable.visibility = if (firebaseReady) View.GONE else View.VISIBLE
        binding.groupPush.visibility = if (firebaseReady) View.VISIBLE else View.GONE
        binding.switchPushArrivals.isChecked = settings.pushNotifyArrivals
        binding.switchPushFull.isChecked = settings.pushFullPictures
        binding.groupPushFull.visibility = if (settings.pushFullPictures) View.VISIBLE else View.GONE
        renderPushState()
        binding.switchNotifyPerson.isChecked = settings.notifyPerson
        binding.switchNotifyAnimal.isChecked = settings.notifyAnimal
        binding.switchNotifyVehicle.isChecked = settings.notifyVehicle
        binding.switchNotifyOther.isChecked = settings.notifyOther
        binding.switchDrive.isChecked = settings.driveEnabled
        binding.btnDriveConnect.visibility = if (settings.driveEnabled) View.GONE else View.VISIBLE
        showDriveStatus(
            getString(if (settings.driveEnabled) R.string.drive_status_connected else R.string.drive_status_off),
            isError = false
        )

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

        // What to notify about
        val saveNotifyChoices = { _: android.widget.CompoundButton, _: Boolean ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(
                cur.copy(
                    notifyPerson = binding.switchNotifyPerson.isChecked,
                    notifyAnimal = binding.switchNotifyAnimal.isChecked,
                    notifyVehicle = binding.switchNotifyVehicle.isChecked,
                    notifyOther = binding.switchNotifyOther.isChecked
                )
            )
        }
        binding.switchNotifyPerson.setOnCheckedChangeListener(saveNotifyChoices)
        binding.switchNotifyAnimal.setOnCheckedChangeListener(saveNotifyChoices)
        binding.switchNotifyVehicle.setOnCheckedChangeListener(saveNotifyChoices)
        binding.switchNotifyOther.setOnCheckedChangeListener(saveNotifyChoices)

        // Phone notifications
        binding.switchPush.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            val cur = app.settingsRepository.getSettings()
            // Can only be on once a key file is loaded; otherwise there is nothing to send with.
            val canEnable = ServiceAccount.parse(cur.pushServiceAccountJson) != null
            app.settingsRepository.updateSettings(cur.copy(pushEnabled = isChecked && canEnable))
            renderPushState()
        }
        binding.switchPushArrivals.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(pushNotifyArrivals = isChecked))
        }
        binding.switchPushFull.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            val cur = app.settingsRepository.getSettings()
            // Storing pictures uses the same key file as sending notifications.
            if (isChecked && ServiceAccount.parse(cur.pushServiceAccountJson) == null) {
                button.isChecked = false
                Toast.makeText(this, R.string.push_full_needs_key, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            app.settingsRepository.updateSettings(cur.copy(pushFullPictures = isChecked))
            binding.groupPushFull.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnCopyRules.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Firestore rules", FirestorePictures.RULES)
            )
            Toast.makeText(this, R.string.push_rules_copied, Toast.LENGTH_LONG).show()
        }
        binding.btnLoadPushKey.setOnClickListener {
            pushKeyPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
        binding.btnRemovePushKey.setOnClickListener {
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(pushServiceAccountJson = "", pushEnabled = false))
            renderPushState()
        }
        binding.btnSharePushCode.setOnClickListener {
            val code = ensurePushLinkCode()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.push_share_text, code))
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share_code)))
        }
        binding.btnNewPushCode.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.push_new_code_title)
                .setMessage(R.string.push_new_code_message)
                .setPositiveButton(R.string.action_new_code) { _, _ ->
                    val cur = app.settingsRepository.getSettings()
                    app.settingsRepository.updateSettings(cur.copy(pushLinkCode = PushMessage.newLinkCode()))
                    renderPushState()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        // Mode schedule
        binding.switchSchedule.setOnCheckedChangeListener { button, _ ->
            if (button.isPressed) saveSchedule()
        }
        scheduleDayChips().forEach { chip ->
            chip.setOnCheckedChangeListener { button, _ -> if (button.isPressed) saveSchedule() }
        }
        binding.btnScheduleStart.setOnClickListener { pickScheduleTime(isStart = true) }
        binding.btnScheduleEnd.setOnClickListener { pickScheduleTime(isStart = false) }

        // Video clips
        binding.switchClips.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(recordClips = isChecked))
        }

        // Picture messages
        binding.switchSmsPicture.setOnCheckedChangeListener { _, isChecked ->
            val cur = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(cur.copy(smsSendPicture = isChecked))
        }

        // Google Drive: switching on starts the Google sign-in; it stays on only if that succeeds
        binding.switchDrive.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (isChecked) connectDrive() else {
                setDriveEnabled(false)
                showDriveStatus(getString(R.string.drive_status_off), isError = false)
            }
        }
        binding.btnDriveConnect.setOnClickListener { connectDrive() }

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

    /** The camera's link code, created the first time it is needed. */
    private fun ensurePushLinkCode(): String {
        val cur = app.settingsRepository.getSettings()
        if (PushMessage.topicFor(cur.pushLinkCode) != null) return cur.pushLinkCode
        val code = PushMessage.newLinkCode()
        app.settingsRepository.updateSettings(cur.copy(pushLinkCode = code))
        return code
    }

    private fun renderPushState(statusOverride: String? = null, isError: Boolean = false) {
        if (FirebaseApp.getApps(this).isEmpty()) return
        val settings = app.settingsRepository.getSettings()
        val account = ServiceAccount.parse(settings.pushServiceAccountJson)

        binding.switchPush.isChecked = settings.pushEnabled && account != null
        binding.btnRemovePushKey.visibility = if (account != null) View.VISIBLE else View.GONE
        binding.tvPushKeyStatus.text = statusOverride ?: if (account != null) {
            getString(R.string.push_key_loaded, account.projectId)
        } else {
            getString(R.string.push_key_missing)
        }
        binding.tvPushKeyStatus.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, if (isError) R.color.status_disarmed else R.color.on_surface_muted
            )
        )
        // Shown in groups of four so it is easier to read out or type.
        binding.tvPushLinkCode.text = ensurePushLinkCode().chunked(4).joinToString(" ")
    }

    private fun loadPushKey(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                // A real key file is about 2 KB; refuse anything implausibly large.
                val bytes = stream.readBytes()
                if (bytes.size > 64 * 1024) null else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
        if (text == null || ServiceAccount.parse(text) == null) {
            renderPushState(getString(R.string.push_key_invalid), isError = true)
            return
        }

        val cur = app.settingsRepository.getSettings()
        app.settingsRepository.updateSettings(cur.copy(pushServiceAccountJson = text))
        renderPushState(getString(R.string.push_key_checking))
        lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { app.fcmSender.checkKey() }
            if (check.isSuccess) {
                val latest = app.settingsRepository.getSettings()
                app.settingsRepository.updateSettings(latest.copy(pushEnabled = true))
                renderPushState()
            } else {
                renderPushState(getString(R.string.push_key_rejected, check.errorMessage.orEmpty()), isError = true)
            }
        }
    }

    /** Monday first, matching the bit order used by [ModeSchedule]. */
    private fun scheduleDayChips() = listOf(
        binding.chipDayMon, binding.chipDayTue, binding.chipDayWed, binding.chipDayThu,
        binding.chipDayFri, binding.chipDaySat, binding.chipDaySun
    )

    private fun pickScheduleTime(isStart: Boolean) {
        val cur = app.settingsRepository.getSettings()
        val minutes = if (isStart) cur.scheduleAwayStartMinutes else cur.scheduleAwayEndMinutes
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(minutes / 60)
            .setMinute(minutes % 60)
            .setTitleText(if (isStart) R.string.schedule_away_from else R.string.schedule_away_until)
            .build()
        picker.addOnPositiveButtonClickListener {
            val chosen = picker.hour * 60 + picker.minute
            val latest = app.settingsRepository.getSettings()
            app.settingsRepository.updateSettings(
                if (isStart) latest.copy(scheduleAwayStartMinutes = chosen)
                else latest.copy(scheduleAwayEndMinutes = chosen)
            )
            saveSchedule()
        }
        picker.show(supportFragmentManager, "schedule_time")
    }

    /** Stores the days and the on/off switch. A timetable that can't work is kept switched off. */
    private fun saveSchedule() {
        var days = 0
        scheduleDayChips().forEachIndexed { index, chip -> if (chip.isChecked) days = days or (1 shl index) }
        val cur = app.settingsRepository.getSettings()
        val schedule = ModeSchedule(cur.scheduleAwayStartMinutes, cur.scheduleAwayEndMinutes, days)
        app.settingsRepository.updateSettings(
            cur.copy(scheduleAwayDays = days, scheduleEnabled = binding.switchSchedule.isChecked && schedule.isValid)
        )
        renderSchedule()
    }

    private fun renderSchedule() {
        val settings = app.settingsRepository.getSettings()
        val schedule = ModeSchedule(
            settings.scheduleAwayStartMinutes, settings.scheduleAwayEndMinutes, settings.scheduleAwayDays
        )
        binding.btnScheduleStart.text = ModeSchedule.formatTime(settings.scheduleAwayStartMinutes)
        binding.btnScheduleEnd.text = ModeSchedule.formatTime(settings.scheduleAwayEndMinutes)
        if (binding.switchSchedule.isChecked != settings.scheduleEnabled) {
            binding.switchSchedule.isChecked = settings.scheduleEnabled
        }

        val problem = when {
            settings.scheduleAwayStartMinutes == settings.scheduleAwayEndMinutes ->
                getString(R.string.schedule_error_same_time)
            (settings.scheduleAwayDays and ModeSchedule.ALL_DAYS) == 0 -> getString(R.string.schedule_error_no_days)
            else -> null
        }
        binding.tvScheduleStatus.text = when {
            problem != null -> problem
            settings.scheduleEnabled -> {
                val now = getString(
                    R.string.schedule_now,
                    getString(
                        if (schedule.modeAt(System.currentTimeMillis()) == AlertPolicy.MODE_HOME) R.string.mode_home
                        else R.string.mode_away
                    )
                )
                if (settings.scheduleAwayEndMinutes < settings.scheduleAwayStartMinutes) {
                    now + " " + getString(R.string.schedule_overnight_note)
                } else {
                    now
                }
            }
            settings.scheduleAwayEndMinutes < settings.scheduleAwayStartMinutes ->
                getString(R.string.schedule_overnight_note)
            else -> ""
        }
        binding.tvScheduleStatus.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, if (problem != null) R.color.status_disarmed else R.color.on_surface
            )
        )
    }

    private fun connectDrive() {
        showDriveStatus(getString(R.string.drive_status_checking), isError = false)
        Identity.getAuthorizationClient(this)
            .authorize(app.driveAuth.authorizationRequest())
            .addOnSuccessListener { authorization -> onDriveAuthorized(authorization) }
            .addOnFailureListener { e ->
                showDriveStatus(getString(R.string.drive_status_failed, e.message.orEmpty()), isError = true)
                setDriveEnabled(false)
            }
    }

    private fun onDriveAuthorized(authorization: AuthorizationResult) {
        val pendingIntent = authorization.pendingIntent
        if (authorization.hasResolution() && pendingIntent != null) {
            // The owner still has to choose an account or accept on Google's screen.
            driveConsent.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            return
        }
        // Permission is granted. Make sure Drive itself answers before reporting success.
        lifecycleScope.launch {
            val check = app.driveUploader.checkConnection()
            if (check.isSuccess) {
                setDriveEnabled(true)
                showDriveStatus(getString(R.string.drive_status_connected), isError = false)
            } else {
                setDriveEnabled(false)
                showDriveStatus(getString(R.string.drive_status_failed, check.errorMessage.orEmpty()), isError = true)
            }
        }
    }

    private fun setDriveEnabled(enabled: Boolean) {
        val cur = app.settingsRepository.getSettings()
        if (cur.driveEnabled != enabled) {
            app.settingsRepository.updateSettings(cur.copy(driveEnabled = enabled))
        }
        if (binding.switchDrive.isChecked != enabled) {
            binding.switchDrive.isChecked = enabled
        }
        binding.btnDriveConnect.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun showDriveStatus(message: String, isError: Boolean) {
        binding.tvDriveStatus.text = message
        binding.tvDriveStatus.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, if (isError) R.color.status_disarmed else R.color.on_surface
            )
        )
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

            binding.tvTestAlertResult.text = listOfNotNull(pushInfo, smsInfo, emailInfo).joinToString("\n")
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
