package app.lantern.spike.playback

import android.content.Context
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Turns sentences into WAV files just ahead of playback, on one thread of its own.
 * It makes the first unready sentence at or after the playing one, then the next chapter's, never further.
 * [onReady] is called on the generator thread after each sentence's file is in place.
 */
class Generator(context: Context, private val onReady: (chapter: Int, index: Int) -> Unit) {
    private class Playing(val chapter: Int, val index: Int)

    private val ctx = context.applicationContext
    private val wake = Semaphore(0) // a permit means "look again"; permits pile up, so no wake-up is lost

    @Volatile private var playing = Playing(-1, 0) // -1: nothing is playing yet, so nothing to make
    @Volatile private var running = true

    init {
        Thread({ run() }, "lantern-generator").start()
    }

    fun update(chapter: Int, index: Int) {
        playing = Playing(chapter, index)
        wakeUp()
    }

    fun textArrived() = wakeUp()

    /** Stops after the sentence in progress, and closes the model then. */
    fun shutdown() {
        running = false
        wakeUp()
    }

    private fun wakeUp() = wake.release()

    private fun run() {
        var tts: PhononTTS? = null
        try {
            val loadStart = System.nanoTime()
            try {
                // Rayon reads this once, at the first model load, so it must be set before PhononTTS is touched.
                Os.setenv("RAYON_NUM_THREADS", "2", true)
                val modelDir = File(ctx.getExternalFilesDir(null), "model")
                tts = PhononTTS(modelDir.path, "en").also { it.setVoice("everly") }
            } catch (t: Throwable) {
                EventLog.log("GEN", "fail load $t")
                return
            }
            EventLog.log("GEN", "load ms=${(System.nanoTime() - loadStart) / 1_000_000}")

            while (running) {
                val job = nextJob()
                if (job == null) {
                    wake.tryAcquire(2, TimeUnit.SECONDS)
                    wake.drainPermits()
                    continue
                }
                make(tts, job.first, job.second)
            }
        } catch (_: InterruptedException) {
            // Only shutdown wakes the wait, and it does not interrupt; nothing else to do.
        } finally {
            tts?.close()
            EventLog.log("GEN", "stopped")
        }
    }

    /** The (chapter, index) to make next, or null when there is nothing to do. */
    private fun nextJob(): Pair<Int, Int>? {
        val p = playing
        if (p.chapter < 0) return null
        val current = Chapters.sentences(ctx, p.chapter) ?: return null
        for (i in p.index until current.size) {
            if (!AudioCache.isReady(ctx, p.chapter, i)) return p.chapter to i
        }
        val next = Chapters.sentences(ctx, p.chapter + 1) ?: return null
        for (i in next.indices) {
            if (!AudioCache.isReady(ctx, p.chapter + 1, i)) return (p.chapter + 1) to i
        }
        return null
    }

    private fun make(tts: PhononTTS, chapter: Int, index: Int) {
        val text = Chapters.sentences(ctx, chapter)?.getOrNull(index) ?: return
        val wav = AudioCache.file(ctx, chapter, index)
        val tmp = File(wav.parentFile, "$index.wav.tmp")
        var samples = 0L
        val start = System.nanoTime()
        var wallMs = 0.0
        try {
            wav.parentFile?.mkdirs()
            WavWriter(tmp).use { w ->
                tts.speak(text) { pcm -> w.write(pcm); samples += pcm.size; true }
            }
            wallMs = (System.nanoTime() - start) / 1e6
            if (samples == 0L) throw IOException("the engine produced no audio")
            if (!tmp.renameTo(wav)) throw IOException("cannot rename ${tmp.name}")
        } catch (t: Throwable) {
            // A sentence that cannot be made must not stall playback for good, so it becomes a short silence.
            EventLog.log("GEN", "error chapter=$chapter sentence=$index $t text=${text.take(60)}")
            try {
                tmp.delete()
                WavWriter(tmp).use { it.write(FloatArray(SILENCE_SAMPLES)) }
                samples = SILENCE_SAMPLES.toLong()
                if (!tmp.renameTo(wav)) throw IOException("cannot rename ${tmp.name}")
            } catch (t2: Throwable) {
                EventLog.log("GEN", "error silence chapter=$chapter sentence=$index $t2")
                tmp.delete()
                Thread.sleep(1000) // do not spin if the disk is the problem
                return
            }
        }
        val audioMs = samples * 1000.0 / SAMPLE_RATE
        val rtf = if (audioMs > 0) wallMs / audioMs else Double.NaN
        val playingNow = playing
        var ahead = 0
        if (playingNow.chapter >= 0) {
            val count = Chapters.sentences(ctx, playingNow.chapter)?.size ?: 0
            for (i in playingNow.index until count) if (AudioCache.isReady(ctx, playingNow.chapter, i)) ahead++
        }
        EventLog.log(
            "GEN",
            "chapter=$chapter sentence=$index wall_ms=${f0(wallMs)} audio_ms=${f0(audioMs)} rtf=${String.format(Locale.ROOT, "%.3f", rtf)} " +
                "ahead=$ahead ${EventLog.state(ctx)}",
        )
        onReady(chapter, index)
    }

    private fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val SILENCE_SAMPLES = 7_200 // 300 ms
    }
}
