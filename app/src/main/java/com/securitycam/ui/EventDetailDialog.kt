package com.securitycam.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import coil.load
import com.securitycam.R
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.DialogEventDetailBinding
import java.io.File

/**
 * Full-screen / modal inspection dialog for a selected motion intrusion event.
 * Displays watermarked screenshot, detailed metadata, and provides native Android share intent.
 */
class EventDetailDialog : DialogFragment() {

    private var _binding: DialogEventDetailBinding? = null
    private val binding get() = _binding!!

    private var event: MotionEventEntity? = null
    var onDeleteListener: ((MotionEventEntity) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_MaterialComponents_Dialog_MinWidth)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEventDetailBinding.inflate(inflater, container, false)
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentEvent = event ?: return

        // 1. Load image via Coil
        val file = File(currentEvent.imagePath)
        binding.ivDetailImage.load(file) {
            crossfade(true)
            placeholder(R.drawable.chip_status_background)
            error(R.drawable.ic_stat_alert)
        }

        // 2. Metadata text
        binding.tvDetailTimestamp.text = "Time: ${EventAdapter.formatTimestamp(currentEvent.timestamp)}"
        binding.tvDetailMotionScore.text = EventAdapter.formatMotionScore(currentEvent.motionScore)
        binding.tvDetailEventId.text = "Event ID: ${currentEvent.id}"

        // 3. Status chips
        binding.chipDetailSms.text = EventAdapter.formatStatusChipText("SMS", currentEvent.smsStatus)
        binding.chipDetailSms.setTextColor(EventAdapter.getStatusChipColor(currentEvent.smsStatus))

        binding.chipDetailEmail.text = EventAdapter.formatStatusChipText("Email", currentEvent.emailStatus)
        binding.chipDetailEmail.setTextColor(EventAdapter.getStatusChipColor(currentEvent.emailStatus))

        // 4. Share button
        binding.btnShareScreenshot.setOnClickListener {
            shareScreenshot(file)
        }

        // 5. Delete button
        binding.btnDeleteEventDetail.setOnClickListener {
            onDeleteListener?.invoke(currentEvent)
            dismiss()
        }

        // 6. Close button
        binding.btnCloseDetail.setOnClickListener {
            dismiss()
        }
    }

    private fun shareScreenshot(file: File) {
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Screenshot file not found", Toast.LENGTH_SHORT).show()
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
                putExtra(Intent.EXTRA_SUBJECT, "Security Camera Intrusion Screenshot")
                putExtra(Intent.EXTRA_TEXT, "Motion detected by Security Camera at ${binding.tvDetailTimestamp.text}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share_image)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to share screenshot: ${e.message}", Toast.LENGTH_LONG).show()
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
