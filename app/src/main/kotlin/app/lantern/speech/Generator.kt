package app.lantern.speech

import app.lantern.core.ChapterPlan
import app.lantern.core.Position
import app.lantern.core.ReadAhead
import app.lantern.core.SpeechRules
import app.lantern.library.Chapter
import app.lantern.library.ChapterSpeech
import app.lantern.library.Library
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * Makes sentence audio ahead of playback, on one thread of its own, as Spike 2 did. What to make comes from
 * [ReadAhead]: the first unready sentence from the playing one, then each following chapter's, up to
 * [ReadAhead.CHAPTERS_AHEAD] chapters ahead. It never fetches text: it stops at the first chapter whose text isn't
 * stored yet (the playback service fetches ahead when a chapter starts, then calls [textArrived]).
 *
 * Audio is kept for every chapter not behind its book's position, so prepared chapters survive
 * (docs/decisions/prepared-audio.md). Each sentence is made, and old audio deleted, under [SpeechWork.lock], so the
 * prepare job and this never run the engine at once or delete what the other is writing.
 *
 * The book's skip rules and pronunciation fixes (docs/decisions/speech-rules.md) apply here: a skipped sentence counts
 * as ready and is never made, a sentence is spoken through [SpeechRules.spoken], and a chapter's audio lives under
 * [SpeechRules.audioKey] instead of its bare text hash. Phrases arrive already spoken from the playback service.
 *
 * While the phone is hot ([isHot]) it holds back: with playback paused it makes nothing, and while you listen it
 * makes only the playing chapter and the next, as much as playback needs (docs/decisions/prepared-audio.md).
 *
 * Must run inside the foreground playback service: a background app gets almost no CPU (Spike 1).
 */
class Generator(
    private val library: Library,
    private val audio: SentenceAudio,
    /** Called once, on the generator thread, before the model loads: installing it can take a while. */
    private val modelDir: () -> File,
    voice: String,
    /** Asked before each sentence; see the class comment. */
    private val isHot: () -> Boolean,
    private val log: (String) -> Unit,
) : AutoCloseable {
    /** [chapterId] is -1 for a phrase. [key] is where its audio goes: the chapter's audio key, or the phrase key. */
    private class Job(val chapterId: Long, val index: Int, val key: String, val text: String)

    /** What decides which audio is kept. When any of it changes, old audio may need deleting. */
    private data class Keep(val voice: String, val chapterId: Long, val phrases: List<String>, val rulesVersion: Int)

    private val wake = Semaphore(0) // a permit means "look again"; permits pile up, so no wake-up is lost

    @Volatile private var playing: Position? = null
    @Volatile private var voice = voice
    @Volatile private var phrases: List<String> = emptyList()
    @Volatile private var running = true
    @Volatile private var rulesStale = false

    // Only the generator thread touches these.
    private var rulesBook = -1L
    private var rules: SpeechRules? = null
    private var rulesVersion = 0
    private val speech = HashMap<Long, ChapterSpeech>() // chapter id -> its speech under [rules], for the chapters in reach
    private var wantKept: Keep? = null
    private var kept: Keep? = null // what the last clean-up was for
    // Audio key -> sentences known to be made in [readyVoice], so a look doesn't list files every time. Read from disk
    // again when the chapter changes, and a sentence is checked on disk once more before it is made.
    private val ready = HashMap<String, MutableSet<Int>>()
    private var readyVoice: String? = null
    private var readyChapter = -1L
    private var wasHot = false

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

    /** The book's rules changed: what is skipped, what is said and where audio lives are worked out again. */
    fun rulesChanged() {
        rulesStale = true
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
                try {
                    cleanUpIfNeeded()
                } catch (e: Exception) {
                    log("GEN error cleaning up: $e")
                }
                if (job == null) {
                    wake.tryAcquire(IDLE_WAIT_S, TimeUnit.SECONDS)
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
        val hot = isHot()
        if (hot != wasHot) {
            log(if (hot) "GEN hot: holding back" else "GEN cool: reading ahead again")
            wasHot = hot
        }
        if (hot && !SpeechWork.listening.value) return null
        val current = library.chapter(p.chapterId)?.takeIf { it.textHash != null } ?: return null
        val rules = rulesFor(current.bookId)
        if (v != readyVoice || current.id != readyChapter) {
            ready.clear()
            readyVoice = v
            readyChapter = current.id
        }
        // The playing chapter and the ones after it in reach, stopping at the first whose text isn't stored.
        val reach = if (hot) HOT_CHAPTERS_AHEAD else ReadAhead.CHAPTERS_AHEAD
        val window = library.chaptersFrom(current.id, 1 + reach).takeWhile { it.textHash != null }
        val chapters = window.associate { it.id to speechOf(it, rules) }
        speech.keys.retainAll(chapters.keys)
        wantKept = Keep(v, current.id, wanted.map(::phraseKey), rulesVersion)

        val plans = window.map { ChapterPlan(it.id, chapters.getValue(it.id).sentences.size) }
        val (chapterId, index) = ReadAhead.next(p, plans) { c, i ->
            val s = chapters.getValue(c)
            i in s.skipped || i in readyIn(v, s.key)
        } ?: return null
        val s = chapters.getValue(chapterId)
        return Job(chapterId, index, s.key, rules.spoken(s.sentences[index].text))
    }

    private fun readyIn(voice: String, key: String): MutableSet<Int> = ready.getOrPut(key) { audio.ready(voice, key).toHashSet() }

    /** The book's rules, loaded again when the book changes or [rulesChanged] was called. */
    private suspend fun rulesFor(bookId: Long): SpeechRules {
        val cached = rules
        if (cached != null && bookId == rulesBook && !rulesStale) return cached
        rulesStale = false // before loading, so a change during the load is seen next time
        speech.clear()
        rulesVersion++
        return library.rules(bookId).also {
            rules = it
            rulesBook = bookId
        }
    }

    /** [chapter]'s speech under [rules]. Its text is stored already, so this reads the database only. */
    private suspend fun speechOf(chapter: Chapter, rules: SpeechRules): ChapterSpeech =
        speech.getOrPut(chapter.id) { library.speech(chapter.id, rules) }

    /**
     * Deletes audio in other voices, phrases no longer used, and chapters behind their book's position, when what
     * decides that has changed. Under the lock, so the keys are read after any write in progress has finished.
     */
    private fun cleanUpIfNeeded() {
        val want = wantKept ?: return
        if (want == kept) return
        SpeechWork.lock.withLock {
            val keys = runBlocking { library.audioKeysAhead() }
            audio.keepOnly(want.voice, keys + want.phrases)
        }
        ready.keys.retainAll(audio.chapterKeys(want.voice))
        kept = want
    }

    private fun make(tts: PhononTTS, voice: String, job: Job) {
        val start = System.nanoTime()
        val samples = try {
            SpeechWork.lock.withLock {
                // The prepare job, or the laptop, may have made it meanwhile.
                if (audio.isReady(voice, job.key, job.index)) {
                    null
                } else {
                    audio.write(voice, job.key, job.index, onError = { e ->
                        log("GEN error chapter=${job.chapterId} sentence=${job.index} $e text=${job.text.take(60)}")
                    }) { w -> tts.speak(job.text) { pcm -> w.write(pcm); true } }
                }
            }
        } catch (e: IOException) {
            log("GEN error writing chapter=${job.chapterId} sentence=${job.index} $e")
            Thread.sleep(1000) // the disk is the problem: don't spin
            return
        }
        if (job.chapterId >= 0) ready[job.key]?.add(job.index)
        if (samples == null) return
        val wallMs = (System.nanoTime() - start) / 1e6
        val audioMs = samples * 1000.0 / Phonon.SAMPLE_RATE
        log(
            "GEN chapter=${job.chapterId} sentence=${job.index} wall_ms=${"%.0f".format(Locale.ROOT, wallMs)} " +
                "audio_ms=${"%.0f".format(Locale.ROOT, audioMs)} rtf=${"%.3f".format(Locale.ROOT, wallMs / audioMs)}",
        )
    }

    companion object {
        /** How long an idle generator sleeps before looking again. Every change that could give it work wakes it. */
        private const val IDLE_WAIT_S = 10L

        /** Chapters made past the playing one while the phone is hot: enough that the next chapter starts without a gap. */
        private const val HOT_CHAPTERS_AHEAD = 1

        /** Where a phrase's audio lives: SentenceAudio file 0 under this key, in place of a chapter's text hash. */
        fun phraseKey(text: String): String =
            SentenceAudio.PHRASE_PREFIX + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
