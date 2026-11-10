package com.securitycam

import com.securitycam.core.push.FirestoreLive
import com.securitycam.core.push.FirestorePictures
import com.securitycam.core.push.PictureLedger
import com.securitycam.core.push.ReceivedAlertStore
import com.securitycam.core.storage.ClipStore
import com.securitycam.core.push.FcmPushNotifier
import com.securitycam.core.push.FcmSender
import com.securitycam.core.push.ServiceAccount
import com.securitycam.core.alerting.AndroidMmsDispatcher
import com.securitycam.core.alerting.DriveUploader
import com.securitycam.core.alerting.GoogleDriveAuth
import com.securitycam.core.recognition.SceneDetector
import com.securitycam.core.recognition.TfliteSceneDetector
import com.securitycam.core.recognition.FaceAnalyzer
import com.securitycam.core.recognition.KnownPeopleStore
import com.securitycam.core.recognition.MlKitFaceAnalyzer
import com.securitycam.core.remote.FrameHub
import com.securitycam.core.remote.ViewerServer
import com.securitycam.service.SurveillanceService
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securitycam.core.alerting.AlertCoordinator
import com.securitycam.core.alerting.AndroidSmsDispatcher
import com.securitycam.core.alerting.DefaultAlertCoordinator
import com.securitycam.core.alerting.DefaultAndroidSmsTransport
import com.securitycam.core.alerting.EmailDispatcher
import com.securitycam.core.alerting.JakartaEmailDispatcher
import com.securitycam.core.alerting.SmsDispatcher
import com.securitycam.core.config.CooldownManager
import com.securitycam.core.config.SecuritySettings
import com.securitycam.core.config.SettingsRepository
import com.securitycam.core.config.SharedPreferencesSettingsRepository
import com.securitycam.core.storage.AppDatabase
import com.securitycam.core.storage.EventRepository
import com.securitycam.core.storage.FileImageStorageManager
import com.securitycam.core.storage.ImageStorageManager
import com.securitycam.core.storage.RoomEventRepository
import com.securitycam.core.surveillance.AdaptiveBackgroundMotionDetector
import com.securitycam.core.surveillance.MotionConfig
import com.securitycam.core.surveillance.MotionDetector
import java.io.File

/**
 * Main Application class for Android Security Camera.
 * Initializes notification channels, singleton dependency graph, and storage directories.
 */
class SecurityCamApp : Application() {

    lateinit var settingsRepository: SettingsRepository
    lateinit var appDatabase: AppDatabase
    lateinit var eventRepository: EventRepository
    lateinit var imageStorageManager: ImageStorageManager
    lateinit var motionDetector: MotionDetector
    lateinit var cooldownManager: CooldownManager
    lateinit var smsDispatcher: SmsDispatcher
    lateinit var emailDispatcher: EmailDispatcher
    lateinit var alertCoordinator: AlertCoordinator

    /** Newest camera frame for the live web viewer and for face enrolment. */
    val frameHub = FrameHub()
    lateinit var viewerServer: ViewerServer
    lateinit var knownPeopleStore: KnownPeopleStore
    lateinit var faceAnalyzer: FaceAnalyzer
    lateinit var sceneDetector: SceneDetector
    lateinit var driveAuth: GoogleDriveAuth
    lateinit var driveUploader: DriveUploader
    lateinit var fcmSender: FcmSender
    lateinit var clipStore: ClipStore

    /** Receiver role: the notifications this phone has been sent. */
    lateinit var receivedAlerts: ReceivedAlertStore

    /** Full-size alert pictures in the owner's Firebase database. */
    val firestorePictures = FirestorePictures()
    lateinit var pictureLedger: PictureLedger

    /** Live pictures for family phones, through the same database. */
    val firestoreLive = FirestoreLive()

    companion object {
        const val TAG = "SecurityCamApp"

        // Notification Channels
        const val CHANNEL_SURVEILLANCE = "surveillance_channel"
        const val CHANNEL_SURVEILLANCE_ID = CHANNEL_SURVEILLANCE
        const val CHANNEL_ALERTS = "alert_channel"
        const val CHANNEL_ALERTS_ID = CHANNEL_ALERTS

        // Notification Identifiers
        const val NOTIFICATION_ID_SURVEILLANCE = 1001
        const val NOTIFICATION_ID_ALERT_BASE = 2000

        @Volatile
        private var instance: SecurityCamApp? = null

        fun getInstance(): SecurityCamApp {
            return checkNotNull(instance) { "SecurityCamApp is not initialized yet!" }
        }

        fun getAppContext(): Context {
            return getInstance().applicationContext
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        initNotificationChannels()
        initStorageDirectories()
        initDependencyGraph()

        Log.i(TAG, "SecurityCamApp initialized successfully with complete dependency graph.")
    }

    /**
     * Initializes the core application singleton dependency graph.
     */
    fun initDependencyGraph() {
        settingsRepository = SharedPreferencesSettingsRepository.create(this)
        appDatabase = AppDatabase.getInstance(this)
        eventRepository = RoomEventRepository(appDatabase.motionEventDao())
        imageStorageManager = FileImageStorageManager(this)

        val settings: SecuritySettings = settingsRepository.getSettings()
        motionDetector = AdaptiveBackgroundMotionDetector(
            MotionConfig(
                sensitivity = settings.sensitivity,
                lowLightCompensation = settings.lowLightCompensation
            )
        )
        cooldownManager = CooldownManager(settings.cooldownSeconds)

        smsDispatcher = AndroidSmsDispatcher(
            transport = DefaultAndroidSmsTransport(this),
            webhookUrlProvider = { settingsRepository.getSettings().webhookUrl }
        )
        emailDispatcher = JakartaEmailDispatcher(
            settingsProvider = { settingsRepository.getSettings() }
        )
        driveAuth = GoogleDriveAuth(this)
        driveUploader = DriveUploader(
            tokenProvider = { driveAuth.accessTokenOrNull() },
            folderIdStore = object : DriveUploader.FolderIdStore {
                override fun get(): String = settingsRepository.getSettings().driveFolderId
                override fun set(id: String) {
                    settingsRepository.updateSettings(settingsRepository.getSettings().copy(driveFolderId = id))
                }
            }
        )
        sceneDetector = TfliteSceneDetector(this)

        clipStore = ClipStore(File(filesDir, ClipStore.DIRECTORY_NAME))
        receivedAlerts = ReceivedAlertStore(File(filesDir, ReceivedAlertStore.DIRECTORY_NAME))
        fcmSender = FcmSender(
            accountProvider = { ServiceAccount.parse(settingsRepository.getSettings().pushServiceAccountJson) }
        )
        pictureLedger = PictureLedger(File(filesDir, PictureLedger.FILE_NAME))

        alertCoordinator = DefaultAlertCoordinator(
            settingsRepository = settingsRepository,
            eventRepository = eventRepository,
            cooldownManager = cooldownManager,
            smsDispatcher = smsDispatcher,
            emailDispatcher = emailDispatcher,
            context = this,
            pictureDispatcher = AndroidMmsDispatcher(this),
            cloudUploader = driveUploader,
            pushNotifier = FcmPushNotifier(
                fcmSender,
                { settingsRepository.getSettings().pushLinkCode },
                fullPictureUploader = { image, time -> storeFullPicture(image, time) }
            )
        )

        viewerServer = ViewerServer(
            frameHub = frameHub,
            passwordProvider = { settingsRepository.getSettings().remoteViewPassword },
            statusJsonProvider = {
                val state = SurveillanceService.liveState.value
                "{\"running\":${state.isRunning},\"armed\":${state.isArmed}," +
                    "\"nightVision\":${state.isNightVisionActive}}"
            }
        )
        knownPeopleStore = KnownPeopleStore(File(filesDir, KnownPeopleStore.FILE_NAME))
        faceAnalyzer = MlKitFaceAnalyzer(this)
    }

    /**
     * Initializes notification channels required for Android 8.0+ (API 26+).
     * Creates:
     * 1. Surveillance Channel: Low importance, non-intrusive ongoing notification.
     * 2. Alert Channel: High importance, sound, vibration, and red LED alert.
     */
    /**
     * Stores the full-size picture in the owner's database when that is switched on, and removes
     * the oldest stored pictures beyond the ledger's limit. Returns the new picture's id, or null.
     */
    private fun storeFullPicture(image: File, time: Long): String? {
        val settings = settingsRepository.getSettings()
        if (!settings.pushFullPictures) return null
        val jpeg = image.readBytes()
        val (account, token) = fcmSender.accountAndToken()
        val result = firestorePictures.upload(account.projectId, token, settings.pushLinkCode, jpeg, time)
        val id = result.pictureId
        if (id == null) {
            Log.w(TAG, "Full-size picture not stored: ${result.error}")
            return null
        }
        val expired = pictureLedger.add(settings.pushLinkCode, id)
        val notDeleted = expired.filterNot {
            firestorePictures.delete(account.projectId, token, it.linkCode, it.pictureId)
        }
        pictureLedger.restore(notDeleted)
        return id
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Ongoing Surveillance Background Channel
            val surveillanceChannel = NotificationChannel(
                CHANNEL_SURVEILLANCE,
                getString(R.string.channel_surveillance_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_surveillance_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // 2. High-Priority Motion Alerts Channel
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alert_desc)
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .build()
                setSound(soundUri, audioAttributes)
            }

            notificationManager.createNotificationChannels(listOf(surveillanceChannel, alertChannel))
            Log.i(TAG, "Notification channels registered: [$CHANNEL_SURVEILLANCE, $CHANNEL_ALERTS]")
        }
    }

    /**
     * Pre-creates required storage directories on application startup.
     */
    private fun initStorageDirectories() {
        try {
            // Internal storage
            val internalEventsDir = File(filesDir, "events")
            if (!internalEventsDir.exists()) internalEventsDir.mkdirs()

            // Internal cache for in-flight snapshots
            val cacheSnapshotsDir = File(cacheDir, "snapshots")
            if (!cacheSnapshotsDir.exists()) cacheSnapshotsDir.mkdirs()

            // External Pictures/events directory
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { picturesDir ->
                val externalEventsDir = File(picturesDir, "events")
                if (!externalEventsDir.exists()) externalEventsDir.mkdirs()
            }

            Log.i(TAG, "Storage directories initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing storage directories: ${e.message}", e)
        }
    }
}
