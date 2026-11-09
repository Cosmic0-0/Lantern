package app.lantern.spike.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Serves lantern://sentence/<chapter>/<index> from the audio cache. When the sentence's file is not there
 * yet, open() waits for it, which leaves the player in BUFFERING instead of ending the playlist.
 */
@OptIn(UnstableApi::class)
class SentenceDataSource(private val ctx: Context) : DataSource {
    private val file = FileDataSource()

    override fun addTransferListener(transferListener: TransferListener) = file.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val parts = uri.pathSegments
        val chapter = parts.getOrNull(0)?.toIntOrNull()
        val index = parts.getOrNull(1)?.toIntOrNull()
        if (uri.scheme != "lantern" || uri.host != "sentence" || chapter == null || index == null) {
            throw IOException("not a sentence uri: $uri")
        }
        val wav = AudioCache.file(ctx, chapter, index)
        if (!wav.isFile) {
            EventLog.log("WAIT", "start chapter=$chapter sentence=$index")
            val start = SystemClock.elapsedRealtime()
            try {
                while (!wav.isFile) Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                // Media3 interrupts a load it cancels.
                EventLog.log("WAIT", "cancelled chapter=$chapter sentence=$index waited_ms=${SystemClock.elapsedRealtime() - start}")
                throw InterruptedIOException("cancelled while waiting for sentence $chapter/$index")
            }
            EventLog.log("WAIT", "end chapter=$chapter sentence=$index waited_ms=${SystemClock.elapsedRealtime() - start}")
        }
        return file.open(dataSpec.withUri(Uri.fromFile(wav)))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

    override fun getUri(): Uri? = file.uri

    override fun close() = file.close()

    @OptIn(UnstableApi::class)
    class Factory(context: Context) : DataSource.Factory {
        private val ctx = context.applicationContext
        override fun createDataSource(): DataSource = SentenceDataSource(ctx)
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
