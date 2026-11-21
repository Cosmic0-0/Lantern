package app.lantern.core

/**
 * Says when making audio should hold back because the phone is hot, from Android's thermal status (0 none, 1 light,
 * 2 moderate, 3 severe, and up). Hot from [pauseAt] until it falls to [resumeAt] or below.
 *
 * Battery temperature was tried first and failed: on the charger the battery sat at 39 to 41 °C all night with nothing
 * running, so a 37 °C resume point was never reached (docs/decisions/prepared-audio.md). The thermal status is what
 * Android itself throttles on, and it stays "light" from charging alone.
 */
class HeatGuard(private val pauseAt: Int = MODERATE, private val resumeAt: Int = LIGHT) {
    var hot = false
        private set

    /** Takes the thermal status, or null when it can't be read, and returns whether the phone is hot. */
    fun update(status: Int?): Boolean {
        if (status != null) {
            if (status >= pauseAt) hot = true else if (status <= resumeAt) hot = false
        }
        return hot
    }

    companion object {
        const val LIGHT = 1
        const val MODERATE = 2
    }
}
