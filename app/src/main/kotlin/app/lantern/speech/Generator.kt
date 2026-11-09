package app.lantern.speech

import app.lantern.core.ChapterPlan
import app.lantern.core.Position
import app.lantern.core.ReadAhead
import app.lantern.core.Sentence
import app.lantern.core.SpeechRules
import app.lantern.library.Chapter
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
 * The book's skip rules and pronunciation fixes (docs/decisions/speech-rules.md) apply here: a skipped sentence counts
 * as ready and is never made, a sentence is spoken through [SpeechRules.spoken], and a chapter's audio lives under
 * [SpeechRules.audioKey] instead of its bare text hash. Phrases arrive already spoken from the playback service.
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
    /** [chapterId] is -1 for a phrase. [key] is where its audio goes: the chapter's audio key, or the phrase key. */
    private class Job(val chapterId: Long, val index: Int, val key: String, val text: String)

    /** A chapter as the rules see it: its sentences, which are skipped, and its audio key. */
    private class Speech(val sentences: List<Sentence>, val skipped: Set<Int>, val key: String)

    private val wake = Semaphore(0) // a permit means "look again"; permits pile up, so no wake-up is lost

    @Volatile private var playing: Position? = null
    @Volatile private var voice = voice
    @Volatile private var phrases: List<String> = emptyList()
    @Volatile private var running = true
    @Volatile private var rulesStale = false


    // Only the generator thread touches these.
    private var rulesBook = -1L
    private var rules: SpeechRules? = null
    private val speech = HashMap<Long, Speech>() // chapter id -> its speech under [rules]; the current and next only
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
        val current = library.chapter(p.chapterId)?.takeIf { it.textHash != null } ?: return null
        val rules = rulesFor(current.bookId)
        val next = library.nextChapter(current.id)?.takeIf { it.textHash != null }
        val chapters = buildMap {
            put(current.id, speechOf(current, rules))
            if (next != null) put(next.id, speechOf(next, rules))
        }
        speech.keys.retainAll(chapters.keys)
        keepOnly(v, chapters.values.mapTo(HashSet()) { it.key } + wanted.map(::phraseKey))

        val currentPlan = ChapterPlan(current.id, chapters.getValue(current.id).sentences.size)
        val nextPlan = next?.let { ChapterPlan(it.id, chapters.getValue(it.id).sentences.size) }
        val (chapterId, index) = ReadAhead.next(p, currentPlan, nextPlan) { c, i ->
            val s = chapters.getValue(c)
            i in s.skipped || audio.isReady(v, s.key, i)
        } ?: return null
        val s = chapters.getValue(chapterId)
        return Job(chapterId, index, s.key, rules.spoken(s.sentences[index].text))
    }

    /** The book's rules, loaded again when the book changes or [rulesChanged] was called. */
    private suspend fun rulesFor(bookId: Long): SpeechRules {
        val cached = rules
        if (cached != null && bookId == rulesBook && !rulesStale) return cached
        rulesStale = false // before loading, so a change during the load is seen next time
        speech.clear()
        return library.rules(bookId).also {
            rules = it
            rulesBook = bookId
        }
    }

    /** [chapter]'s speech under [rules]. Its text is stored already, so this reads the database only. */
    private suspend fun speechOf(chapter: Chapter, rules: SpeechRules): Speech = speech.getOrPut(chapter.id) {
        val sentences = library.chapterText(chapter.id).sentences
        Speech(sentences, rules.skipped(sentences), rules.audioKey(requireNotNull(chapter.textHash), sentences))
    }

    /** Only the current and next chapters' audio, in the current voice, is kept. Runs when that set changes. */
    private fun keepOnly(voice: String, keys: Set<String>) {
        val want = voice to keys
        if (kept == want) return
        audio.keepOnly(voice, keys)
        kept = want
    }

    private fun make(tts: PhononTTS, voice: String, job: Job) {
        val start = System.nanoTime()
        val samples = try {
            audio.write(voice, job.key, job.index, onError = { e ->
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
