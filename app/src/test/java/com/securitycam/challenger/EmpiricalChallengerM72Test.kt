package com.securitycam.challenger

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.securitycam.SecurityCamApp
import com.securitycam.core.alerting.AlertPayload
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.alerting.EmailDispatcher
import com.securitycam.core.alerting.SmsDispatcher
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecurePreferencesFactory
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.config.SettingsRepository
import com.securitycam.core.config.SharedPreferencesSettingsRepository
import com.securitycam.core.storage.EventRepository
import com.securitycam.core.storage.FileImageStorageManager
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.core.surveillance.NormalizedRect
import com.securitycam.service.BootCompletedReceiver
import com.securitycam.service.SurveillanceService
import com.securitycam.ui.EventHistoryActivity
import com.securitycam.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Empirical Verification Suite for Requirements R3, R4, R5, and R6.
 * Authored by challenger_m7_2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EmpiricalChallengerM72Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @After
    fun tearDown() {
        // Clear preferences
        context.getSharedPreferences("security_cam_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("security_cam_credentials", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // =========================================================================
    // REQUIREMENT R3: Watermark Banner Text Darkening & Luminance
    // =========================================================================

    @Test
    fun testR3_WatermarkBannerDarkensPureWhiteBackgroundAndPreservesCrispWhiteText(): Unit = runBlocking {
        val storageManager = FileImageStorageManager(
            context = context,
            maxStorageBytes = 100L * 1024L * 1024L,
            maxFileCount = 100,
            jpegQuality = 95
        )

        val width = 320
        val height = 240
        // Create solid pure white bitmap (RGB 255, 255, 255)
        val pureWhiteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        val timestamp = 1700000000000L
        val savedFile = storageManager.saveScreenshot(pureWhiteBitmap, timestamp, emptyList())
        assertTrue("Saved screenshot file must exist", savedFile.exists() && savedFile.length() > 0)

        val decoded = BitmapFactory.decodeFile(savedFile.absolutePath)
        assertNotNull("Decoded JPEG must not be null", decoded)

        val bannerHeight = Math.max(24f, Math.min(width, height) * 0.065f).toInt()
        val bannerStartY = height - bannerHeight

        // 1. Verify that the banner background pixels (far right side where there is no text) are darkened
        // At (width - 15, height - bannerHeight / 2), it should not contain text glyphs.
        val bgSamplePixel = decoded.getPixel(width - 15, height - (bannerHeight / 2))
        val bgR = Color.red(bgSamplePixel)
        val bgG = Color.green(bgSamplePixel)
        val bgB = Color.blue(bgSamplePixel)

        assertTrue(
            "Banner background on pure white must be darkened prior to text (expected R,G,B < 180, actual R=$bgR, G=$bgG, B=$bgB)",
            bgR < 180 && bgG < 180 && bgB < 180
        )

        // 2. Verify that white monospace text in the banner area retains high RGB luminance (>220)
        var maxLuminance = 0
        var foundCrispWhitePixel = false
        for (y in bannerStartY until height) {
            for (x in 5 until (width - 20)) {
                val p = decoded.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val luminance = (r + g + b) / 3
                if (luminance > maxLuminance) maxLuminance = luminance

                if (r > 220 && g > 220 && b > 220) {
                    foundCrispWhitePixel = true
                    break
                }
            }
            if (foundCrispWhitePixel) break
        }

        assertTrue(
            "White monospace text pixels must retain high RGB luminance > 220 (max observed=$maxLuminance)",
            foundCrispWhitePixel
        )

        // 3. Contrast ratio verification: White text luminance must be significantly higher than darkened banner background
        val bgLuminance = (bgR + bgG + bgB) / 3
        assertTrue(
            "White text luminance ($maxLuminance) must have high contrast over darkened banner background ($bgLuminance)",
            maxLuminance - bgLuminance > 80
        )
    }

    @Test
    fun testR3_WatermarkRobustnessAcrossMultipleResolutions(): Unit = runBlocking {
        val storageManager = FileImageStorageManager(context = context)
        val testResolutions = listOf(
            Pair(80, 60),    // Tiny thumbnail
            Pair(300, 300),  // Square
            Pair(640, 480),  // VGA
            Pair(1280, 720)  // HD 720p
        )

        for ((w, h) in testResolutions) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(100, 100, 100))
            }
            val boxes = listOf(NormalizedRect(0.1f, 0.1f, 0.4f, 0.4f))
            val file = storageManager.saveScreenshot(bmp, System.currentTimeMillis(), boxes)
            assertTrue("Saved resolution ${w}x${h} must exist", file.exists() && file.length() > 0)

            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull("Decoded bitmap for ${w}x${h} must not be null", decoded)
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
        }
    }

    // =========================================================================
    // REQUIREMENT R4: Auto-restart on Boot (BootCompletedReceiver)
    // =========================================================================

    @Test
    fun testR4_BootCompletedReceiver_startsSurveillanceServiceWhenArmed() {
        val settingsRepo = SharedPreferencesSettingsRepository.create(context)
        settingsRepo.setArmed(true)

        val receiver = BootCompletedReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNotNull("SurveillanceService must be started when system boots and surveillance is armed", startedServiceIntent)
        assertEquals(SurveillanceService::class.java.name, startedServiceIntent.component?.className)
        assertEquals(SurveillanceService.ACTION_START, startedServiceIntent.action)
    }

    @Test
    fun testR4_QuickBootAction_startsSurveillanceServiceWhenArmed() {
        val settingsRepo = SharedPreferencesSettingsRepository.create(context)
        settingsRepo.setArmed(true)

        val receiver = BootCompletedReceiver()
        val intent = Intent(BootCompletedReceiver.ACTION_QUICKBOOT)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNotNull("SurveillanceService must start on QUICKBOOT_POWERON when armed", startedServiceIntent)
        assertEquals(SurveillanceService::class.java.name, startedServiceIntent.component?.className)
        assertEquals(SurveillanceService.ACTION_START, startedServiceIntent.action)
    }

    @Test
    fun testR4_BootCompletedReceiver_doesNotStartServiceWhenDisarmed() {
        val settingsRepo = SharedPreferencesSettingsRepository.create(context)
        settingsRepo.setArmed(false)

        val receiver = BootCompletedReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        val app = context as Application
        val startedServiceIntent = shadowOf(app).nextStartedService
        assertNull("SurveillanceService must NOT start on boot when surveillance is disarmed", startedServiceIntent)
    }

    @Test
    fun testR4_UnrelatedOrNullBroadcastActions_doNotStartServiceEvenIfArmed() {
        val settingsRepo = SharedPreferencesSettingsRepository.create(context)
        settingsRepo.setArmed(true)

        val receiver = BootCompletedReceiver()
        val app = context as Application

        // 1. Null intent
        receiver.onReceive(context, null)
        assertNull("Null intent must be gracefully ignored", shadowOf(app).nextStartedService)

        // 2. Null action intent
        receiver.onReceive(context, Intent())
        assertNull("Intent with null action must be gracefully ignored", shadowOf(app).nextStartedService)

        // 3. Unrelated action (e.g. SCREEN_ON)
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertNull("Unrelated broadcast action must NOT start service", shadowOf(app).nextStartedService)

        // 4. Locked boot completed
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull("LOCKED_BOOT_COMPLETED must not start service unless explicitly supported", shadowOf(app).nextStartedService)
    }

    // =========================================================================
    // REQUIREMENT R5: Sensitive Credentials Protection & XML Backup Exclusions
    // =========================================================================

    @Test
    fun testR5_EncryptedSharedPreferencesCreationAndKeyConfig() {
        val prefs = SecurePreferencesFactory.create(context)
        assertNotNull("SecurePreferencesFactory must return a non-null SharedPreferences instance", prefs)

        // Verify storage roundtrip
        prefs.edit().putString("test_secret", "secret_value_xyz").commit()
        assertEquals("secret_value_xyz", prefs.getString("test_secret", null))
    }

    @Test
    fun testR5_SmtpPasswordStoredInSecurePrefs_andNeverInPlaintext() {
        val normalPrefs = context.getSharedPreferences("test_settings_norm", Context.MODE_PRIVATE)
        val securePrefs = context.getSharedPreferences("test_settings_sec", Context.MODE_PRIVATE)
        normalPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()

        val repo = SharedPreferencesSettingsRepository(normalPrefs, securePrefs)

        val testPassword = "Adversarial#Master%Key&98765"
        val customSettings = SecuritySettings(smtpPassword = testPassword)
        repo.updateSettings(customSettings)

        // 1. Password must be stored in securePrefs
        assertEquals(testPassword, securePrefs.getString("pref_smtp_password", null))

        // 2. Password must NOT be present in normal plaintext prefs
        assertFalse("Plaintext prefs must NOT contain the SMTP password key", normalPrefs.contains("pref_smtp_password"))
        assertNull(normalPrefs.getString("pref_smtp_password", null))

        // 3. getSettings transparently provides the secure password
        val retrieved = repo.getSettings()
        assertEquals(testPassword, retrieved.smtpPassword)
    }

    @Test
    fun testR5_LegacyPlaintextSmtpPasswordAutoMigratedAndPurged() {
        val normalPrefs = context.getSharedPreferences("test_legacy_norm", Context.MODE_PRIVATE)
        val securePrefs = context.getSharedPreferences("test_legacy_sec", Context.MODE_PRIVATE)
        normalPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()

        // Seed plaintext preferences with legacy password
        normalPrefs.edit().putString("pref_smtp_password", "plaintext_legacy_pass").commit()
        assertFalse(securePrefs.contains("pref_smtp_password"))

        val repo = SharedPreferencesSettingsRepository(normalPrefs, securePrefs)

        // Reading settings must trigger automatic migration
        val settings = repo.getSettings()
        assertEquals("plaintext_legacy_pass", settings.smtpPassword)

        // Migration verification: now in securePrefs, removed from normalPrefs
        assertEquals("plaintext_legacy_pass", securePrefs.getString("pref_smtp_password", null))
        assertFalse("Legacy password must be purged from normal prefs", normalPrefs.contains("pref_smtp_password"))
    }

    @Test
    fun testR5_ResetToDefaultsWipesCredentialsFromSecurePrefs() {
        val normalPrefs = context.getSharedPreferences("test_reset_norm", Context.MODE_PRIVATE)
        val securePrefs = context.getSharedPreferences("test_reset_sec", Context.MODE_PRIVATE)
        normalPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()

        val repo = SharedPreferencesSettingsRepository(normalPrefs, securePrefs)
        repo.updateSettings(SecuritySettings(smtpPassword = "transient_secret"))
        assertEquals("transient_secret", securePrefs.getString("pref_smtp_password", null))

        repo.resetToDefaults()

        assertFalse("Secure prefs must be wiped after resetToDefaults", securePrefs.contains("pref_smtp_password"))
        assertEquals("", repo.getSettings().smtpPassword)
    }

    @Test
    fun testR5_XmlBackupAndDataExtractionRulesExcludeCredentialsFile() {
        // 1. Parse res/xml/backup_rules.xml
        val backupRulesFile = File("src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", backupRulesFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = dbFactory.newDocumentBuilder()
        val backupDoc = docBuilder.parse(backupRulesFile)
        val backupExcludes = backupDoc.getElementsByTagName("exclude")

        var credentialsExcludedFromBackup = false
        for (i in 0 until backupExcludes.length) {
            val element = backupExcludes.item(i) as Element
            val domain = element.getAttribute("domain")
            val path = element.getAttribute("path")
            if (domain == "sharedpref" && path == "security_cam_credentials.xml") {
                credentialsExcludedFromBackup = true
                break
            }
        }
        assertTrue("backup_rules.xml must exclude security_cam_credentials.xml", credentialsExcludedFromBackup)

        // 2. Parse res/xml/data_extraction_rules.xml
        val dataExtractionFile = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", dataExtractionFile.exists())

        val extractionDoc = docBuilder.parse(dataExtractionFile)
        val cloudBackupList = extractionDoc.getElementsByTagName("cloud-backup")
        assertTrue("data_extraction_rules.xml must contain cloud-backup", cloudBackupList.length > 0)
        val cloudBackupElement = cloudBackupList.item(0) as Element
        val cloudExcludes = cloudBackupElement.getElementsByTagName("exclude")

        var cloudExcluded = false
        for (i in 0 until cloudExcludes.length) {
            val el = cloudExcludes.item(i) as Element
            if (el.getAttribute("domain") == "sharedpref" && el.getAttribute("path") == "security_cam_credentials.xml") {
                cloudExcluded = true
                break
            }
        }
        assertTrue("data_extraction_rules.xml cloud-backup must exclude security_cam_credentials.xml", cloudExcluded)

        val deviceTransferList = extractionDoc.getElementsByTagName("device-transfer")
        assertTrue("data_extraction_rules.xml must contain device-transfer", deviceTransferList.length > 0)
        val deviceTransferElement = deviceTransferList.item(0) as Element
        val deviceExcludes = deviceTransferElement.getElementsByTagName("exclude")

        var deviceExcluded = false
        for (i in 0 until deviceExcludes.length) {
            val el = deviceExcludes.item(i) as Element
            if (el.getAttribute("domain") == "sharedpref" && el.getAttribute("path") == "security_cam_credentials.xml") {
                deviceExcluded = true
                break
            }
        }
        assertTrue("data_extraction_rules.xml device-transfer must exclude security_cam_credentials.xml", deviceExcluded)
    }

    // =========================================================================
    // REQUIREMENT R6: Local Alert Notification
    // =========================================================================

    private fun createValidJpegFile(name: String): File {
        val file = tempFolder.newFile(name)
        val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    class FakeSettingsRepo(private var settings: SecuritySettings) : SettingsRepository {
        override fun getSettings(): SecuritySettings = settings
        override fun updateSettings(settings: SecuritySettings) { this.settings = settings }
        override fun observeSettings(): Flow<SecuritySettings> = emptyFlow()
        override fun isArmed(): Boolean = settings.isArmed
        override fun setArmed(armed: Boolean) { settings = settings.copy(isArmed = armed) }
        override fun getSensitivity(): Int = settings.sensitivity
        override fun setSensitivity(sensitivity: Int) { settings = settings.copy(sensitivity = sensitivity) }
        override fun getCooldownSeconds(): Int = settings.cooldownSeconds
        override fun setCooldownSeconds(seconds: Int) { settings = settings.copy(cooldownSeconds = seconds) }
        override fun getPhoneNumbers(): List<String> = settings.phoneNumbers
        override fun setPhoneNumbers(numbers: List<String>) { settings = settings.copy(phoneNumbers = numbers) }
        override fun getEmailAddresses(): List<String> = settings.emailAddresses
        override fun setEmailAddresses(emails: List<String>) { settings = settings.copy(emailAddresses = emails) }
        override fun isPatrolModeEnabled(): Boolean = settings.isPatrolModeEnabled
        override fun setPatrolModeEnabled(enabled: Boolean) { settings = settings.copy(isPatrolModeEnabled = enabled) }
        override fun getPatrolIntervalSeconds(): Int = settings.patrolIntervalSeconds
        override fun setPatrolIntervalSeconds(seconds: Int) { settings = settings.copy(patrolIntervalSeconds = seconds) }
        override fun resetToDefaults() { settings = SecuritySettings() }
    }

    class MemoryEventRepo : EventRepository {
        val events = mutableMapOf<String, MotionEventEntity>()
        override suspend fun insertEvent(event: MotionEventEntity): Long {
            events[event.id] = event
            return 1L
        }
        override suspend fun getAllEvents(): List<MotionEventEntity> = events.values.toList()
        override fun observeEvents(): Flow<List<MotionEventEntity>> = emptyFlow()
        override suspend fun deleteEvent(id: String) { events.remove(id) }
        override suspend fun getEventById(id: String): MotionEventEntity? = events[id]
        override suspend fun updateSmsStatus(id: String, status: String) {
            events[id]?.let { events[id] = it.copy(smsStatus = status) }
        }
        override suspend fun updateEmailStatus(id: String, status: String) {
            events[id]?.let { events[id] = it.copy(emailStatus = status) }
        }
    }

    class MockSmsDispatcher : SmsDispatcher {
        var count = 0
        override suspend fun sendSms(phoneNumbers: List<String>, message: String): DispatchResult {
            count++
            return DispatchResult(true, phoneNumbers.size, 0)
        }
    }

    class MockEmailDispatcher : EmailDispatcher {
        var count = 0
        override suspend fun sendEmail(
            emailAddresses: List<String>,
            subject: String,
            bodyHtml: String,
            attachmentFile: File?
        ): DispatchResult {
            count++
            return DispatchResult(true, emailAddresses.size, 0)
        }
    }

    @Test
    fun testR6_LocalAlertNotification_Attributes_Intents_andBigPictureStyle() = runTest(testDispatcher) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        val settingsRepo = FakeSettingsRepo(SecuritySettings(isArmed = true))
        val eventRepo = MemoryEventRepo()
        val cooldown = CooldownManager(30)
        val sms = MockSmsDispatcher()
        val email = MockEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepo,
            eventRepository = eventRepo,
            cooldownManager = cooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        val jpegFile = createValidJpegFile("test_alert_snapshot.jpg")
        val payload = AlertPayload(
            eventId = "evt-empirical-r6",
            timestamp = 50_000L,
            motionScore = 0.92f,
            screenshotFile = jpegFile,
            cameraFacing = "BACK"
        )

        coordinator.onMotionDetected(payload)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals("Exactly 1 local alert notification must be posted", 1, notifications.size)

        val notif = notifications[0]

        // 1. Channel ID
        assertEquals(SecurityCamApp.CHANNEL_ALERTS, notif.channelId)

        // 2. High Priority
        assertEquals(NotificationCompat.PRIORITY_HIGH, notif.priority)

        // 3. Sound and vibration configuration (on API 26+ encapsulated in NotificationChannel)
        val channel = notificationManager.getNotificationChannel(SecurityCamApp.CHANNEL_ALERTS)
        if (channel != null) {
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue("Alert channel must enable vibration", channel.shouldVibrate())
            assertNotNull("Alert channel must have sound URI", channel.sound)
        } else {
            assertEquals(NotificationCompat.DEFAULT_ALL, notif.defaults)
        }

        // 4. Content Intent -> MainActivity
        assertNotNull("Notification must have a contentIntent", notif.contentIntent)
        val shadowContentIntent = shadowOf(notif.contentIntent)
        val savedContentIntent = shadowContentIntent.savedIntent
        assertEquals(MainActivity::class.java.name, savedContentIntent.component?.className)
        assertEquals("evt-empirical-r6", savedContentIntent.getStringExtra("EXTRA_EVENT_ID"))

        // 5. Actions -> "View History" pointing to EventHistoryActivity
        assertNotNull("Notification must have actions", notif.actions)
        assertTrue("Notification must have at least 1 action", notif.actions.isNotEmpty())
        val historyAction = notif.actions.first { it.title == "View History" }
        assertNotNull("History action pendingIntent must not be null", historyAction.actionIntent)
        val shadowHistoryIntent = shadowOf(historyAction.actionIntent)
        val savedHistoryIntent = shadowHistoryIntent.savedIntent
        assertEquals(EventHistoryActivity::class.java.name, savedHistoryIntent.component?.className)
        assertEquals("evt-empirical-r6", savedHistoryIntent.getStringExtra("EXTRA_EVENT_ID"))

        // 6. BigPictureStyle: Extras should contain picture or BigPicture template
        val extras = notif.extras
        assertNotNull(extras)
        val bigPicture = extras.get(NotificationCompat.EXTRA_PICTURE) as? Bitmap
        assertNotNull("BigPictureStyle must attach the decoded screenshot bitmap", bigPicture)
        assertEquals(160, bigPicture?.width)
        assertEquals(120, bigPicture?.height)
    }

    @Test
    fun testR6_LocalAlertNotification_MissingScreenshotGracefulFallback() = runTest(testDispatcher) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        val settingsRepo = FakeSettingsRepo(SecuritySettings(isArmed = true))
        val eventRepo = MemoryEventRepo()
        val cooldown = CooldownManager(30)
        val sms = MockSmsDispatcher()
        val email = MockEmailDispatcher()

        val coordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepo,
            eventRepository = eventRepo,
            cooldownManager = cooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        // Non-existent file
        val missingFile = File(context.cacheDir, "non_existent_${System.currentTimeMillis()}.jpg")
        val payload = AlertPayload(
            eventId = "evt-missing-file",
            timestamp = 60_000L,
            motionScore = 0.85f,
            screenshotFile = missingFile,
            cameraFacing = "FRONT"
        )

        coordinator.onMotionDetected(payload)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals("Notification must still be posted when image file is missing", 1, notifications.size)
        val notif = notifications[0]
        assertEquals(SecurityCamApp.CHANNEL_ALERTS, notif.channelId)

        val extras = notif.extras
        assertNotNull(extras)
        // Should have BigText fallback
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
        assertTrue("BigTextStyle must be used when screenshot bitmap is absent", bigText?.contains("Motion detected") == true)
    }

    @Test
    fun testR6_LocalAlertNotification_SuppressedWhenDisarmedOrCooldownOrDebounce() = runTest(testDispatcher) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        // 1. Disarmed: No notification
        val disarmedRepo = FakeSettingsRepo(SecuritySettings(isArmed = false))
        val eventRepo = MemoryEventRepo()
        val cooldown = CooldownManager(30)
        val sms = MockSmsDispatcher()
        val email = MockEmailDispatcher()

        val coordinator1 = DefaultAlertCoordinator(
            settingsRepository = disarmedRepo,
            eventRepository = eventRepo,
            cooldownManager = cooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        val file1 = createValidJpegFile("disarmed.jpg")
        coordinator1.onMotionDetected(AlertPayload("evt-disarmed", 10_000L, 0.9f, file1))
        assertEquals("No notification when disarmed", 0, shadowOf(notificationManager).allNotifications.size)

        // 2. Cooldown active: No notification
        val armedRepo = FakeSettingsRepo(SecuritySettings(isArmed = true))
        val activeCooldown = CooldownManager(30).apply {
            recordAlertDispatched(10_000L) // in cooldown until 40_000L
        }
        val coordinator2 = DefaultAlertCoordinator(
            settingsRepository = armedRepo,
            eventRepository = eventRepo,
            cooldownManager = activeCooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        val file2 = createValidJpegFile("cooldown.jpg")
        coordinator2.onMotionDetected(AlertPayload("evt-cooldown", 15_000L, 0.9f, file2))
        assertEquals("No notification when in cooldown", 0, shadowOf(notificationManager).allNotifications.size)

        // 3. Burst debounce: consecutive event < 3000ms
        val freshCooldown = CooldownManager(30)
        val coordinator3 = DefaultAlertCoordinator(
            settingsRepository = armedRepo,
            eventRepository = eventRepo,
            cooldownManager = freshCooldown,
            smsDispatcher = sms,
            emailDispatcher = email,
            ioDispatcher = testDispatcher,
            context = context
        )

        val file3 = createValidJpegFile("burst1.jpg")
        coordinator3.onMotionDetected(AlertPayload("evt-burst-1", 100_000L, 0.9f, file3))
        assertEquals("First event posts 1 notification", 1, shadowOf(notificationManager).allNotifications.size)

        val file4 = createValidJpegFile("burst2.jpg")
        coordinator3.onMotionDetected(AlertPayload("evt-burst-2", 101_500L, 0.9f, file4)) // 1500ms later < 3000ms
        assertEquals("Rapid duplicate within burst debounce window does NOT post second notification", 1, shadowOf(notificationManager).allNotifications.size)
    }
}
