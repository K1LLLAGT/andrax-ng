package net.k1lllagt.andraxng.core

/** Small bridge between the terminal UI and SessionService (notification text, "Stop" action). */
object SessionHub {
    @Volatile
    var activeSessions: Int = 0

    @Volatile
    var onStopRequested: (() -> Unit)? = null
}
