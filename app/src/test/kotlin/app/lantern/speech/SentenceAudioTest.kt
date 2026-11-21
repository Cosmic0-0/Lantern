package app.lantern.speech

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SentenceAudioTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "audio")
    private val audio get() = SentenceAudio(root)

    private fun dataBytes(file: File) = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

    private fun leftovers() = root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()

    @Test
    fun `file path is root voice hash index dot wav`() {
        assertEquals(File(root, "everly/abc123/7.wav"), audio.file("everly", "abc123", 7))
    }

    @Test
    fun `nothing is ready until it is written`() {
        assertFalse(audio.isReady("everly", "abc", 0))
        audio.write("everly", "abc", 0) { it.write(FloatArray(10)) }
        assertTrue(audio.isReady("everly", "abc", 0))
        assertFalse(audio.isReady("everly", "abc", 1))
        assertFalse(audio.isReady("trevor", "abc", 0))
        assertFalse(audio.isReady("everly", "other", 0))
    }

    @Test
    fun `write returns the samples and the file holds exactly them`() {
        val n = audio.write("everly", "abc", 3) {
            it.write(FloatArray(100))
            it.write(FloatArray(50))
        }
        assertEquals(150L, n)
        assertEquals(300, dataBytes(audio.file("everly", "abc", 3)))
    }

    @Test
    fun `the wav appears only when the sentence is finished and no tmp is left`() {
        val a = audio
        a.write("everly", "abc", 0) {
            assertFalse(a.isReady("everly", "abc", 0), "not ready while writing")
            assertTrue(File(a.file("everly", "abc", 0).path + ".tmp").isFile, "writes to .wav.tmp")
            it.write(FloatArray(10))
        }
        assertTrue(a.isReady("everly", "abc", 0))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a throwing producer leaves 300 ms of silence and reports the error`() {
        val errors = mutableListOf<Throwable>()
        val n = audio.write("everly", "abc", 0, onError = { errors += it }) {
            it.write(FloatArray(5000) { 0.5f }) // partial audio is discarded
            throw IllegalStateException("engine failed")
        }
        assertEquals(7_200L, n)
        assertEquals(listOf("engine failed"), errors.map { it.message })
        val bytes = audio.file("everly", "abc", 0).readBytes()
        assertEquals(44 + 14_400, bytes.size)
        assertTrue(bytes.drop(44).all { it == 0.toByte() }, "silence")
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `a producer that writes no samples also leaves silence`() {
        val errors = mutableListOf<Throwable>()
        val n = audio.write("everly", "abc", 0, onError = { errors += it }) { it.write(FloatArray(0)) }
        assertEquals(7_200L, n)
        assertTrue(audio.isReady("everly", "abc", 0))
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IOException)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `an interrupt leaves nothing behind, so the sentence is made properly later`() {
        assertFailsWith<InterruptedException> {
            audio.write("everly", "abc", 0) {
                it.write(FloatArray(100))
                throw InterruptedException()
            }
        }
        assertTrue(Thread.interrupted(), "interrupt flag restored")
        assertFalse(audio.isReady("everly", "abc", 0))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `an error in onError does not stop the silence`() {
        val n = audio.write("everly", "abc", 0, onError = { error("logger broke") }) { error("engine failed") }
        assertEquals(7_200L, n)
        assertTrue(audio.isReady("everly", "abc", 0))
    }

    @Test
    fun `an unwritable folder throws instead of pretending`() {
        File(tmp.root, "audio").writeText("a file where the root folder should be")
        assertFailsWith<IOException> { audio.write("everly", "abc", 0) { it.write(FloatArray(10)) } }
        assertFalse(audio.isReady("everly", "abc", 0))
    }

    @Test
    fun `keepOnly keeps the listed chapters in every voice and deletes the rest`() {
        val a = audio
        for (voice in listOf("everly", "trevor")) {
            for (hash in listOf("h1", "h2", "h3")) a.write(voice, hash, 0) { it.write(FloatArray(10)) }
        }
        a.keepOnly(setOf("h1", "h3"))
        for (voice in listOf("everly", "trevor")) {
            assertTrue(a.isReady(voice, "h1", 0))
            assertFalse(a.isReady(voice, "h2", 0))
            assertFalse(File(root, "$voice/h2").exists())
            assertTrue(a.isReady(voice, "h3", 0))
        }
    }

    @Test
    fun `keepOnly with nothing to keep empties the voice but keeps its folder, and a missing root is fine`() {
        SentenceAudio(File(tmp.root, "never-created")).keepOnly(setOf("h1"))

        val a = audio
        a.write("everly", "h1", 0) { it.write(FloatArray(10)) }
        a.keepOnly(emptySet())
        assertFalse(a.isReady("everly", "h1", 0))
        assertTrue(File(root, "everly").isDirectory)
    }

    @Test
    fun `ready lists the finished sentences of a chapter and nothing else`() {
        audio.write("everly", "abc", 0) { it.write(FloatArray(10)) }
        audio.write("everly", "abc", 3) { it.write(FloatArray(10)) }
        File(root, "everly/abc/5.wav.tmp").writeText("half")
        File(root, "everly/abc/notes.txt").writeText("x")
        assertEquals(setOf(0, 3), audio.ready("everly", "abc"))
        assertEquals(emptySet(), audio.ready("everly", "missing"))
    }

    @Test
    fun `bytes counts a chapter's finished files`() {
        audio.write("everly", "abc", 0) { it.write(FloatArray(10)) }
        audio.write("everly", "abc", 1) { it.write(FloatArray(20)) }
        File(root, "everly/abc/2.wav.tmp").writeText("half")
        assertEquals(audio.file("everly", "abc", 0).length() + audio.file("everly", "abc", 1).length(), audio.bytes("everly", "abc"))
        assertEquals(0L, audio.bytes("everly", "missing"))
    }

    @Test
    fun `chapterKeys lists a voice's chapters, not its phrases`() {
        audio.write("everly", "abc", 0) { it.write(FloatArray(10)) }
        audio.write("everly", "def", 0) { it.write(FloatArray(10)) }
        audio.write("everly", "phrase-0123", 0) { it.write(FloatArray(10)) }
        audio.write("trevor", "ghi", 0) { it.write(FloatArray(10)) }
        assertEquals(setOf("abc", "def"), audio.chapterKeys("everly"))
        assertEquals(emptySet(), audio.chapterKeys("nobody"))
    }
}
