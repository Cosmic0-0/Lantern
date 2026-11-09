package app.lantern

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.lantern.core.Position
import app.lantern.data.LanternDatabase
import app.lantern.library.Library
import app.lantern.library.SourcesChapterSource
import app.lantern.model.ModelInstaller
import app.lantern.sources.Sources
import app.lantern.speech.Generator
import app.lantern.speech.SentenceAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Debug builds only: the step 6 phone check (docs/phase-2-plan.md). Installs the model, adds Ode of the Brave from
 * AllNovelFull, fetches one chapter and generates all of it on screen, then logs the overall RTF under tag "Lantern".
 *   adb shell am start -n app.lantern/.DebugActivity --es voice trevor --ei chapter 1
 */
class DebugActivity : ComponentActivity() {
    private val status = mutableStateOf("starting")
    private var sources: Sources? = null
    private var generator: Generator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // timing runs stay on screen
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(24.dp)) { Text(status.value) } }
            }
        }
        val voice = intent.getStringExtra("voice") ?: "everly"
        val chapterNumber = intent.getIntExtra("chapter", 1)
        lifecycleScope.launch {
            try {
                run(voice, chapterNumber)
            } catch (e: Exception) {
                say("failed: $e")
            }
        }
    }

    private suspend fun run(voice: String, chapterNumber: Int) {
        say("installing model")
        val modelDir = withContext(Dispatchers.IO) { ModelInstaller.ensureInstalled(this@DebugActivity) }

        val src = Sources(log = { Log.d(TAG, it) }).also { sources = it }
        val library = Library(LanternDatabase.get(this), SourcesChapterSource(src))
        say("searching")
        val listing = src.search("Ode of the Brave").toList()
            .first { it.first == "anf.net" }.second.getOrThrow()
            .first { it.title.contains("Ode of the Brave", ignoreCase = true) }
        val bookId = library.add(listing)
        val chapter = library.chapters(bookId).first()[chapterNumber - 1]

        say("fetching ${chapter.title}")
        var start = System.nanoTime()
        val sentences = library.sentences(chapter.id)
        val hash = library.chapter(chapter.id)!!.textHash!!
        say("fetched ${sentences.size} sentences in ${ms(start)} ms")

        val audio = SentenceAudio(File(cacheDir, "audio"))
        start = System.nanoTime()
        val gen = Generator(library, audio, { modelDir }, voice) { Log.i(TAG, it) }.also { generator = it }
        gen.update(Position(bookId, chapter.id, 0))
        while (true) {
            val ready = sentences.indices.count { audio.isReady(voice, hash, it) }
            say("$voice: $ready of ${sentences.size} sentences made")
            if (ready == sentences.size) break
            delay(2000)
        }
        val wallMs = ms(start)
        val audioMs = sentences.indices.sumOf { (audio.file(voice, hash, it).length() - 44) / 2 } * 1000.0 / 24_000
        say(
            "done voice=$voice sentences=${sentences.size} wall_ms=$wallMs audio_ms=${"%.0f".format(Locale.ROOT, audioMs)} " +
                "rtf=${"%.3f".format(Locale.ROOT, wallMs / audioMs)} hash=$hash",
        )
    }

    override fun onDestroy() {
        generator?.close()
        sources?.close()
        super.onDestroy()
    }

    private fun say(text: String) {
        status.value = text
        Log.i(TAG, "DEBUG $text")
    }

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        const val TAG = "Lantern"
    }
}
