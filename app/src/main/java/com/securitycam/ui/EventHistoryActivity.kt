package com.securitycam.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.securitycam.R
import com.securitycam.SecurityCamApp
import com.securitycam.core.storage.MotionEventEntity
import com.securitycam.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Event History Activity.
 * Displays past motion detection events grouped by day, with screenshot thumbnails,
 * motion scores, and SMS/Email delivery tracking.
 */
class EventHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var eventAdapter: EventAdapter

    private val app by lazy { SecurityCamApp.getInstance() }

    private var allEvents: List<MotionEventEntity> = emptyList()

    /** Hidden from the list while its Undo snackbar is showing; deleted for real afterwards. */
    private var pendingDelete: MotionEventEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarHistory.padTopForSystemBars()
        BottomNav.setup(this, binding.bottomNav.root, R.id.nav_history)

        setupToolbar()
        setupRecyclerView()
        observeEvents()
    }

    override fun onStop() {
        super.onStop()
        // The snackbar can't be answered once the screen is gone, so finish the delete now.
        commitPendingDelete()
    }

    private fun setupToolbar() {
        binding.toolbarHistory.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_all) {
                showClearAllConfirmationDialog()
                true
            } else {
                false
            }
        }
    }

    private fun setupRecyclerView() {
        eventAdapter = EventAdapter(onItemClick = { event -> showEventDetailDialog(event) })
        binding.rvEvents.adapter = eventAdapter

        val swipeToDelete = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Day headers can't be swiped.
                return if (eventAdapter.eventAt(viewHolder.bindingAdapterPosition) == null) {
                    0
                } else {
                    super.getSwipeDirs(recyclerView, viewHolder)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val event = eventAdapter.eventAt(viewHolder.bindingAdapterPosition)
                if (event != null) {
                    requestDelete(event)
                } else {
                    render()
                }
            }
        }
        ItemTouchHelper(swipeToDelete).attachToRecyclerView(binding.rvEvents)
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.eventRepository.observeEvents().collect { events ->
                    allEvents = events
                    render()
                }
            }
        }
    }

    private fun render() {
        val visible = allEvents.filter { it.id != pendingDelete?.id }
        eventAdapter.submitList(EventGrouping.group(visible))

        val isEmpty = visible.isEmpty()
        binding.layoutEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvEvents.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.toolbarHistory.menu.findItem(R.id.action_clear_all)?.isVisible = !isEmpty
    }

    private fun showEventDetailDialog(event: MotionEventEntity) {
        val dialog = EventDetailDialog.newInstance(event).apply {
            onDeleteListener = { eventToDelete -> requestDelete(eventToDelete) }
        }
        dialog.show(supportFragmentManager, EventDetailDialog.TAG)
    }

    /** Hides the event straight away and deletes it once the Undo snackbar goes away. */
    private fun requestDelete(event: MotionEventEntity) {
        commitPendingDelete()
        pendingDelete = event
        render()

        Snackbar.make(binding.root, R.string.event_deleted, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomNav.root)
            .setAction(R.string.action_undo) {
                if (pendingDelete?.id == event.id) {
                    pendingDelete = null
                    render()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, dismissEvent: Int) {
                    if (dismissEvent != DISMISS_EVENT_ACTION && pendingDelete?.id == event.id) {
                        commitPendingDelete()
                    }
                }
            })
            .show()
    }

    private fun commitPendingDelete() {
        val event = pendingDelete ?: return
        pendingDelete = null
        deleteSingleEvent(event)
    }

    private fun deleteSingleEvent(event: MotionEventEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Not cancellable: leaving the screen must not leave a half-deleted event behind.
            withContext(NonCancellable) {
                try {
                    app.imageStorageManager.deleteImage(File(event.imagePath))
                    app.eventRepository.deleteEvent(event.id)
                    // A clip can be shared by several events; delete it with the last of them.
                    val clip = event.clipPath
                    if (!clip.isNullOrEmpty() && app.eventRepository.countEventsWithClip(clip) == 0) {
                        app.clipStore.delete(clip)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete event: ${e.message}", e)
                }
            }
        }
    }

    private fun showClearAllConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_clear_all_events)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                clearAllEvents()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearAllEvents() {
        pendingDelete = null
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                try {
                    val allEvents = app.eventRepository.getAllEvents()
                    for (evt in allEvents) {
                        app.imageStorageManager.deleteImage(File(evt.imagePath))
                    }
                    app.eventRepository.clearAllEvents()
                    app.clipStore.deleteAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear events: ${e.message}", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "EventHistoryActivity"
    }
}
