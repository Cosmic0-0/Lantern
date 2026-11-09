package app.lantern.spike.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the player, the session and the generator. One media item per sentence; the item's uri is
 * lantern://sentence/<chapter>/<index>, which SentenceDataSource turns into the generated audio file.
 * Everything here runs on the main thread except the chapter downloads, which post back.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var generator: Generator
    private val main = Handler(Looper.getMainLooper())

    private var startToken = 0 // a newer start replaces an older one still waiting for its download
    private var lastChapter = -1 // chapter of the item playing now
    private var appendedChapter = -1 // highest chapter in the playlist
    private var lastTransitionAt = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("SERVICE", "create")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SentenceDataSource.Factory(this)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener())
        session = MediaSession.Builder(this, player).setCallback(SessionCallback()).build()
        // Readiness reaches the player through SentenceDataSource, which polls for the file,
        // so this callback has nothing to do.
        generator = Generator(this) { _, _ -> }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition("task_removed")
        EventLog.log("SERVICE", "task_removed")
        // Keep going if the listener is mid-book, including while waiting for a sentence to be made.
        val active = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!active) stopSelf()
    }

    override fun onDestroy() {
        savePosition("destroy")
        EventLog.log("SERVICE", "destroy")
        session.release()
        player.release()
        generator.shutdown()
        super.onDestroy()
    }

    // ---- Playlist ----

    private fun itemsFor(chapter: Int): List<MediaItem> {
        val sentences = Chapters.sentences(this, chapter) ?: return emptyList()
        return sentences.mapIndexed { i, text ->
            MediaItem.Builder()
                .setMediaId("c$chapter-s$i")
                .setUri(Uri.parse("lantern://sentence/$chapter/$i"))
                .setMimeType("audio/wav")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Ode of the Brave, chapter $chapter")
                        .setArtist(text.take(80))
                        .build(),
                )
                .build()
        }
    }

    /** Fetches the chapter if needed, then replaces the playlist with it, starting at [sentence]. */
    private fun startAt(chapter: Int, sentence: Int, playWhenReady: Boolean) {
        val token = ++startToken
        EventLog.log("START", "chapter=$chapter sentence=$sentence play=$playWhenReady")
        Thread({
            val ok = Chapters.fetch(this, chapter)
            main.post {
                if (token != startToken) return@post
                val items = if (ok) itemsFor(chapter) else emptyList()
                if (items.isEmpty()) {
                    EventLog.log("START", "failed chapter=$chapter: no text")
                    return@post
                }
                val index = sentence.coerceIn(0, items.lastIndex)
                // Set before the playlist changes, so the first transition does not look like a new chapter.
                lastChapter = chapter
                appendedChapter = chapter
                player.setMediaItems(items, index, 0)
                player.prepare()
                player.playWhenReady = playWhenReady
                generator.update(chapter, index)
                onChapterStarted(chapter)
            }
        }, "lantern-start").start()
    }

    private fun onChapterStarted(chapter: Int) {
        EventLog.log("CHAPTER", "start $chapter")
        AudioCache.prune(this, setOf(chapter, chapter + 1))
        fetchNext(chapter, 0)
    }

    /** Gets the next chapter's text and puts its sentences at the end of the playlist. Retries a minute apart. */
    private fun fetchNext(chapter: Int, attempt: Int) {
        Thread({
            val ok = Chapters.fetch(this, chapter + 1)
            main.post {
                if (lastChapter != chapter) return@post // playback has moved on; its own start fetches what it needs
                if (ok) {
                    if (chapter + 1 > appendedChapter) {
                        val items = itemsFor(chapter + 1)
                        player.addMediaItems(items)
                        appendedChapter = chapter + 1
                        EventLog.log("NEXT", "appended chapter=${chapter + 1} items=${items.size} state=${stateName(player.playbackState)}")
                        if (player.playbackState == Player.STATE_ENDED) {
                            // Playback already ran off the end; carry on into the new chapter.
                            player.seekTo(player.mediaItemCount - items.size, 0)
                        }
                    }
                    generator.textArrived()
                } else if (attempt < MAX_RETRIES) {
                    EventLog.log("NEXT", "fetch of chapter ${chapter + 1} failed, retry ${attempt + 1} of $MAX_RETRIES in 60 s")
                    main.postDelayed({ if (lastChapter == chapter) fetchNext(chapter, attempt + 1) }, RETRY_MS)
                } else {
                    EventLog.log("NEXT", "gave up on chapter ${chapter + 1} after $MAX_RETRIES retries")
                }
            }
        }, "lantern-fetch-next").start()
    }

    // ---- Position ----

    /** Saves the item playing now. Does nothing when there is none, so a stale save cannot erase a good one. */
    private fun savePosition(reason: String) {
        val id = player.currentMediaItem?.mediaId ?: return
        val (chapter, index) = parseId(id) ?: return
        Position.save(this, Pos(chapter, index, player.currentPosition, System.currentTimeMillis()), reason)
    }

    /** The sentence to resume at, by the rule: the saved one, or one earlier after more than 5 minutes away. */
    private fun resumeIndex(saved: Pos): Int {
        val awayMs = System.currentTimeMillis() - saved.savedAt
        val start = if (awayMs > AWAY_MS) maxOf(0, saved.sentence - 1) else saved.sentence
        EventLog.log("RESUME", "chapter=${saved.chapter} from=${saved.sentence} start=$start away_s=${awayMs / 1000}")
        return start
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val m = ID.matchEntire(id) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    // ---- Player events ----

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val now = SystemClock.elapsedRealtime()
            val (chapter, index) = mediaItem?.let { parseId(it.mediaId) } ?: return
            EventLog.log("PLAY", "chapter=$chapter sentence=$index reason=${transitionName(reason)} since_last_ms=${now - lastTransitionAt}")
            lastTransitionAt = now
            Position.save(this@PlaybackService, Pos(chapter, index, 0, System.currentTimeMillis()), "transition")
            generator.update(chapter, index)
            if (chapter != lastChapter) {
                lastChapter = chapter
                onChapterStarted(chapter)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            EventLog.log("STATE", stateName(playbackState))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            EventLog.log("PLAYING", isPlaying.toString())
            if (!isPlaying) savePosition("paused")
        }

        override fun onPlayerError(error: PlaybackException) {
            EventLog.log("ERROR", "${error.errorCodeName} $error cause=${error.cause}")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            EventLog.log("PWR", "$playWhenReady reason=$reason")
        }
    }

    // ---- Session ----

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_RESUME, Bundle.EMPTY))
                        .add(SessionCommand(CMD_START_CHAPTER, Bundle.EMPTY))
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_RESUME -> {
                    val saved = Position.load(this@PlaybackService)
                    if (saved == null) {
                        EventLog.log("RESUME", "nothing saved")
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    }
                    startAt(saved.chapter, resumeIndex(saved), true)
                }
                CMD_START_CHAPTER -> startAt(args.getInt("chapter", 1), 0, true)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** The service was killed and a headset or lock-screen button asked for playback: rebuild from the saved position. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            EventLog.log("RESUME", "request for_playback=$isForPlayback")
            val saved = Position.load(this@PlaybackService)
                ?: return Futures.immediateFailedFuture(IllegalStateException("no saved position"))
            // Only text already on disk; a download here would block the main thread.
            val items = itemsFor(saved.chapter)
            if (items.isEmpty()) return Futures.immediateFailedFuture(IllegalStateException("chapter ${saved.chapter} text not on disk"))
            val start = resumeIndex(saved).coerceIn(0, items.lastIndex)
            lastChapter = -1 // the first transition then starts the chapter, which fetches the next one
            appendedChapter = saved.chapter
            generator.update(saved.chapter, start)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, start, 0))
        }
    }

    private companion object {
        const val CMD_RESUME = "resume"
        const val CMD_START_CHAPTER = "start_chapter"
        const val AWAY_MS = 5 * 60 * 1000L
        const val RETRY_MS = 60_000L
        const val MAX_RETRIES = 5
        val ID = Regex("""c(\d+)-s(\d+)""")

        fun stateName(state: Int) = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }

        fun transitionName(reason: Int) = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "UNKNOWN($reason)"
        }
    }
}
