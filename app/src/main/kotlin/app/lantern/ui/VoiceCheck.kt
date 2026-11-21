package app.lantern.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.session.MediaController
import app.lantern.EventLog
import app.lantern.LanternApp
import app.lantern.core.Position
import app.lantern.playback.Commands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Before playback starts on another chapter, asks which voice to use when that chapter is prepared only in a voice
 * other than the current one (docs/decisions/prepared-audio.md). Resuming the loaded chapter never asks, and a
 * chapter playback rolls into keeps the voice already picked. One per activity: [LanternRoot] shows the question.
 */
class VoiceCheck(private val app: LanternApp, private val player: PlayerConnection, private val scope: CoroutineScope) {
    /** [prepared] has the chapter; [current] is the voice set now. [go] starts playback in the voice picked. */
    class Question(val chapterTitle: String, val prepared: String, val current: String, internal val go: (String) -> Unit)

    var question: Question? by mutableStateOf(null)
        private set

    /**
     * Runs [start] once the voice is settled for the chapter at [at], or at once when there is nothing to ask. [start]
     * must send a custom command (Commands.playFrom or continueBook), so it lands after a voice change.
     */
    fun start(at: suspend () -> Position?, start: (MediaController) -> Unit) {
        scope.launch {
            val voice = app.settings.voice
            val position = at()?.takeIf { it.chapterId != player.state.chapterId }
            val other = position?.let {
                try {
                    app.prepared.preparedIn(it, voice)
                } catch (e: Exception) { // never let the check stop playback
                    EventLog.log("VOICE", "check failed: $e")
                    null
                }
            }
            if (position == null || other == null) {
                player.controller?.let(start)
                return@launch
            }
            val title = app.library.chapter(position.chapterId)?.title.orEmpty()
            question = Question(title, other, voice) { picked ->
                question = null
                val c = player.controller ?: return@Question
                if (picked != app.settings.voice) {
                    app.settings.voice = picked
                    Commands.setVoice(c, picked)
                }
                start(c)
            }
        }
    }

    fun dismiss() {
        question = null
    }
}

val LocalVoiceCheck = staticCompositionLocalOf<VoiceCheck> { error("no VoiceCheck") }

@Composable
internal fun VoiceQuestion(check: VoiceCheck) {
    val q = check.question ?: return
    AlertDialog(
        onDismissRequest = check::dismiss,
        title = { Text("Which voice?") },
        text = {
            Text(
                "\"${q.chapterTitle}\" is prepared in ${voiceName(q.prepared)}. In ${voiceName(q.current)} it is made as " +
                    "you listen, and the ${voiceName(q.prepared)} audio stays until you pass the chapter.",
            )
        },
        confirmButton = { TextButton(onClick = { q.go(q.prepared) }) { Text(voiceName(q.prepared)) } },
        dismissButton = { TextButton(onClick = { q.go(q.current) }) { Text(voiceName(q.current)) } },
    )
}

/** "everly" as people read it: "Everly". */
fun voiceName(voice: String): String = voice.replaceFirstChar(Char::titlecase)
