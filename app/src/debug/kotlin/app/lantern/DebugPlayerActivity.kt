package app.lantern

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lantern.core.Position
import app.lantern.library.RuleKind
import app.lantern.playback.Commands
import app.lantern.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

/**
 * Debug builds only: drives the playback service from adb for the step 7 phone checks, through a MediaController
 * as the real screens will. It sends one command and closes; the service keeps playing.
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd continue [--el book 1]
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd play_from --el book 1 --el chapter 2 --ei sentence 40
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd voice --es voice trevor
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd speed --ef speed 1.25
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd pause|play
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd sleep --ei minutes 1
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd sleep_end_of_chapter|sleep_cancel
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd take-focus
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd add-rule --es kind skip|fix --es term T [--es replacement R] [--el book 1]
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd delete-rules
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd preview --es text "Shao Yen"
 * take-focus holds transient audio focus for 20 s, as the dialer does for a call, then lets it go.
 * add-rule without --el book is for all books; it and delete-rules tell the service the rules changed.
 */
class DebugPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val controller = future.get()
            val cmd = intent.getStringExtra("cmd")
            EventLog.log("DEBUG", "command $cmd")
            when (cmd) {
                "continue" -> Commands.continueBook(controller, intent.getLongExtra("book", -1).takeIf { it >= 0 })
                "play_from" -> Commands.playFrom(
                    controller,
                    Position(intent.getLongExtra("book", 1), intent.getLongExtra("chapter", 1), intent.getIntExtra("sentence", 0)),
                    jump = true,
                )
                "voice" -> Commands.setVoice(controller, intent.getStringExtra("voice") ?: "everly")
                "speed" -> controller.setPlaybackSpeed(intent.getFloatExtra("speed", 1f))
                "pause" -> controller.pause()
                "play" -> controller.play()
                "sleep" -> Commands.sleep(controller, Commands.Sleep.Minutes(intent.getIntExtra("minutes", 1)))
                "sleep_end_of_chapter" -> Commands.sleep(controller, Commands.Sleep.EndOfChapter)
                "sleep_cancel" -> Commands.sleep(controller, null)
                "take-focus" -> takeFocus()
                "add-rule" -> lifecycleScope.launch {
                    val kind = if (intent.getStringExtra("kind") == "fix") RuleKind.FIX else RuleKind.SKIP
                    val book = intent.getLongExtra("book", -1).takeIf { it >= 0 }
                    runCatching {
                        lantern.library.addRule(book, kind, intent.getStringExtra("term").orEmpty(), intent.getStringExtra("replacement"))
                    }.onSuccess { id ->
                        EventLog.log("DEBUG", "rule $id added: $kind book=$book")
                        Commands.rulesChanged(controller)
                    }.onFailure { EventLog.log("DEBUG", "rule not added: $it") }
                }
                "delete-rules" -> lifecycleScope.launch {
                    lantern.library.deleteAllRules()
                    EventLog.log("DEBUG", "all rules deleted")
                    Commands.rulesChanged(controller)
                }
                "preview" -> Commands.preview(controller, intent.getStringExtra("text").orEmpty())
                else -> EventLog.log("DEBUG", "unknown command $cmd")
            }
            // Give the command time to arrive before the controller goes away with this screen.
            window.decorView.postDelayed({ controller.release(); finish() }, 1000)
        }, MoreExecutors.directExecutor())
    }

    /** A stand-in for a phone call: transient focus with call audio attributes, held on the app's main thread. */
    private fun takeFocus() {
        val audio = applicationContext.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { EventLog.log("DEBUG", "take-focus change $it") }
            .build()
        val result = audio.requestAudioFocus(request)
        EventLog.log("DEBUG", "take-focus requested result=$result, holding ${FOCUS_HOLD_MS / 1000} s")
        Handler(Looper.getMainLooper()).postDelayed({
            audio.abandonAudioFocusRequest(request)
            EventLog.log("DEBUG", "take-focus abandoned")
        }, FOCUS_HOLD_MS)
    }

    private companion object {
        const val FOCUS_HOLD_MS = 20_000L
    }
}
