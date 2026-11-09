package com.securitycam.core.remote

/**
 * Recent alerts, kept in memory on the camera phone so that receiver phones can collect them.
 * Each event gets an increasing id; a receiver asks for "everything after the id I last saw".
 */
class AlertEventHub(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Event(
        val id: Long,
        /** [TYPE_ALERT] for unrecognised motion, [TYPE_ARRIVAL] for a recognised household member. */
        val type: String,
        val title: String,
        val message: String,
        val time: Long,
        /** The stored event this refers to, used to fetch its screenshot. */
        val eventId: String?
    )

    private val lock = Object()
    private val events = ArrayDeque<Event>()
    private var lastId = 0L

    val latestId: Long get() = synchronized(lock) { lastId }

    fun publish(type: String, title: String, message: String, time: Long, eventId: String?): Event {
        synchronized(lock) {
            lastId++
            val event = Event(lastId, type, title, message, time, eventId)
            events.addLast(event)
            while (events.size > capacity) events.removeFirst()
            lock.notifyAll()
            return event
        }
    }

    /**
     * Events with an id above [afterId], waiting up to [timeoutMs] for one to arrive.
     * Returns at once with an empty list if [afterId] is ahead of this hub, which happens when
     * the camera app restarted and its ids started again; the caller then resynchronises.
     */
    fun await(afterId: Long, timeoutMs: Long): List<Event> {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                if (afterId > lastId) return emptyList()
                val newer = events.filter { it.id > afterId }
                if (newer.isNotEmpty()) return newer
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return emptyList()
                lock.wait(remaining)
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val TYPE_ALERT = "alert"
        const val TYPE_ARRIVAL = "arrival"
    }
}
