package app.lantern.model

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against the app's real filesDir/model, so it leaves the model installed and intact, as the app expects.
 * The manifest is read and the checksums computed here, apart from the code under test.
 */
class ModelInstallerTest {
    private class Line(val path: String, val size: Long, val sha256: String)

    private lateinit var context: Context
    private lateinit var manifest: List<Line>

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manifest = context.assets.open("model/manifest.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map {
            val (path, size, sha) = it.trim().split(' ')
            Line(path, size.toLong(), sha)
        }
        assertTrue("manifest lists the model and both voices", manifest.size >= 6)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assertAllMatch(dir: File) {
        for (line in manifest) {
            val file = File(dir, line.path)
            assertTrue("${line.path} exists", file.isFile)
            assertEquals("${line.path} size", line.size, file.length())
            assertEquals("${line.path} sha256", line.sha256, sha256(file))
        }
        assertEquals("no .tmp left behind", emptyList<String>(), dir.walkTopDown().filter { it.name.endsWith(".tmp") }.map { it.path }.toList())
    }

    private fun file(dir: File, name: String) = File(dir, manifest.first { it.path == name }.path)

    @Test
    fun installsEveryManifestFile() {
        assertAllMatch(ModelInstaller.ensureInstalled(context))
    }

    @Test
    fun repairsATruncatedFileACorruptedFileAndAMissingOne() {
        val dir = ModelInstaller.ensureInstalled(context)
        assertAllMatch(dir)

        val truncated = file(dir, "voices/everly.safetensors")
        RandomAccessFile(truncated, "rw").use { it.setLength(it.length() / 2) }

        // Same size, one byte flipped: only the checksum can tell.
        val corrupted = file(dir, "tokenizer.json")
        val size = corrupted.length()
        RandomAccessFile(corrupted, "rw").use {
            it.seek(size / 2)
            val b = it.read()
            it.seek(size / 2)
            it.write(b xor 0xFF)
        }
        assertEquals(size, corrupted.length())

        val missing = file(dir, "voices/trevor.safetensors")
        assertTrue(missing.delete())

        val untouched = file(dir, "model.q8.gguf")
        val untouchedModified = untouched.lastModified()

        assertEquals(dir, ModelInstaller.ensureInstalled(context))

        assertAllMatch(dir)
        assertEquals("a file that was fine is not copied again", untouchedModified, untouched.lastModified())
    }
}
