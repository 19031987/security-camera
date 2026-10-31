package com.securitycam.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.securitycam.R
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.ItemEventBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the Motion Event History gallery.
 * Employs Coil for downsampled, memory-safe image caching and DiffUtil for smooth list updates.
 */
class EventAdapter(
    private val onItemClick: (MotionEventEntity) -> Unit,
    private val onDeleteClick: (MotionEventEntity) -> Unit
) : ListAdapter<MotionEventEntity, EventAdapter.EventViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MotionEventEntity) {
            // 1. Thumbnail loading via Coil
            val file = File(item.imagePath)
            binding.ivThumbnail.load(file) {
                crossfade(true)
                placeholder(R.drawable.chip_status_background)
                error(R.drawable.ic_stat_alert)
            }

            // 2. Formatted date & time
            binding.tvTimestamp.text = formatTimestamp(item.timestamp)

            // 3. Motion score
            binding.tvMotionScore.text = formatMotionScore(item.motionScore)

            // 4. Status chips
            applyStatusChip(binding.chipSms, "SMS", item.smsStatus)
            applyStatusChip(binding.chipEmail, "Email", item.emailStatus)

            // 5. Clicks
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDeleteEvent.setOnClickListener { onDeleteClick(item) }
        }

        private fun applyStatusChip(chip: TextView, prefix: String, status: String) {
            chip.text = formatStatusChipText(prefix, status)
            val textColor = getStatusChipColor(status)
            chip.setTextColor(textColor)
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<MotionEventEntity>() {
        override fun areItemsTheSame(oldItem: MotionEventEntity, newItem: MotionEventEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MotionEventEntity, newItem: MotionEventEntity): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)

        fun formatTimestamp(timestamp: Long): String {
            return dateFormat.format(Date(timestamp))
        }

        fun formatMotionScore(score: Float): String {
            val pct = (score.coerceIn(0f, 1f) * 100).toInt()
            return "Motion Score: $pct%"
        }

        fun formatStatusChipText(prefix: String, status: String): String {
            return when (status.uppercase(Locale.ROOT)) {
                MotionEventEntity.STATUS_SENT -> "$prefix: Sent"
                MotionEventEntity.STATUS_FAILED -> "$prefix: Failed"
                MotionEventEntity.STATUS_SUPPRESSED -> "$prefix: Cooldown"
                MotionEventEntity.STATUS_DISABLED -> "$prefix: Disabled"
                else -> "$prefix: $status"
            }
        }

        fun getStatusChipColor(status: String): Int {
            return when (status.uppercase(Locale.ROOT)) {
                MotionEventEntity.STATUS_SENT -> 0xFF00E676.toInt() // Green
                MotionEventEntity.STATUS_FAILED -> 0xFFFF1744.toInt() // Red
                MotionEventEntity.STATUS_SUPPRESSED -> 0xFFFFAB00.toInt() // Amber
                MotionEventEntity.STATUS_DISABLED -> 0xFF94A3B8.toInt() // Muted grey
                else -> 0xFFFFFFFF.toInt()
            }
        }
    }
}
