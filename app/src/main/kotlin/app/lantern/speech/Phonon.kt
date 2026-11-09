package app.lantern.speech

import android.system.Os
import java.io.File

/** Lantern's settings for the Phonon engine. Loading is the slow part (0.2 to 0.4 s in Spike 1): keep the result. */
object Phonon {
    /** Fixed, with no setting and no pacing: docs/decisions/generation-pacing.md. */
    const val THREADS = 2
    const val SAMPLE_RATE = 24_000
    val VOICES = listOf("everly", "trevor")

    private var threadsSet = false

    /**
     * Sets RAYON_NUM_THREADS (once, before the first model load, which is when Rayon reads it) and loads the model
     * from [modelDir] with [voice]. Blocking: call it off the main thread. The caller closes the result.
     */
    fun load(modelDir: File, voice: String): PhononTTS {
        setThreadsOnce()
        val tts = PhononTTS(modelDir.path, "en")
        try {
            tts.setVoice(voice)
        } catch (t: Throwable) {
            tts.close()
            throw t
        }
        return tts
    }

    @Synchronized
    private fun setThreadsOnce() {
        if (threadsSet) return
        Os.setenv("RAYON_NUM_THREADS", THREADS.toString(), true)
        threadsSet = true
    }
}
