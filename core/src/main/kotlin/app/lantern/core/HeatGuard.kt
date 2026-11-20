package app.lantern.core

/**
 * Pauses preparing audio when the battery gets hot: paused from [pauseAt] °C until it cools to [resumeAt] °C. The gap
 * keeps it from switching on and off around one temperature.
 */
class HeatGuard(private val pauseAt: Float = 40f, private val resumeAt: Float = 37f) {
    var paused = false
        private set

    /** Takes the battery temperature in °C, or null when it can't be read, and returns whether to pause. */
    fun update(celsius: Float?): Boolean {
        if (celsius != null) {
            if (celsius >= pauseAt) paused = true else if (celsius <= resumeAt) paused = false
        }
        return paused
    }
}
