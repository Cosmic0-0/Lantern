package app.lantern.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lantern.EventLog
import app.lantern.lantern
import app.lantern.playback.Commands
import app.lantern.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the screens need to know about the player. The playback service names its items "s:<chapterId>:<index>"
 * for a sentence and "t:<chapterId>" for a spoken chapter title; anything else is a notice.
 *
 * [isPlaying] is true while playback is on or waiting for audio to be made, so the button says Pause
 * while a sentence is still being generated.
 */
data class PlayerState(
    val loaded: Boolean = false,
    val isPlaying: Boolean = false,
    val chapterId: Long? = null,
    val sentence: Int? = null,
    val chapterTitle: String? = null,
    val bookTitle: String? = null,
)

/** The sleep timer as the service publishes it: when a timed sleep stops playback (epoch ms), or end of chapter. */
data class SleepState(val at: Long? = null, val endOfChapter: Boolean = false) {
    val isSet get() = at != null || endOfChapter
}

/** The activity's [MediaController], connected between onStart and onStop, and the [PlayerState] observed from it. */
class PlayerConnection {
    var controller: MediaController? by mutableStateOf(null)
        private set
    var state: PlayerState by mutableStateOf(PlayerState())
        private set
    var sleep: SleepState by mutableStateOf(SleepState())
        private set

    private val sessionListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            sleep = sleepOf(extras)
        }
    }

    private var future: ListenableFuture<MediaController>? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            state = stateOf(player)
        }
    }

    fun connect(context: Context) {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).setListener(sessionListener).buildAsync()
        future = pending
        pending.addListener({
            val connected = try {
                pending.get()
            } catch (e: Exception) { // released before it connected, or the service refused
                if (future === pending) EventLog.log("PLAYER", "connect failed: $e")
                return@addListener
            }
            connected.addListener(listener)
            controller = connected
            state = stateOf(connected)
            sleep = sleepOf(connected.sessionExtras)
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller?.removeListener(listener)
        controller = null
        state = PlayerState()
        sleep = SleepState()
    }

    private fun sleepOf(extras: Bundle) = SleepState(
        at = extras.getLong(Commands.EXTRA_SLEEP_AT, 0L).takeIf { it > 0 },
        endOfChapter = extras.getBoolean(Commands.EXTRA_SLEEP_END_OF_CHAPTER, false),
    )

    private fun stateOf(player: Player): PlayerState {
        val item = player.currentMediaItem
        val parts = item?.mediaId?.split(':').orEmpty()
        val kind = parts.firstOrNull()
        return PlayerState(
            loaded = item != null,
            isPlaying = player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED,
            chapterId = if (kind == "s" || kind == "t") parts.getOrNull(1)?.toLongOrNull() else null,
            sentence = if (kind == "s") parts.getOrNull(2)?.toIntOrNull() else null,
            chapterTitle = item?.mediaMetadata?.title?.toString(),
            bookTitle = item?.mediaMetadata?.artist?.toString(),
        )
    }
}

val LocalPlayer = staticCompositionLocalOf { PlayerConnection() }

/** A bar at the bottom of every screen but the reader: what is loaded and a play/pause button. Tap it to open the reader there. */
@Composable
fun MiniPlayer(nav: Nav) {
    val player = LocalPlayer.current
    val state = player.state
    val controller = player.controller
    if (!state.loaded || controller == null) return
    val library = LocalContext.current.lantern.library
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            Modifier
                .clickable(enabled = state.chapterId != null) {
                    scope.launch {
                        library.chapter(state.chapterId ?: return@launch)?.let { nav.open(Screen.Reader(it.bookId, it.id)) }
                    }
                }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.chapterTitle.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.bookTitle.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SleepButton(player.sleep, controller)
            TextButton(onClick = { if (state.isPlaying) controller.pause() else controller.play() }) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
        }
    }
}

/** "Sleep", or the minutes left, or "Sleep: chapter". Opens a menu to set, change or cancel the timer. */
@Composable
internal fun SleepButton(sleep: SleepState, controller: MediaController) {
    var open by remember { mutableStateOf(false) }
    // Recounted every 15 s; the label shows whole minutes, rounded up.
    val now by produceState(System.currentTimeMillis(), sleep.at) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val label = when {
        sleep.at != null -> "Sleep ${((sleep.at - now).coerceAtLeast(0) + 59_999) / 60_000} min"
        sleep.endOfChapter -> "Sleep: chapter"
        else -> "Sleep"
    }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun choose(choice: Commands.Sleep?) {
                Commands.sleep(controller, choice)
                open = false
            }
            for (minutes in listOf(15, 30, 45, 60)) {
                DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { choose(Commands.Sleep.Minutes(minutes)) })
            }
            DropdownMenuItem(text = { Text("End of chapter") }, onClick = { choose(Commands.Sleep.EndOfChapter) })
            if (sleep.isSet) DropdownMenuItem(text = { Text("Cancel timer") }, onClick = { choose(null) })
        }
    }
}
