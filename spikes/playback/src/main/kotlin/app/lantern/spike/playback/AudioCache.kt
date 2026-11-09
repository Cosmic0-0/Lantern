package app.lantern.spike.playback

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sentence audio as WAV files in cacheDir/audio/<chapter>/<index>.wav. A .wav is complete; the generator writes .wav.tmp first. */
object AudioCache {
    private fun root(ctx: Context) = File(ctx.applicationContext.cacheDir, "audio")

    fun dir(ctx: Context, chapter: Int) = File(root(ctx), chapter.toString())

    fun file(ctx: Context, chapter: Int, index: Int) = File(dir(ctx, chapter), "$index.wav")

    fun isReady(ctx: Context, chapter: Int, index: Int) = file(ctx, chapter, index).isFile

    /** Deletes the audio of every chapter not in [keep]. */
    fun prune(ctx: Context, keep: Set<Int>) {
        val dirs = root(ctx).listFiles() ?: return
        for (d in dirs) {
            val chapter = d.name.toIntOrNull()
            if (chapter == null || chapter !in keep) {
                val ok = d.deleteRecursively()
                EventLog.log("PRUNE", "dir=${d.name} deleted=$ok")
            }
        }
    }
}

/** Streams 24 kHz mono 16-bit PCM into a WAV file: a placeholder header first, the sizes patched on close. */
internal class WavWriter(private val file: File) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L

    init {
        out.write(ByteArray(HEADER_BYTES))
    }

    fun write(pcm: FloatArray) {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putShort(Math.round(x.coerceIn(-1f, 1f) * 32767f).toShort())
        out.write(buf.array())
        dataBytes += buf.capacity()
    }

    override fun close() {
        out.close()
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24_000).putInt(24_000 * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use { it.write(h.array()) }
    }

    private companion object {
        const val HEADER_BYTES = 44
    }
}
