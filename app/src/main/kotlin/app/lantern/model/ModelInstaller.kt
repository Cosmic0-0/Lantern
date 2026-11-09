package app.lantern.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Phonon loads its model from a folder, not from the APK, so the model and voices ship as assets and are copied to
 * filesDir/model on first launch (docs/decisions/model-delivery.md). assets/model/manifest.txt is written at build time
 * with one line per file: `<path under model/> <size in bytes> <sha256 in hex>`.
 */
object ModelInstaller {
    private const val TAG = "Lantern"
    private const val ASSET_DIR = "model"
    private const val BUFFER_BYTES = 1 shl 18

    internal class Entry(val path: String, val size: Long, val sha256: String)

    /**
     * Makes sure filesDir/model holds every file in the manifest with the right size and SHA-256, copying from assets
     * (via a .tmp file, then a rename) any file that is missing or wrong. Returns the model dir.
     * Blocking and slow on first run (about 65 MB): call it off the main thread. Logs with tag "Lantern" what it copied
     * and how long checking and copying took. Throws IOException if the APK's own copy fails the manifest.
     */
    fun ensureInstalled(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val manifest = context.assets.open("$ASSET_DIR/manifest.txt").use { parseManifest(it.reader().readText()) }
        var checkNanos = 0L
        var copyNanos = 0L
        val copied = mutableListOf<String>()
        for (entry in manifest) {
            val target = File(dir, entry.path)
            val checkStart = System.nanoTime()
            val ok = matches(target, entry)
            checkNanos += System.nanoTime() - checkStart
            if (ok) continue
            val copyStart = System.nanoTime()
            copy(context, entry, target)
            val took = System.nanoTime() - copyStart
            copyNanos += took
            copied += entry.path
            Log.i(TAG, "model copied ${entry.path} bytes=${entry.size} ms=${took / 1_000_000}")
        }
        Log.i(
            TAG,
            "model ready dir=${dir.path} files=${manifest.size} copied=${copied.size} check_ms=${checkNanos / 1_000_000} " +
                "copy_ms=${copyNanos / 1_000_000}" + if (copied.isEmpty()) "" else " copiedFiles=$copied",
        )
        return dir
    }

    internal fun parseManifest(text: String): List<Entry> = text.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.trim().split(' ')
        val size = parts.getOrNull(1)?.toLongOrNull()
        require(parts.size == 3 && size != null && parts[2].length == 64) { "bad manifest line: $line" }
        Entry(parts[0], size, parts[2])
    }

    private fun matches(file: File, entry: Entry): Boolean =
        file.isFile && file.length() == entry.size && sha256(file) == entry.sha256

    private fun copy(context: Context, entry: Entry, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.assets.open("$ASSET_DIR/${entry.path}").use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        bytes += n
                    }
                }
            }
            // The APK's own bytes should match what was recorded at build time; if not, copying again won't help.
            if (bytes != entry.size || hex(digest.digest()) != entry.sha256) {
                throw IOException("assets/$ASSET_DIR/${entry.path} does not match manifest.txt: rebuild the app")
            }
            if (!tmp.renameTo(target)) throw IOException("cannot rename ${tmp.name} to ${target.name}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
