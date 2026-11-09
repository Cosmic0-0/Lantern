package app.lantern.playback

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.lantern.EventLog
import app.lantern.core.Position
import app.lantern.core.Resume
import app.lantern.lantern
import app.lantern.library.Chapter
import app.lantern.model.ModelInstaller
import app.lantern.speech.Generator
import app.lantern.speech.SentenceAudio
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the player, the session and the generator (Spike 2, docs/decisions/position-tracking.md). One media item per
 * sentence, plus short phrase items: a spoken chapter title before an appended chapter, and notices such as "No more
 * chapters yet". The position is saved on every sentence change, pause, task removal and destroy. When a chapter
 * starts, the next one is fetched and appended, so playback runs on with the screen off.
 *
 * Runs on the main thread; Library calls suspend in [scope].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var generator: Generator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library get() = lantern.library
    private val settings get() = lantern.settings

    private var bookId = -1L
    private var bookTitle = ""
    private var currentChapterId = -1L // chapter of the sentence playing now
    private val appended = mutableListOf<Long>() // chapters in the playlist, in order
    private var titlePhrase: String? = null // the appended next chapter's spoken title
    private var notices = listOf<String>()
    private var startJob: Job? = null
    private var nextJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        EventLog.log("SERVICE", "create")
        val audio = SentenceAudio(File(cacheDir, "audio"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SentenceDataSource.Factory(audio)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setPlaybackSpeed(settings.speed)
        player.addListener(PlayerListener())
        session = MediaSession.Builder(this, player).setCallback(SessionCallback()).build()
        generator = Generator(library, audio, { ModelInstaller.ensureInstalled(this) }, settings.voice) {
            EventLog.log("SPEECH", it)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePositionNow("task_removed")
        // Keep going if you are mid-book, including while waiting for a sentence to be made.
        val active = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!active) stopSelf()
    }

    override fun onDestroy() {
        savePositionNow("destroy")
        EventLog.log("SERVICE", "destroy")
        scope.cancel()
        session.release()
        player.release()
        generator.close()
        super.onDestroy()
    }

    // ---- Starting ----

    /** Replaces the playlist with [position]'s chapter, fetching its text first if needed. */
    private fun startAt(position: Position, play: Boolean) {
        EventLog.log("START", "book=${position.bookId} chapter=${position.chapterId} sentence=${position.sentence} play=$play")
        startJob?.cancel()
        startJob = scope.launch {
            val book = library.book(position.bookId) ?: return@launch EventLog.log("START", "no book ${position.bookId}")
            // A fetch takes seconds, and a service that isn't playing yet is destroyed as soon as the screen that
            // asked lets go of it. Saying so starts playback at once, which keeps the service in the foreground.
            if (library.chapter(position.chapterId)?.textHash == null) say(LOADING, play)
            val sentences = try {
                library.sentences(position.chapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("START", "failed: $e")
                return@launch say("Couldn't load this chapter.", play)
            }
            val chapter = library.chapter(position.chapterId) ?: return@launch
            bookId = book.id
            bookTitle = book.title
            currentChapterId = chapter.id // set before the playlist changes, so its first transition isn't a new chapter
            appended.clear()
            appended += chapter.id
            titlePhrase = null
            notices = emptyList()
            syncPhrases()
            val index = position.sentence.coerceIn(0, sentences.lastIndex)
            player.setMediaItems(itemsFor(chapter, sentences.size, spokenTitle = false), index, 0)
            player.prepare()
            player.playWhenReady = play
            generator.update(Position(book.id, chapter.id, index))
            onChapterStarted(chapter)
        }
    }

    /** Fetches the chapter after [chapter] and appends it, with a spoken title, or appends a notice saying why not. */
    private fun onChapterStarted(chapter: Chapter) {
        EventLog.log("CHAPTER", "start ${chapter.id} \"${chapter.title}\" ${EventLog.state(this)}")
        nextJob?.cancel()
        nextJob = scope.launch {
            val next = library.nextChapter(chapter.id) ?: return@launch appendNotice(NO_MORE_CHAPTERS)
            if (next.id in appended) return@launch
            for (attempt in 1..MAX_TRIES) {
                try {
                    val sentences = library.sentences(next.id)
                    appendChapter(library.chapter(next.id) ?: return@launch, sentences)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    EventLog.log("NEXT", "fetch of chapter ${next.id} failed, try $attempt of $MAX_TRIES: $e")
                    if (attempt < MAX_TRIES) delay(RETRY_MS)
                }
            }
            appendNotice(FETCH_FAILED)
        }
    }

    private fun appendChapter(chapter: Chapter, sentences: List<String>) {
        // Most sites open the text with its own heading ("Chapter 2: ..."); then the title isn't said twice.
        val spokenTitle = sentences.firstOrNull()?.startsWith(chapter.title, ignoreCase = true) != true
        val items = itemsFor(chapter, sentences.size, spokenTitle)
        titlePhrase = chapter.title.takeIf { spokenTitle }
        syncPhrases()
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItems(items)
        appended += chapter.id
        generator.textArrived()
        EventLog.log("NEXT", "appended chapter=${chapter.id} items=${items.size} ended=$wasEnded ${EventLog.state(this)}")
        // Playback already ran off the end while the chapter was being fetched: carry on into it.
        if (wasEnded) player.seekTo(player.mediaItemCount - items.size, 0)
    }

    /** Ends the playlist with [text] spoken. Playback stops after it, at the last sentence. */
    private fun appendNotice(text: String) {
        EventLog.log("NOTICE", text)
        notices = notices + text
        syncPhrases()
        player.addMediaItem(phraseItem(NOTICE_ID, text, chapterTitle = null))
    }

    /** Says [text] on its own, for when there is no chapter to play. */
    private fun say(text: String, play: Boolean) {
        notices = listOf(text)
        syncPhrases()
        player.setMediaItem(phraseItem(NOTICE_ID, text, chapterTitle = null))
        player.prepare()
        player.playWhenReady = play
    }

    private fun syncPhrases() = generator.setPhrases(listOfNotNull(titlePhrase) + notices)

    // ---- Items ----

    private fun itemsFor(chapter: Chapter, count: Int, spokenTitle: Boolean): List<MediaItem> {
        val hash = requireNotNull(chapter.textHash) { "chapter ${chapter.id} has no text" }
        val voice = settings.voice
        val items = ArrayList<MediaItem>(count + 1)
        if (spokenTitle) items += phraseItem("$TITLE_PREFIX${chapter.id}", chapter.title, chapter.title)
        for (i in 0 until count) {
            items += MediaItem.Builder()
                .setMediaId("$SENTENCE_PREFIX${chapter.id}:$i")
                .setUri(SentenceDataSource.uri(voice, hash, i))
                .setMimeType("audio/wav")
                .setMediaMetadata(metadata(chapter.title))
                .build()
        }
        return items
    }

    private fun phraseItem(id: String, text: String, chapterTitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(SentenceDataSource.uri(settings.voice, Generator.phraseKey(text), 0))
        .setMimeType("audio/wav")
        .setMediaMetadata(metadata(chapterTitle ?: text))
        .build()

    private fun metadata(chapterTitle: String) = MediaMetadata.Builder()
        .setTitle(chapterTitle)
        .setArtist(bookTitle)
        .setAlbumTitle(bookTitle)
        .build()

    /** The (chapter, sentence) of a sentence item's id, or null for a phrase. */
    private fun parse(mediaId: String?): Pair<Long, Int>? {
        if (mediaId == null || !mediaId.startsWith(SENTENCE_PREFIX)) return null
        val (chapter, index) = mediaId.removePrefix(SENTENCE_PREFIX).split(':')
        return chapter.toLong() to index.toInt()
    }

    private fun currentPosition(): Position? =
        parse(player.currentMediaItem?.mediaId)?.let { (chapter, index) -> Position(bookId, chapter, index) }

    /** Drops the items before [chapterId]'s first one: earlier chapters and the title just spoken. */
    private fun trimBefore(chapterId: Long) {
        val first = (0 until player.mediaItemCount).firstOrNull { parse(player.getMediaItemAt(it).mediaId)?.first == chapterId }
        if (first != null && first > 0) player.removeMediaItems(0, first)
        appended.retainAll(appended.dropWhile { it != chapterId }.toSet())
    }

    // ---- Position ----

    private fun savePosition(reason: String) {
        val p = currentPosition() ?: return
        EventLog.log("SAVE", "chapter=${p.chapterId} sentence=${p.sentence} reason=$reason")
        scope.launch { library.savePosition(p) }
    }

    private fun savePositionNow(reason: String) {
        val p = currentPosition() ?: return
        EventLog.log("SAVE", "chapter=${p.chapterId} sentence=${p.sentence} reason=$reason now")
        library.savePositionNow(p)
    }

    /** Where [bookId] continues: its saved position by the resume rule, or the start of its first chapter. */
    private suspend fun continuePosition(bookId: Long): Position? {
        val saved = library.position(bookId).first()
        if (saved == null) return library.firstChapter(bookId)?.let { Position(bookId, it.id, 0) }
        val awayMs = System.currentTimeMillis() - (library.savedAt(bookId) ?: System.currentTimeMillis())
        return Resume.startAt(saved, awayMs).also {
            EventLog.log("RESUME", "book=$bookId chapter=${saved.chapterId} from=${saved.sentence} start=${it.sentence} away_s=${awayMs / 1000}")
        }
    }

    // ---- Player events ----

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val (chapterId, index) = parse(mediaItem?.mediaId) ?: return
            val p = Position(bookId, chapterId, index)
            EventLog.log("PLAY", "chapter=$chapterId sentence=$index reason=$reason")
            scope.launch { library.savePosition(p) }
            generator.update(p)
            if (chapterId != currentChapterId) {
                currentChapterId = chapterId
                trimBefore(chapterId)
                titlePhrase = null
                notices = emptyList()
                syncPhrases()
                scope.launch { library.chapter(chapterId)?.let(::onChapterStarted) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            EventLog.log("STATE", "$playbackState ${EventLog.state(this@PlaybackService)}")
            // After a notice the playlist ends; go back to the last sentence, paused, so play starts there again.
            if (playbackState == Player.STATE_ENDED && player.currentMediaItem?.mediaId == NOTICE_ID) {
                val last = (player.mediaItemCount - 1 downTo 0).firstOrNull { parse(player.getMediaItemAt(it).mediaId) != null }
                if (last != null) {
                    player.pause()
                    player.seekTo(last, 0)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            EventLog.log("PLAYING", "$isPlaying")
            if (!isPlaying) savePosition("paused")
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            settings.speed = playbackParameters.speed
        }

        override fun onPlayerError(error: PlaybackException) {
            EventLog.log("ERROR", "${error.errorCodeName} $error cause=${error.cause}")
        }
    }

    // ---- Session ----

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(Commands.PLAY_FROM, Bundle.EMPTY))
                        .add(SessionCommand(Commands.CONTINUE, Bundle.EMPTY))
                        .add(SessionCommand(Commands.SET_VOICE, Bundle.EMPTY))
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
                Commands.PLAY_FROM -> {
                    val to = Commands.position(args)
                    val from = currentPosition()
                    if (args.getBoolean(Commands.ARG_JUMP) && from != null && from.bookId == to.bookId && from != to) {
                        scope.launch { library.recordJump(from) }
                    }
                    startAt(to, play = true)
                }
                Commands.CONTINUE -> scope.launch {
                    val id = args.getLong(Commands.ARG_BOOK, -1).takeIf { it >= 0 } ?: library.lastPlayed()?.bookId
                    val p = id?.let { continuePosition(it) } ?: return@launch EventLog.log("RESUME", "nothing to continue")
                    startAt(p, play = true)
                }
                Commands.SET_VOICE -> {
                    val voice = args.getString(Commands.ARG_VOICE) ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    settings.voice = voice
                    generator.setVoice(voice)
                    // Item addresses name the voice, so rebuild the playlist from the sentence playing now.
                    currentPosition()?.let { startAt(it, player.playWhenReady) }
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** A headset or lock-screen play after a kill or reboot: rebuild the last-played book from its saved position. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            EventLog.log("RESUME", "request for_playback=$isForPlayback")
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val last = library.lastPlayed() ?: throw IllegalStateException("nothing played yet")
                    val start = continuePosition(last.bookId)!!
                    val book = library.book(start.bookId)!!
                    val count = library.sentences(start.chapterId).size // stored already: it was playing
                    val chapter = library.chapter(start.chapterId)!!
                    bookId = book.id
                    bookTitle = book.title
                    currentChapterId = -1 // the first transition then starts the chapter, which fetches the next one
                    appended.clear()
                    appended += chapter.id
                    generator.update(start)
                    result.set(MediaSession.MediaItemsWithStartPosition(itemsFor(chapter, count, spokenTitle = false), start.sentence, 0))
                } catch (e: Exception) {
                    EventLog.log("RESUME", "failed: $e")
                    result.setException(e)
                }
            }
            return result
        }
    }

    private companion object {
        const val SENTENCE_PREFIX = "s:"
        const val TITLE_PREFIX = "t:"
        const val NOTICE_ID = "notice"
        const val LOADING = "Loading chapter."
        const val NO_MORE_CHAPTERS = "No more chapters yet."
        const val FETCH_FAILED = "Couldn't load the next chapter."
        const val RETRY_MS = 60_000L
        const val MAX_TRIES = 5
    }
}
