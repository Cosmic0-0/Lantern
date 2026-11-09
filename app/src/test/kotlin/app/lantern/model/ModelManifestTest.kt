package app.lantern.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelManifestTest {
    private val sha = "a".repeat(64)

    @Test
    fun `parses path size and sha256 per line, ignoring blank lines`() {
        val entries = ModelInstaller.parseManifest("config.json 2851 $sha\n\nvoices/everly.safetensors 256136 $sha\n")
        assertEquals(listOf("config.json", "voices/everly.safetensors"), entries.map { it.path })
        assertEquals(listOf(2851L, 256136L), entries.map { it.size })
        assertEquals(listOf(sha, sha), entries.map { it.sha256 })
    }

    @Test
    fun `an empty manifest has no entries`() {
        assertEquals(0, ModelInstaller.parseManifest("").size)
    }

    @Test
    fun `a malformed line is refused`() {
        for (bad in listOf("config.json", "config.json 12", "config.json twelve $sha", "config.json 12 abc", "a b c d")) {
            assertFailsWith<IllegalArgumentException>(bad) { ModelInstaller.parseManifest(bad) }
        }
    }
}
