package app.lantern.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lantern.core.Position
import app.lantern.lantern
import app.lantern.library.Chapter
import app.lantern.playback.Commands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tag of a sentence's row in [ReaderContent]: "sentence-<index>". */
fun sentenceTag(index: Int) = "sentence-$index"

/**
 * The chapter text, one row per sentence, with no state of its own about the player.
 *
 * - [highlighted] is the sentence being spoken, if it is in this chapter. It carries the `selected` semantics.
 * - While [following], the list scrolls to keep [highlighted] in view. A hand on the list calls
 *   `onFollowChange(false)`; the "Back to voice" button, shown while not following, calls `onFollowChange(true)`.
 *   The list's own scrolling never counts as a hand.
 * - After a scroll by hand has settled, [onFirstVisible] gets the first sentence that is fully on screen.
 */
@Composable
fun ReaderContent(
    sentences: List<String>,
    highlighted: Int?,
    following: Boolean,
    onTap: (Int) -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onFirstVisible: (Int) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val latestFollowChange by rememberUpdatedState(onFollowChange)
    val latestFirstVisible by rememberUpdatedState(onFirstVisible)

    LaunchedEffect(listState) {
        var byHand = false
        launch {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) {
                    byHand = true
                    latestFollowChange(false)
                }
            }
        }
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && byHand) {
                byHand = false
                firstFullyVisible(listState)?.let(latestFirstVisible)
            }
        }
    }

    LaunchedEffect(highlighted, following) {
        if (!following || highlighted == null || highlighted !in sentences.indices) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == highlighted }
        val inView = row != null && row.offset >= 0 && row.offset + row.size <= info.viewportSize.height
        // Leave a quarter of the screen above it, so what was just read stays in sight.
        if (!inView) listState.animateScrollToItem(highlighted, -info.viewportSize.height / 4)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 16.dp)) {
            itemsIndexed(sentences, key = { index, _ -> index }) { index, text ->
                SentenceRow(index, text, highlighted = index == highlighted, onClick = { onTap(index) })
            }
        }
        if (!following) {
            ExtendedFloatingActionButton(onClick = { onFollowChange(true) }, Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Text("Back to voice")
            }
        }
    }
}

@Composable
private fun SentenceRow(index: Int, text: String, highlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
        color = if (highlighted) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag(sentenceTag(index))
            .semantics {
                selected = highlighted
                if (highlighted) stateDescription = "Being read"
            }
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The first sentence whose top is on screen, or null before the list is measured. */
internal fun firstFullyVisible(state: LazyListState): Int? {
    val rows = state.layoutInfo.visibleItemsInfo
    return (rows.firstOrNull { it.offset >= 0 } ?: rows.firstOrNull())?.index
}

private sealed interface Load {
    data object Loading : Load
    class Failed(val message: String) : Load
    class Ready(val chapterId: Long, val sentences: List<String>, val listState: LazyListState) : Load
}

/**
 * The reader: [ReaderContent] wired to the library and the player. Opens at the playing sentence when the voice
 * is in this chapter, otherwise at the book's saved position when that is in it.
 */
@Composable
fun ReaderScreen(nav: Nav, bookId: Long, chapterId: Long) {
    val library = LocalContext.current.lantern.library
    val player = LocalPlayer.current
    val controller = player.controller
    val voice = player.state
    val scope = rememberCoroutineScope()
    val chapters by remember(bookId) { library.chapters(bookId) }.collectAsState(initial = emptyList())

    var shown by rememberSaveable { mutableLongStateOf(chapterId) }
    var following by rememberSaveable { mutableStateOf(voice.chapterId == null || voice.chapterId == chapterId) }
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var history by remember { mutableStateOf<List<Position>>(emptyList()) }

    // The chapter of this book the voice is in, and whether it is speaking now.
    val voiceChapter = voice.chapterId?.takeIf { id -> chapters.any { it.id == id } }
    val speaking = voice.isPlaying && voiceChapter != null

    LaunchedEffect(shown, attempt) {
        // A chapter that is already stored comes in at once; the spinner only shows if it takes a moment.
        val spinner = launch {
            delay(SPINNER_DELAY_MS)
            load = Load.Loading
        }
        load = try {
            val sentences = library.sentences(shown)
            val playing = player.state
            val start = when {
                playing.chapterId == shown && playing.sentence != null -> playing.sentence
                else -> library.position(bookId).first()?.takeIf { it.chapterId == shown }?.sentence ?: 0
            }
            Load.Ready(shown, sentences, LazyListState(start.coerceIn(0, sentences.lastIndex)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: e.toString())
        } finally {
            spinner.cancel()
        }
    }

    // The voice went on to another chapter (auto-advance, or a jump): follow it.
    var lastVoiceChapter by remember { mutableStateOf(voiceChapter) }
    LaunchedEffect(voiceChapter) {
        if (following && voiceChapter != null && voiceChapter != lastVoiceChapter) shown = voiceChapter
        lastVoiceChapter = voiceChapter
    }

    // A jump is recorded by the service as the new sentence starts, so look again whenever the sentence changes.
    LaunchedEffect(bookId, voice.chapterId, voice.sentence) { history = library.history(bookId) }

    fun playPause() {
        val c = controller ?: return
        when {
            speaking -> c.pause()
            // Still with the voice: carry on. Scrolled away while paused: start where you are reading
            // (docs/decisions/position-tracking.md), recorded as a jump so Undo goes back to the voice.
            voiceChapter == shown && following -> c.play()
            else -> {
                val first = (load as? Load.Ready)?.let { firstFullyVisible(it.listState) } ?: 0
                Commands.playFrom(c, Position(bookId, shown, first), jump = voiceChapter != null)
                following = true
            }
        }
    }

    fun goTo(target: Chapter) {
        if (speaking) {
            controller?.let { Commands.playFrom(it, Position(bookId, target.id, 0), jump = true) }
            following = true
        } else {
            // Continue from the library should start here next time.
            scope.launch { library.savePosition(Position(bookId, target.id, 0)) }
            if (voiceChapter != null) following = false // the voice stays where it was
        }
        shown = target.id
    }

    fun undo() {
        val c = controller ?: return
        scope.launch {
            val back = library.popHistory(bookId) ?: return@launch
            Commands.playFrom(c, back, jump = false)
            following = true
            shown = back.chapterId
            history = library.history(bookId)
        }
    }

    val at = chapters.indexOfFirst { it.id == shown }
    Page(
        title = chapters.getOrNull(at)?.title.orEmpty(),
        nav = nav,
        bottomBar = {
            ReaderControls(
                canUndo = history.isNotEmpty() && controller != null,
                onUndo = ::undo,
                speaking = speaking,
                canPlay = controller != null,
                onPlayPause = ::playPause,
                onPrevious = if (at > 0) ({ goTo(chapters[at - 1]) }) else null,
                onNext = if (at in 0 until chapters.lastIndex) ({ goTo(chapters[at + 1]) }) else null,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = load) {
                Load.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Getting the chapter…", Modifier.padding(top = 12.dp))
                }
                is Load.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load this chapter: ${state.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt += 1 }, Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                is Load.Ready -> key(state) {
                    ReaderContent(
                        sentences = state.sentences,
                        highlighted = voice.takeIf { it.chapterId == state.chapterId }?.sentence,
                        // With no voice in this book there is nothing to be back to.
                        following = following || voiceChapter == null,
                        onTap = { index ->
                            controller?.let { Commands.playFrom(it, Position(bookId, state.chapterId, index), jump = true) }
                            following = true
                        },
                        onFollowChange = { on ->
                            following = on
                            if (on) voiceChapter?.let { shown = it }
                        },
                        onFirstVisible = { index ->
                            // While the voice is speaking, the service owns the position.
                            if (!speaking) scope.launch { library.savePosition(Position(bookId, state.chapterId, index)) }
                        },
                        listState = state.listState,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderControls(
    canUndo: Boolean,
    onUndo: () -> Unit,
    speaking: Boolean,
    canPlay: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (canUndo) TextButton(onClick = onUndo, Modifier.align(Alignment.End)) { Text("Undo jump") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Text("‹ Chapter") }
                Button(onClick = onPlayPause, enabled = canPlay) { Text(if (speaking) "Pause" else "Play") }
                OutlinedButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("Chapter ›") }
            }
        }
    }
}

private const val SPINNER_DELAY_MS = 200L
