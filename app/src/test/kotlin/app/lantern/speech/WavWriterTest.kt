package app.lantern.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun wav(vararg chunks: FloatArray): ByteBuffer {
        val file = File(tmp.root, "a.wav")
        WavWriter(file).use { w -> chunks.forEach { w.write(it) } }
        return ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteBuffer.tag(at: Int) = String(ByteArray(4) { get(at + it) }, Charsets.US_ASCII)

    private fun ByteBuffer.sample(index: Int): Int = getShort(44 + index * 2).toInt()

    @Test
    fun `header describes 24 kHz mono 16-bit PCM with sizes patched on close`() {
        val samples = 1000
        val b = wav(FloatArray(300), FloatArray(700))
        val dataBytes = 2 * samples

        assertEquals(44 + dataBytes, b.capacity())
        assertEquals("RIFF", b.tag(0))
        assertEquals(36 + dataBytes, b.getInt(4))
        assertEquals("WAVE", b.tag(8))
        assertEquals("fmt ", b.tag(12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(24_000, b.getInt(24))
        assertEquals(48_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals("data", b.tag(36))
        assertEquals(dataBytes, b.getInt(40))
    }

    @Test
    fun `an empty file is a valid header with no data`() {
        val b = wav()
        assertEquals(44, b.capacity())
        assertEquals(36, b.getInt(4))
        assertEquals(0, b.getInt(40))
    }

    @Test
    fun `samples counts what was written, across calls`() {
        WavWriter(File(tmp.root, "b.wav")).use { w ->
            assertEquals(0L, w.samples)
            w.write(FloatArray(5))
            w.write(FloatArray(0))
            w.write(FloatArray(7))
            assertEquals(12L, w.samples)
        }
    }

    @Test
    fun `values are scaled to 16 bit and written little endian`() {
        val b = wav(floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f))
        assertEquals(0, b.sample(0))
        assertEquals(32767, b.sample(1))
        assertEquals(-32767, b.sample(2))
        assertEquals(16384, b.sample(3)) // 16383.5 rounds up
        assertEquals(-16383, b.sample(4)) // -16383.5 rounds toward positive infinity
    }

    @Test
    fun `values outside minus one to one are clipped`() {
        val b = wav(floatArrayOf(1.0001f, 2f, 100f, Float.POSITIVE_INFINITY, -1.5f, -100f, Float.NEGATIVE_INFINITY))
        for (i in 0..3) assertEquals(32767, b.sample(i), "sample $i")
        for (i in 4..6) assertEquals(-32767, b.sample(i), "sample $i")
    }

    @Test
    fun `NaN becomes silence`() {
        assertEquals(0, wav(floatArrayOf(Float.NaN)).sample(0))
    }

    @Test
    fun `writing after close is refused and closing twice is harmless`() {
        val w = WavWriter(File(tmp.root, "c.wav"))
        w.write(FloatArray(3))
        w.close()
        w.close()
        assertFailsWith<IllegalStateException> { w.write(FloatArray(1)) }
        assertEquals(44 + 6, File(tmp.root, "c.wav").length().toInt())
    }
}
