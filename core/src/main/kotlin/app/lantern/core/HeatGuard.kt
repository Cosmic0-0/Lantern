package app.lantern.core

/**
 * Says when making audio should hold back because the phone is hot. Two checks, either of which makes it hot:
 *
 * - Android's thermal status (0 none, 1 light, 2 moderate, 3 severe, and up): hot from [pauseAt] until it falls to
 *   [resumeAt] or below. This is the main check, tuned by the phone's maker.
 * - The battery temperature, as a backup in case the status never rises: hot from [batteryPauseAt] °C until it cools
 *   to [batteryResumeAt] °C. Around 45 °C is where phones start limiting charging and battery wear speeds up.
 *
 * Battery temperature alone was tried first and failed: on the charger the battery sat at 39 to 41 °C all night with
 * nothing running, so a 37 °C resume point was never reached (docs/decisions/prepared-audio.md).
 */
class HeatGuard(
    private val pauseAt: Int = MODERATE,
    private val resumeAt: Int = LIGHT,
    private val batteryPauseAt: Float = 45f,
    private val batteryResumeAt: Float = 42f,
) {
    private var statusHot = false
    private var batteryHot = false

    val hot get() = statusHot || batteryHot

    /** Takes the thermal status and battery °C, either null when it can't be read, and returns whether it is hot. */
    fun update(status: Int?, celsius: Float? = null): Boolean {
        if (status != null) {
            if (status >= pauseAt) statusHot = true else if (status <= resumeAt) statusHot = false
        }
        if (celsius != null) {
            if (celsius >= batteryPauseAt) batteryHot = true else if (celsius <= batteryResumeAt) batteryHot = false
        }
        return hot
    }

    companion object {
        const val LIGHT = 1
        const val MODERATE = 2
    }
}
