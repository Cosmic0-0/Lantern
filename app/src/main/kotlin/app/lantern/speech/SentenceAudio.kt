package app.lantern.speech

import java.io.File
import java.io.IOException

/**
 * Sentence audio files: root/<voice>/<textHash>/<index>.wav (docs/decisions/storage.md). A finished .wav is complete;
 * writing goes through <index>.wav.tmp and a rename. Plain java.io, so it is unit tested on the laptop.
 */
class SentenceAudio(private val root: File) {
    fun file(voice: String, textHash: String, index: Int): File = File(File(File(root, voice), textHash), "$index.wav")

    fun isReady(voice: String, textHash: String, index: Int): Boolean = file(voice, textHash, index).isFile

    /** The indexes of the chapter's finished sentences, from one directory listing. */
    fun ready(voice: String, textHash: String): Set<Int> =
        finished(voice, textHash).mapNotNullTo(HashSet()) { it.name.removeSuffix(".wav").toIntOrNull() }

    /** The size of the chapter's finished sentences in bytes. */
    fun bytes(voice: String, textHash: String): Long = finished(voice, textHash).sumOf { it.length() }

    /** The keys of every chapter with audio in [voice]. Phrase folders are left out. */
    fun chapterKeys(voice: String): Set<String> =
        File(root, voice).listFiles { f -> f.isDirectory && !f.name.startsWith(PHRASE_PREFIX) }.orEmpty().mapTo(HashSet()) { it.name }

    private fun finished(voice: String, textHash: String): List<File> =
        File(File(root, voice), textHash).listFiles { f -> f.isFile && f.name.endsWith(".wav") }.orEmpty().toList()

    /**
     * Writes the sentence through [produce]. If it throws or writes no samples, 300 ms of silence goes in its place, so
     * playback never stalls on one bad sentence, and [onError] gets the reason. Returns the number of samples written.
     * Throws when even the silence cannot be written (a disk problem): the caller should back off then. An
     * [InterruptedException] from [produce] is rethrown with nothing written.
     */
    fun write(
        voice: String,
        textHash: String,
        index: Int,
        onError: (Throwable) -> Unit = {},
        produce: (WavWriter) -> Unit,
    ): Long {
        val wav = file(voice, textHash, index)
        val tmp = File(wav.parentFile, "${wav.name}.tmp")
        wav.parentFile.mkdirs()
        var samples: Long
        try {
            samples = WavWriter(tmp).use { w ->
                produce(w)
                w.samples
            }
            if (samples == 0L) throw IOException("the engine produced no audio")
        } catch (e: InterruptedException) {
            // Stopping is not a bad sentence: leave nothing, so it is made properly next time.
            tmp.delete()
            Thread.currentThread().interrupt()
            throw e
        } catch (t: Throwable) {
            runCatching { onError(t) }
            tmp.delete()
            samples = writeSilence(tmp)
        }
        if (!tmp.renameTo(wav)) {
            tmp.delete()
            throw IOException("cannot rename ${tmp.name} to ${wav.name}")
        }
        return samples
    }

    /** Deletes every voice folder except [voice], and inside it every chapter folder whose hash isn't in [keep]. */
    fun keepOnly(voice: String, keep: Set<String>) {
        for (voiceDir in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            if (voiceDir.name != voice) {
                voiceDir.deleteRecursively()
                continue
            }
            for (chapterDir in voiceDir.listFiles { f -> f.isDirectory }.orEmpty()) {
                if (chapterDir.name !in keep) chapterDir.deleteRecursively()
            }
        }
    }

    private fun writeSilence(tmp: File): Long = try {
        WavWriter(tmp).use { w ->
            w.write(FloatArray(SILENCE_SAMPLES))
            w.samples
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }

    companion object {
        /** Starts every phrase key (Generator.phraseKey), so phrase folders can be told from chapter folders. */
        const val PHRASE_PREFIX = "phrase-"
        private const val SILENCE_SAMPLES = Phonon.SAMPLE_RATE * 3 / 10 // 300 ms
    }
}
