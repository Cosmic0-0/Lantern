package app.lantern.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import app.lantern.EventLog
import app.lantern.lantern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Book covers, downloaded once into [dir] and decoded small enough for a library tile (docs/decisions/ui-design.md).
 * A few lines of HttpURLConnection and BitmapFactory instead of an image library: a library of a handful of books
 * needs no more. A failed download isn't kept, so it is tried again next time the app starts.
 */
class Covers(private val dir: File) {
    private val memory = LruCache<String, ImageBitmap>(MEMORY_COVERS)
    private val failed = HashSet<String>() // this run only
    private val lock = Mutex() // one load at a time, so two tiles of the same book never write the same file at once

    /** The cover if it is already decoded, so a tile shows it on its first frame. */
    fun cached(url: String?): ImageBitmap? = url?.let { memory.get(it) }

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(url) }
    }

    private fun loadLocked(url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        if (url in failed) return null
        val file = File(dir, sha256(url))
        return try {
            if (!file.isFile) download(url, file)
            decode(file)?.also { memory.put(url, it) } ?: throw IOException("not an image")
        } catch (e: Exception) {
            EventLog.log("COVER", "failed $url: $e")
            file.delete()
            failed += url
            null
        }
    }

    private fun download(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val type = connection.contentType.orEmpty()
            if (!type.startsWith("image/")) throw IOException("got $type, not an image")
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("cover larger than $MAX_BYTES bytes")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(file)) throw IOException("cannot rename ${tmp.name}")
        } finally {
            connection.disconnect()
        }
    }

    /** Decodes at no more than [MAX_WIDTH] pixels wide, the largest a tile is drawn on this phone. */
    private fun decode(file: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MEMORY_COVERS = 48
        private const val TIMEOUT_MS = 15_000
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val MAX_WIDTH = 360
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
    }
}

/**
 * A book's cover, filling [modifier]'s box and cropped to it. Until it loads, or when there is none (pasted text, a
 * failed download), the book's first letter on a dark red tile.
 */
@Composable
fun BookCover(title: String, url: String?, modifier: Modifier = Modifier, letterSize: TextUnit = MaterialTheme.typography.displaySmall.fontSize) {
    val covers = LocalContext.current.lantern.covers
    val image by produceState(covers.cached(url), url) { if (url != null) value = covers.load(url) }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "",
                style = MaterialTheme.typography.displaySmall.merge(TextStyle(fontSize = letterSize)),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
