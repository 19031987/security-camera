package com.securitycam.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.securitycam.R
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.DialogEventDetailBinding
import java.io.File

/**
 * Bottom sheet for inspecting a selected motion event.
 * Displays the screenshot and metadata, and offers share and delete.
 */
class EventDetailDialog : BottomSheetDialogFragment() {

    private var _binding: DialogEventDetailBinding? = null
    private val binding get() = _binding!!

    private var event: MotionEventEntity? = null
    var onDeleteListener: ((MotionEventEntity) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEventDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentEvent = event
        if (currentEvent == null) {
            // The event isn't kept across process recreation; close rather than show an empty sheet.
            dismissAllowingStateLoss()
            return
        }

        // 1. Load image via Coil
        val file = File(currentEvent.imagePath)
        binding.ivDetailImage.load(file) {
            crossfade(true)
            error(R.drawable.ic_stat_alert)
        }

        // 2. Metadata text
        val timestampText = EventAdapter.formatTimestamp(currentEvent.timestamp)
        binding.tvDetailTimestamp.text = timestampText
        val detected = currentEvent.detectedLabel
        binding.tvDetailMotionScore.text = if (detected.isNullOrBlank()) {
            EventAdapter.formatMotionScore(currentEvent.motionScore)
        } else {
            "$detected · ${EventAdapter.formatMotionScore(currentEvent.motionScore)}"
        }
        binding.tvDetailEventId.text = getString(R.string.event_detail_id, currentEvent.id)

        val recognized = currentEvent.recognizedPerson
        binding.tvDetailRecognized.visibility = if (recognized.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvDetailRecognized.text = getString(R.string.event_recognized, recognized.orEmpty())

        // 3. Status chips
        binding.chipDetailSms.text = EventAdapter.formatStatusChipText("SMS", currentEvent.smsStatus)
        binding.chipDetailSms.setTextColor(EventAdapter.getStatusChipColor(currentEvent.smsStatus))

        binding.chipDetailEmail.text = EventAdapter.formatStatusChipText("Email", currentEvent.emailStatus)
        binding.chipDetailEmail.setTextColor(EventAdapter.getStatusChipColor(currentEvent.emailStatus))

        // Video clip, played in the phone's own video player
        val clipPath = currentEvent.clipPath
        binding.btnPlayClip.visibility = if (clipPath.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.btnPlayClip.setOnClickListener { playClip(clipPath.orEmpty()) }

        // 4. Share button
        binding.btnShareScreenshot.setOnClickListener {
            shareScreenshot(file, timestampText)
        }

        // 5. Delete button
        binding.btnDeleteEventDetail.setOnClickListener {
            onDeleteListener?.invoke(currentEvent)
            dismiss()
        }
    }

    private fun playClip(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.clip_missing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to play clip: ${e.message}")
            Toast.makeText(requireContext(), R.string.clip_no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareScreenshot(file: File, timestampText: String) {
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.share_missing_file, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, timestampText))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share_image)))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to share screenshot: ${e.message}", e)
            Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EventDetailDialog"

        fun newInstance(event: MotionEventEntity): EventDetailDialog {
            return EventDetailDialog().apply {
                this.event = event
            }
        }
    }
}
