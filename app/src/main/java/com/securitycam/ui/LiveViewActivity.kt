package com.securitycam.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.FirebaseApp
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.push.FirestoreLive
import com.securitycam.databinding.ActivityLiveViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live pictures from the camera on a family phone. While this screen is open it keeps telling
 * the camera "someone is watching" and shows each new picture as it arrives.
 */
class LiveViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveViewBinding
    private val app by lazy { SecurityCamApp.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLiveViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbarLive.padTopForSystemBars()
        binding.toolbarLive.setNavigationOnClickListener { finish() }
        // Keep the screen on while watching.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val linkCode = app.settingsRepository.getSettings().receiverLinkCode
        val options = if (FirebaseApp.getApps(this).isEmpty()) null else FirebaseApp.getInstance().options
        val projectId = options?.projectId
        if (linkCode.isEmpty() || options == null || projectId == null) {
            showStatus(getString(R.string.live_unavailable), isProblem = true)
            return
        }

        // Watching only happens while the screen is visible; leaving it tells the camera to stop.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watch(projectId, options.apiKey, linkCode)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        app.firestoreLive.writeRequest(projectId, options.apiKey, linkCode, FirestoreLive.NONCE_STOP)
                    }
                }
            }
        }
    }

    private suspend fun watch(projectId: String, apiKey: String, linkCode: String) {
        val live = app.firestoreLive
        var lastSequence = Long.MIN_VALUE
        var lastRenewal = 0L
        var lastNewPictureAt = System.currentTimeMillis()
        var seenAny = false
        showStatus(getString(R.string.live_connecting), isProblem = false)

        while (lifecycleScope.isActive) {
            val now = System.currentTimeMillis()
            if (now - lastRenewal >= RENEW_EVERY_MS) {
                val accepted = withContext(Dispatchers.IO) {
                    live.writeRequest(projectId, apiKey, linkCode, FirestoreLive.newNonce())
                }
                if (accepted) {
                    lastRenewal = now
                } else if (!seenAny) {
                    showStatus(getString(R.string.live_request_failed), isProblem = true)
                }
            }

            val frame = withContext(Dispatchers.IO) { live.readFrame(projectId, apiKey, linkCode) }
            // The very first read may return a picture left from an earlier session: remember
            // its number but don't present it as live.
            if (frame != null && lastSequence == Long.MIN_VALUE) {
                lastSequence = frame.sequence
            } else if (frame != null && frame.sequence != lastSequence) {
                lastSequence = frame.sequence
                val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                if (bitmap != null) {
                    binding.ivLive.setImageBitmap(bitmap)
                    lastNewPictureAt = System.currentTimeMillis()
                    seenAny = true
                    showStatus(getString(R.string.live_watching), isProblem = false)
                }
            } else if (frame == null && lastSequence == Long.MIN_VALUE) {
                // Nothing stored yet: any picture that appears from now on is new.
                lastSequence = Long.MIN_VALUE + 1
            }

            if (System.currentTimeMillis() - lastNewPictureAt > STALLED_AFTER_MS) {
                showStatus(getString(R.string.live_stalled), isProblem = true)
            }
            delay(POLL_EVERY_MS)
        }
    }

    private fun showStatus(message: String, isProblem: Boolean) {
        binding.tvLiveStatus.text = message
        binding.tvLiveStatus.setTextColor(
            ContextCompat.getColor(this, if (isProblem) R.color.status_disarmed else R.color.on_surface)
        )
    }

    private companion object {
        const val POLL_EVERY_MS = 1_000L

        /** Well inside the camera's 60-second grant, so one lost renewal doesn't end the stream. */
        const val RENEW_EVERY_MS = 20_000L
        const val STALLED_AFTER_MS = 20_000L
    }
}
