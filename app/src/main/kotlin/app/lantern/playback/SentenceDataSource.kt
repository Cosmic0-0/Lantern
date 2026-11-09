package app.lantern.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import app.lantern.EventLog
import app.lantern.speech.SentenceAudio
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Serves lantern://sentence/<voice>/<key>/<index> from [SentenceAudio], where key is a chapter's text hash or a
 * phrase key. When the file isn't made yet, open() waits for it, which leaves the player in BUFFERING instead of
 * ending the playlist (Spike 2).
 */
@OptIn(UnstableApi::class)
class SentenceDataSource(private val audio: SentenceAudio) : DataSource {
    private val file = FileDataSource()

    override fun addTransferListener(transferListener: TransferListener) = file.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val parts = uri.pathSegments
        val index = parts.getOrNull(2)?.toIntOrNull()
        if (uri.scheme != SCHEME || uri.host != HOST || parts.size != 3 || index == null) {
            throw IOException("not a sentence uri: $uri")
        }
        val wav = audio.file(parts[0], parts[1], index)
        if (!wav.isFile) {
            val start = SystemClock.elapsedRealtime()
            try {
                while (!wav.isFile) Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                // Media3 interrupts a load it cancels.
                EventLog.log("WAIT", "cancelled $uri waited_ms=${SystemClock.elapsedRealtime() - start}")
                throw InterruptedIOException("cancelled while waiting for $uri")
            }
            EventLog.log("WAIT", "$uri waited_ms=${SystemClock.elapsedRealtime() - start}")
        }
        return file.open(dataSpec.withUri(Uri.fromFile(wav)))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

    override fun getUri(): Uri? = file.uri

    override fun close() = file.close()

    class Factory(private val audio: SentenceAudio) : DataSource.Factory {
        override fun createDataSource(): DataSource = SentenceDataSource(audio)
    }

    companion object {
        private const val SCHEME = "lantern"
        private const val HOST = "sentence"
        private const val POLL_MS = 50L

        fun uri(voice: String, key: String, index: Int): Uri = Uri.parse("$SCHEME://$HOST/$voice/$key/$index")
    }
}
