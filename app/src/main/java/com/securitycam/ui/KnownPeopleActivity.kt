package com.securitycam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.recognition.KnownPerson
import com.securitycam.databinding.ActivityKnownPeopleBinding
import com.securitycam.databinding.ItemKnownPersonBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enrolment of household members for known-people recognition.
 * A face sample comes either from what the camera sees right now or from a chosen photo.
 */
class KnownPeopleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKnownPeopleBinding
    private val app by lazy { SecurityCamApp.getInstance() }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            enrol { decodePhoto(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityKnownPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarPeople.padTopForSystemBars()
        binding.scrollPeople.padBottomForSystemBarsAndKeyboard()
        binding.toolbarPeople.setNavigationOnClickListener { finish() }

        binding.btnEnrolCamera.setOnClickListener {
            if (requireName() != null) {
                enrol { captureFromCamera() }
            }
        }
        binding.btnEnrolPhoto.setOnClickListener {
            if (requireName() != null) {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.knownPeopleStore.people.collect { people -> renderPeople(people) }
            }
        }
    }

    private fun requireName(): String? {
        val name = binding.etPersonName.text?.toString()?.trim().orEmpty()
        return if (name.isEmpty()) {
            binding.tilPersonName.error = getString(R.string.people_error_name)
            null
        } else {
            binding.tilPersonName.error = null
            name
        }
    }

    /** Result of trying to get a picture: the bitmap, or the message explaining why not. */
    private class Picture(val bitmap: Bitmap?, val errorRes: Int = 0)

    private fun enrol(source: suspend () -> Picture) {
        val name = requireName() ?: return
        setBusy(true)
        showStatus(getString(R.string.people_working))

        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                val picture = source()
                val bitmap = picture.bitmap ?: return@withContext getString(picture.errorRes)
                val faces = app.faceAnalyzer.analyze(bitmap)
                when {
                    faces == null -> getString(R.string.people_error_failed)
                    faces.isEmpty() -> getString(R.string.people_error_no_face)
                    faces.size > 1 -> getString(R.string.people_error_many_faces)
                    else -> {
                        val person = app.knownPeopleStore.addSample(name, faces[0])
                        getString(R.string.people_added, person.name)
                    }
                }
            }
            setBusy(false)
            showStatus(message)
        }
    }

    /** Takes the next frame from the running camera service. */
    private fun captureFromCamera(): Picture {
        val hub = app.frameHub
        hub.addViewer()
        try {
            val frame = hub.awaitFrame(0, CAMERA_FRAME_TIMEOUT_MS)
                ?: return Picture(null, R.string.people_error_no_frame)
            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
            return if (bitmap != null) Picture(bitmap) else Picture(null, R.string.people_error_failed)
        } finally {
            hub.removeViewer()
        }
    }

    private fun decodePhoto(uri: Uri): Picture {
        return try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software bitmap so pixels can be read; scaled down so large photos stay cheap.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_PHOTO_SIDE) {
                    decoder.setTargetSampleSize((longest + MAX_PHOTO_SIDE - 1) / MAX_PHOTO_SIDE)
                }
            }
            Picture(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decode the chosen photo: ${e.message}")
            Picture(null, R.string.people_error_failed)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progressEnrol.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnEnrolCamera.isEnabled = !busy
        binding.btnEnrolPhoto.isEnabled = !busy
    }

    private fun showStatus(message: String) {
        binding.tvEnrolStatus.visibility = View.VISIBLE
        binding.tvEnrolStatus.text = message
    }

    private fun renderPeople(people: List<KnownPerson>) {
        binding.tvPeopleEmpty.visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
        binding.listPeople.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (person in people.sortedBy { it.name.lowercase() }) {
            val row = ItemKnownPersonBinding.inflate(inflater, binding.listPeople, false)
            row.tvPersonName.text = person.name
            row.tvPersonSamples.text = resources.getQuantityString(
                R.plurals.people_samples, person.embeddings.size, person.embeddings.size
            )
            row.btnRemovePerson.contentDescription = getString(R.string.action_remove_person, person.name)
            row.btnRemovePerson.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { app.knownPeopleStore.remove(person.id) }
            }
            binding.listPeople.addView(row.root)
        }
    }

    private companion object {
        const val TAG = "KnownPeopleActivity"
        const val CAMERA_FRAME_TIMEOUT_MS = 4_000L
        const val MAX_PHOTO_SIDE = 1600
    }
}
