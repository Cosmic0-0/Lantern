package app.lantern.speech

import app.lantern.core.Position
import app.lantern.core.ReadAhead
import app.lantern.library.Library
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Makes sentence audio just ahead of playback, on one thread of its own, as Spike 2 did. What to make comes from
 * [ReadAhead]: the first unready sentence from the playing one, then the next chapter's, never further. It never
 * fetches text: a chapter whose text isn't stored yet is simply not made (the playback service fetches the next
 * chapter when one starts, then calls [textArrived]).
 *
 * Must run inside the foreground playback service: a background app gets almost no CPU (Spike 1).
 */
class Generator(
    private val library: Library,
    private val audio: SentenceAudio,
    /** Called once, on the generator thread, before the model loads: installing it can take a while. */
    private val modelDir: () -> File,
    voice: String,
    private val log: (String) -> Unit,
) : AutoCloseable {
    /** [chapterId] is -1 for a phrase. */
    private class Job(val chapterId: Long, val index: Int, val textHash: String, val text: String)

    private val wake = Semaphore(0) // a permit means "look again"; permits pile up, so no wake-up is lost

    @Volatile private var playing: Position? = null
    @Volatile private var voice = voice
    @Volatile private var phrases: List<String> = emptyList()
    @Volatile private var running = true

    // Only the generator thread touches these.
    private var cachedChapter = -1L
    private var cachedSentences: List<String> = emptyList()
    private var kept: Pair<String, Set<String>>? = null

    private val thread = Thread(::run, "lantern-generator").apply { start() }

    /** The sentence playing now. Making starts from here. */
    fun update(position: Position) {
        playing = position
        wake.release()
    }

    /** Speaks in [name] from the next sentence made. Audio in other voices is deleted. */
    fun setVoice(name: String) {
        require(name in Phonon.VOICES) { "unknown voice $name" }
        voice = name
        wake.release()
    }

    /**
     * Short texts that aren't chapter sentences, such as a spoken chapter title or "No more chapters yet". Each is
     * made before any sentence, as audio file 0 under [phraseKey], and kept until a later call leaves it out.
     */
    fun setPhrases(texts: List<String>) {
        phrases = texts.distinct()
        wake.release()
    }

    /** A chapter's text was just stored, so there may be more to make. */
    fun textArrived() = wake.release()

    /** Stops after the sentence in progress, then closes the model. */
    override fun close() {
        running = false
        wake.release()
    }

    /** Waits for the thread to stop after [close]. */
    fun join(timeoutMs: Long) = thread.join(timeoutMs)

    private fun run() {
        var tts: PhononTTS? = null
        var ttsVoice: String? = null
        try {
            while (running) {
                val job = try {
                    runBlocking { nextJob() }
                } catch (e: Exception) {
                    log("GEN error looking for work: $e")
                    null
                }
                if (job == null) {
                    wake.tryAcquire(2, TimeUnit.SECONDS)
                    wake.drainPermits()
                    continue
                }
                val v = voice
                if (tts == null) {
                    val start = System.nanoTime()
                    tts = Phonon.load(modelDir(), v)
                    log("GEN load voice=$v ms=${(System.nanoTime() - start) / 1_000_000}")
                } else if (ttsVoice != v) {
                    tts.setVoice(v)
                }
                ttsVoice = v
                make(tts, v, job)
            }
        } catch (_: InterruptedException) {
            // Nothing interrupts this thread on purpose; stop quietly if something does.
        } catch (t: Throwable) {
            log("GEN fail $t")
        } finally {
            tts?.close()
            log("GEN stopped")
        }
    }

    private suspend fun nextJob(): Job? {
        val v = voice
        val wanted = phrases
        wanted.firstOrNull { !audio.isReady(v, phraseKey(it), 0) }?.let { return Job(-1, 0, phraseKey(it), it) }

        val p = playing ?: return null
        val current = library.chapter(p.chapterId) ?: return null
        val currentHash = current.textHash ?: return null
        val currentPlan = library.plan(current.id) ?: return null
        val next = library.nextChapter(current.id)
        val nextPlan = next?.let { library.plan(it.id) }
        val hashes = buildMap {
            put(current.id, currentHash)
            if (nextPlan != null) next.textHash?.let { put(next.id, it) }
        }
        keepOnly(v, hashes.values.toSet() + wanted.map(::phraseKey))

        val (chapterId, index) = ReadAhead.next(p, currentPlan, nextPlan) { c, i -> audio.isReady(v, hashes.getValue(c), i) }
            ?: return null
        if (chapterId != cachedChapter) {
            cachedSentences = library.sentences(chapterId) // stored already, so this reads the database only
            cachedChapter = chapterId
        }
        return Job(chapterId, index, hashes.getValue(chapterId), cachedSentences[index])
    }

    /** Only the current and next chapters' audio, in the current voice, is kept. Runs when that set changes. */
    private fun keepOnly(voice: String, hashes: Set<String>) {
        val want = voice to hashes
        if (kept == want) return
        audio.keepOnly(voice, hashes)
        kept = want
    }

    private fun make(tts: PhononTTS, voice: String, job: Job) {
        val start = System.nanoTime()
        val samples = try {
            audio.write(voice, job.textHash, job.index, onError = { e ->
                log("GEN error chapter=${job.chapterId} sentence=${job.index} $e text=${job.text.take(60)}")
            }) { w -> tts.speak(job.text) { pcm -> w.write(pcm); true } }
        } catch (e: IOException) {
            log("GEN error writing chapter=${job.chapterId} sentence=${job.index} $e")
            Thread.sleep(1000) // the disk is the problem: don't spin
            return
        }
        val wallMs = (System.nanoTime() - start) / 1e6
        val audioMs = samples * 1000.0 / Phonon.SAMPLE_RATE
        log(
            "GEN chapter=${job.chapterId} sentence=${job.index} wall_ms=${"%.0f".format(Locale.ROOT, wallMs)} " +
                "audio_ms=${"%.0f".format(Locale.ROOT, audioMs)} rtf=${"%.3f".format(Locale.ROOT, wallMs / audioMs)}",
        )
    }

    companion object {
        /** Where a phrase's audio lives: SentenceAudio file 0 under this key, in place of a chapter's text hash. */
        fun phraseKey(text: String): String =
            "phrase-" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
