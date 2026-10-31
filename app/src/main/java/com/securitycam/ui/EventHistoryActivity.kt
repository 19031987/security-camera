package com.securitycam.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Event History Gallery Activity.
 * Displays past motion detection intrusion events with captured screenshot thumbnails,
 * motion scores, and SMS/Email delivery tracking.
 */
class EventHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var eventAdapter: EventAdapter

    private val app by lazy { SecurityCamApp.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        observeEvents()
    }

    private fun setupToolbar() {
        binding.toolbarHistory.setNavigationOnClickListener {
            finish()
        }

        binding.btnClearAllHistory.setOnClickListener {
            showClearAllConfirmationDialog()
        }
    }

    private fun setupRecyclerView() {
        eventAdapter = EventAdapter(
            onItemClick = { event ->
                showEventDetailDialog(event)
            },
            onDeleteClick = { event ->
                deleteSingleEvent(event)
            }
        )
        binding.rvEvents.adapter = eventAdapter
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.eventRepository.observeEvents().collect { events ->
                    eventAdapter.submitList(events)
                    if (events.isEmpty()) {
                        binding.layoutEmptyHistory.visibility = View.VISIBLE
                        binding.rvEvents.visibility = View.GONE
                        binding.btnClearAllHistory.visibility = View.GONE
                    } else {
                        binding.layoutEmptyHistory.visibility = View.GONE
                        binding.rvEvents.visibility = View.VISIBLE
                        binding.btnClearAllHistory.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun showEventDetailDialog(event: MotionEventEntity) {
        val dialog = EventDetailDialog.newInstance(event).apply {
            onDeleteListener = { eventToDelete ->
                deleteSingleEvent(eventToDelete)
            }
        }
        dialog.show(supportFragmentManager, EventDetailDialog.TAG)
    }

    private fun deleteSingleEvent(event: MotionEventEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Delete image file from storage
                val file = File(event.imagePath)
                app.imageStorageManager.deleteImage(file)
                // Delete event from Room DB
                app.eventRepository.deleteEvent(event.id)
            } catch (e: Exception) {
                android.util.Log.e("EventHistoryActivity", "Failed to delete event: ${e.message}", e)
            }
        }
    }

    private fun showClearAllConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_clear_all_events)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                clearAllEvents()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearAllEvents() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allEvents = app.eventRepository.getAllEvents()
                for (evt in allEvents) {
                    val f = File(evt.imagePath)
                    app.imageStorageManager.deleteImage(f)
                }
                app.eventRepository.clearAllEvents()
            } catch (e: Exception) {
                android.util.Log.e("EventHistoryActivity", "Failed to clear events: ${e.message}", e)
            }
        }
    }
}
