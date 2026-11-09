package app.lantern.speech

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 24 kHz mono 16-bit PCM floats into a WAV file: a placeholder header first, the sizes patched on [close].
 * Ported from the spike's WavWriter. Input outside [-1, 1] is clipped.
 */
class WavWriter(private val file: File) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private var closed = false

    /** The samples written so far. */
    val samples: Long get() = dataBytes / BYTES_PER_SAMPLE

    init {
        out.write(ByteArray(HEADER_BYTES))
    }

    fun write(pcm: FloatArray) {
        check(!closed) { "WavWriter is closed" }
        val buf = ByteBuffer.allocate(pcm.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putShort(Math.round(x.coerceIn(-1f, 1f) * 32767f).toShort())
        out.write(buf.array())
        dataBytes += buf.capacity()
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(Phonon.SAMPLE_RATE).putInt(Phonon.SAMPLE_RATE * BYTES_PER_SAMPLE)
            .putShort(BYTES_PER_SAMPLE.toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use { it.write(h.array()) }
    }

    private companion object {
        const val HEADER_BYTES = 44
        const val BYTES_PER_SAMPLE = 2
    }
}
