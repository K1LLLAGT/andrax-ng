package net.k1lllagt.andraxng.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide install progress. Listeners are called on the worker thread: post to the UI thread. */
object InstallState {
    data class Snapshot(
        val running: Boolean = false,
        val profileId: String? = null,
        val phase: String = "",
        val done: Long = 0,
        val total: Long = 0,
        val detail: String = "",
        val error: String? = null,
        val finished: Boolean = false
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    val cancel = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
        l(snapshot)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(s: Snapshot) {
        snapshot = s
        for (l in listeners) l(s)
    }

    fun begin(profileId: String) {
        cancel.set(false)
        publish(Snapshot(running = true, profileId = profileId, phase = "Starting"))
    }

    fun progress(phase: String, done: Long, total: Long, detail: String) {
        publish(snapshot.copy(running = true, phase = phase, done = done, total = total, detail = detail))
    }

    fun succeed() {
        publish(snapshot.copy(running = false, finished = true, error = null, phase = "Done"))
    }

    fun fail(message: String) {
        publish(snapshot.copy(running = false, finished = true, error = message))
    }
}
