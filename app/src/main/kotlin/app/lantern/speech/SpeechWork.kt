package app.lantern.speech

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.locks.ReentrantLock

/**
 * What the playback generator and the prepare job share, so only one of them makes audio at a time
 * (docs/decisions/prepared-audio.md).
 */
object SpeechWork {
    /**
     * Held while making one sentence and while deleting audio, so a deletion never races a write and the two never
     * run the engine at once. Fair, so neither can starve the other.
     */
    val lock = ReentrantLock(true)

    /** True while playback is meant to be playing. The prepare job waits while it is. */
    val listening = MutableStateFlow(false)
}
