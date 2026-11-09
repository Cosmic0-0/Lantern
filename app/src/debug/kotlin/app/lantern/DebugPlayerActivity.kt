package app.lantern

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lantern.core.Position
import app.lantern.playback.Commands
import app.lantern.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors

/**
 * Debug builds only: drives the playback service from adb for the step 7 phone checks, through a MediaController
 * as the real screens will. It sends one command and closes; the service keeps playing.
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd continue [--el book 1]
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd play_from --el book 1 --el chapter 2 --ei sentence 40
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd voice --es voice trevor
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd speed --ef speed 1.25
 *   adb shell am start -n app.lantern/.DebugPlayerActivity --es cmd pause|play
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
                else -> EventLog.log("DEBUG", "unknown command $cmd")
            }
            // Give the command time to arrive before the controller goes away with this screen.
            window.decorView.postDelayed({ controller.release(); finish() }, 1000)
        }, MoreExecutors.directExecutor())
    }
}
