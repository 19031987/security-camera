package com.securitycam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.securitycam.R
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.ItemEventBinding
import com.securitycam.databinding.ItemEventHeaderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the Motion Event History list: day headers with event cards under them.
 * Employs Coil for downsampled, memory-safe image caching and DiffUtil for smooth list updates.
 */
class EventAdapter(
    private val onItemClick: (MotionEventEntity) -> Unit
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(RowDiffCallback()) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HistoryRow.Header -> VIEW_TYPE_HEADER
        is HistoryRow.Event -> VIEW_TYPE_EVENT
    }

    /** The event shown at [position], or null when that row is a day header. */
    fun eventAt(position: Int): MotionEventEntity? {
        if (position !in 0 until itemCount) return null
        return (getItem(position) as? HistoryRow.Event)?.event
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(ItemEventHeaderBinding.inflate(inflater, parent, false))
        } else {
            EventViewHolder(ItemEventBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Header -> (holder as HeaderViewHolder).bind(row)
            is HistoryRow.Event -> (holder as EventViewHolder).bind(row.event)
        }
    }

    class HeaderViewHolder(
        private val binding: ItemEventHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: HistoryRow.Header) {
            val context = binding.root.context
            binding.tvDayLabel.text = when (
                EventGrouping.dayKind(header.dayStartMillis, System.currentTimeMillis())
            ) {
                EventGrouping.DayKind.TODAY -> context.getString(R.string.history_day_today)
                EventGrouping.DayKind.YESTERDAY -> context.getString(R.string.history_day_yesterday)
                EventGrouping.DayKind.OTHER -> formatDay(header.dayStartMillis)
            }
            binding.tvDayCount.text = context.resources.getQuantityString(
                R.plurals.history_day_count, header.eventCount, header.eventCount
            )
        }
    }

    inner class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MotionEventEntity) {
            // 1. Thumbnail loading via Coil
            binding.ivThumbnail.load(File(item.imagePath)) {
                crossfade(true)
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
            }

            // 2. Time of day (the date is in the header above)
            binding.tvTimestamp.text = formatTime(item.timestamp)

            // 3. Motion score and low-light badge
            val detected = item.detectedLabel
            binding.tvMotionScore.text = if (detected.isNullOrBlank()) {
                formatMotionScore(item.motionScore)
            } else {
                "$detected · ${formatMotionScore(item.motionScore)}"
            }
            binding.tvLowLight.visibility = if (item.isLowLight) View.VISIBLE else View.GONE
            binding.tvClipBadge.visibility = if (item.clipPath.isNullOrEmpty()) View.GONE else View.VISIBLE
            val recognized = item.recognizedPerson
            binding.tvRecognized.visibility = if (recognized.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvRecognized.text = binding.root.context.getString(R.string.event_recognized, recognized.orEmpty())

            // 4. Status chips
            applyStatusChip(binding.chipSms, "SMS", item.smsStatus)
            applyStatusChip(binding.chipEmail, "Email", item.emailStatus)

            // 5. Clicks
            binding.root.setOnClickListener { onItemClick(item) }
        }

        private fun applyStatusChip(chip: TextView, prefix: String, status: String) {
            chip.text = formatStatusChipText(prefix, status)
            chip.setTextColor(getStatusChipColor(status))
        }
    }

    /** Identity and content comparison for two events. */
    class EventDiffCallback : DiffUtil.ItemCallback<MotionEventEntity>() {
        override fun areItemsTheSame(oldItem: MotionEventEntity, newItem: MotionEventEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MotionEventEntity, newItem: MotionEventEntity): Boolean {
            return oldItem == newItem
        }
    }

    class RowDiffCallback : DiffUtil.ItemCallback<HistoryRow>() {
        private val eventDiff = EventDiffCallback()

        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean {
            return when {
                oldItem is HistoryRow.Header && newItem is HistoryRow.Header ->
                    oldItem.dayStartMillis == newItem.dayStartMillis
                oldItem is HistoryRow.Event && newItem is HistoryRow.Event ->
                    eventDiff.areItemsTheSame(oldItem.event, newItem.event)
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_EVENT = 1

        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)

        fun formatTimestamp(timestamp: Long): String {
            return dateFormat.format(Date(timestamp))
        }

        fun formatTime(timestamp: Long): String {
            return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
        }

        fun formatDay(timestamp: Long): String {
            return SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(Date(timestamp))
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
                MotionEventEntity.STATUS_KNOWN_PERSON -> "$prefix: Not sent"
                MotionEventEntity.STATUS_QUIET -> "$prefix: Not sent"
                else -> "$prefix: $status"
            }
        }

        fun getStatusChipColor(status: String): Int {
            return when (status.uppercase(Locale.ROOT)) {
                MotionEventEntity.STATUS_SENT -> 0xFF00E676.toInt() // Green
                MotionEventEntity.STATUS_FAILED -> 0xFFFF1744.toInt() // Red
                MotionEventEntity.STATUS_SUPPRESSED -> 0xFFFFAB00.toInt() // Amber
                MotionEventEntity.STATUS_DISABLED -> 0xFF94A3B8.toInt() // Muted grey
                MotionEventEntity.STATUS_KNOWN_PERSON -> 0xFF94A3B8.toInt() // Muted grey: nothing went wrong
                MotionEventEntity.STATUS_QUIET -> 0xFF94A3B8.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
    }
}
