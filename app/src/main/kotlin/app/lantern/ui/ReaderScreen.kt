package app.lantern.ui

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lantern.core.Position
import app.lantern.core.Sentence
import app.lantern.lantern
import app.lantern.library.Chapter
import app.lantern.playback.Commands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tag of the paragraph row in [ReaderContent] that starts with sentence [index]: "sentence-<index>". In a chapter
 * stored before paragraphs (one sentence per paragraph), that is the sentence's own row.
 */
fun sentenceTag(index: Int) = "sentence-$index"

/** A run of sentences shown as one row: their indexes, the joined text, and where each sentence starts in it. */
internal class Paragraph(val sentences: IntRange, val text: String, val starts: IntArray) {
    /** The sentence at character [offset] of [text]. */
    fun sentenceAt(offset: Int): Int = sentences.first + (starts.indexOfLast { it <= offset }.coerceAtLeast(0))

    fun startOf(sentence: Int): Int = starts[sentence - sentences.first]

    /** Exclusive: the joining space after [sentence] is not part of it. */
    fun endOf(sentence: Int): Int = if (sentence == sentences.last) text.length else starts[sentence - sentences.first + 1] - 1
}

/** [sentences] grouped into rows: each run of adjacent sentences with the same paragraph number, joined by a space. */
internal fun paragraphsOf(sentences: List<Sentence>): List<Paragraph> {
    val rows = ArrayList<Paragraph>()
    var first = 0
    while (first < sentences.size) {
        var last = first
        while (last + 1 < sentences.size && sentences[last + 1].paragraph == sentences[first].paragraph) last++
        val starts = IntArray(last - first + 1)
        val text = StringBuilder()
        for (i in first..last) {
            if (i > first) text.append(' ')
            starts[i - first] = text.length
            text.append(sentences[i].text)
        }
        rows += Paragraph(first..last, text.toString(), starts)
        first = last + 1
    }
    return rows
}

/**
 * The chapter text, one row per paragraph, with no state of its own about the player.
 *
 * - [highlighted] is the sentence being spoken, if it is in this chapter. Its paragraph's row carries the `selected`
 *   semantics. Sentences in [skipped] are shown dimmed (docs/decisions/speech-rules.md).
 * - Tapping a sentence calls [onTap] with its index; pressing and holding it calls [onLongPress] with the same index.
 * - While [following], the list scrolls to keep [highlighted] in view, inside a long paragraph too. A hand on the
 *   list calls `onFollowChange(false)`; the "Back to voice" button, shown while not following, calls
 *   `onFollowChange(true)`. The list's own scrolling never counts as a hand.
 * - After a scroll by hand has settled, [onFirstVisible] gets the first sentence whose first line is fully on screen.
 */
@Composable
fun ReaderContent(
    sentences: List<Sentence>,
    highlighted: Int?,
    following: Boolean,
    onTap: (Int) -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onFirstVisible: (Int) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    skipped: Set<Int> = emptySet(),
    onLongPress: (Int) -> Unit = {},
) {
    val latestFollowChange by rememberUpdatedState(onFollowChange)
    val latestFirstVisible by rememberUpdatedState(onFirstVisible)
    val paragraphs = remember(sentences) { paragraphsOf(sentences) }
    // Each row's text layout, by row index, for taps, following inside a paragraph and the first visible sentence.
    // A plain map: a new layout must not cause another composition.
    val layouts = remember(sentences) { HashMap<Int, TextLayoutResult>() }
    val padTop = with(LocalDensity.current) { ROW_PADDING_V.roundToPx() }

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
                firstFullyVisible(listState, paragraphs, layouts, padTop)?.let(latestFirstVisible)
            }
        }
    }

    LaunchedEffect(highlighted, following) {
        if (!following || highlighted == null || highlighted !in sentences.indices) return@LaunchedEffect
        val row = paragraphs.indexOfFirst { highlighted in it.sentences }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        // A row that hasn't been laid out yet has no text layout: scroll to it, then look again with its layout.
        repeat(2) {
            val info = listState.layoutInfo
            val viewport = info.viewportSize.height
            val item = info.visibleItemsInfo.firstOrNull { it.index == row }
            val layout = layouts[row]
            val p = paragraphs[row]
            val top = padTop + (layout?.let { it.getLineTop(it.getLineForOffset(p.startOf(highlighted))) }?.toInt() ?: 0)
            val bottom = padTop + (layout?.let { it.getLineBottom(it.getLineForOffset(p.endOf(highlighted))) }?.toInt() ?: (item?.size ?: 0))
            if (item != null && item.offset + top >= 0 && item.offset + bottom <= viewport) return@LaunchedEffect
            // Leave a quarter of the screen above it, so what was just read stays in sight.
            listState.animateScrollToItem(row, top - viewport / 4)
            if (layout != null) return@LaunchedEffect
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 16.dp)) {
            itemsIndexed(paragraphs, key = { _, p -> p.sentences.first }) { row, p ->
                ParagraphRow(
                    p,
                    highlighted = highlighted?.takeIf { it in p.sentences },
                    skipped = skipped,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    onLayout = { layouts[row] = it },
                )
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
private fun ParagraphRow(
    p: Paragraph,
    highlighted: Int?,
    skipped: Set<Int>,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val text: AnnotatedString = remember(p, highlighted, skipped, colors) {
        buildAnnotatedString {
            append(p.text)
            for (s in p.sentences) {
                val style = when {
                    s == highlighted -> SpanStyle(color = colors.onPrimaryContainer, background = colors.primaryContainer)
                    s in skipped -> SpanStyle(color = colors.onSurface.copy(alpha = 0.45f))
                    else -> continue
                }
                addStyle(style, p.startOf(s), p.endOf(s))
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val latestTap by rememberUpdatedState(onTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
        color = colors.onSurface,
        onTextLayout = {
            layout = it
            onLayout(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(sentenceTag(p.sentences.first))
            .semantics {
                selected = highlighted != null
                if (highlighted != null) stateDescription = "Being read"
                // Accessibility services click the row as a whole: start from the sentence being read, or the first.
                onClick { onTap(highlighted ?: p.sentences.first); true }
                onLongClick("Add a rule") { onLongPress(highlighted ?: p.sentences.first); true }
            }
            .padding(horizontal = 16.dp, vertical = ROW_PADDING_V)
            // After the padding, so tap positions are in the text layout's own coordinates.
            .pointerInput(p) {
                // A tap and a long-press on the same spot name the same sentence.
                fun sentenceAt(at: Offset): Int = layout?.let { p.sentenceAt(it.getOffsetForPosition(at)) } ?: p.sentences.first
                detectTapGestures(
                    onTap = { at -> latestTap(sentenceAt(at)) },
                    onLongPress = { at -> latestLongPress(sentenceAt(at)) },
                )
            },
    )
}

/**
 * The first sentence whose first line is fully on screen, or null before the list is measured. A row whose text
 * layout isn't known yet counts by its top only.
 */
internal fun firstFullyVisible(state: LazyListState, paragraphs: List<Paragraph>, layouts: Map<Int, TextLayoutResult>, padTop: Int): Int? {
    val rows = state.layoutInfo.visibleItemsInfo
    if (rows.isEmpty()) return null
    for (row in rows) {
        val p = paragraphs.getOrNull(row.index) ?: continue
        val layout = layouts[row.index]
        if (layout == null) {
            if (row.offset >= 0) return p.sentences.first
            continue
        }
        for (s in p.sentences) {
            val top = row.offset + padTop + layout.getLineTop(layout.getLineForOffset(p.startOf(s))).toInt()
            if (top >= 0) return s
        }
    }
    return paragraphs.getOrNull(rows.first().index)?.sentences?.first
}

private sealed interface Load {
    data object Loading : Load
    class Failed(val message: String) : Load

    /**
     * [start] is where you are reading until a scroll by hand reports another sentence. [skipped] changes when a rule
     * is saved, and nothing else about the chapter does, so the scroll position and [start] stay.
     */
    class Ready(
        val chapterId: Long,
        val sentences: List<Sentence>,
        skipped: Set<Int>,
        val start: Int,
        val listState: LazyListState,
    ) : Load {
        var skipped: Set<Int> by mutableStateOf(skipped)
    }
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
            val text = library.chapterText(shown)
            val playing = player.state
            val start = when {
                playing.chapterId == shown && playing.sentence != null -> playing.sentence
                else -> library.position(bookId).first()?.takeIf { it.chapterId == shown }?.sentence ?: 0
            }.coerceIn(0, text.sentences.lastIndex)
            val row = paragraphsOf(text.sentences).indexOfFirst { start in it.sentences }.coerceAtLeast(0)
            Load.Ready(shown, text.sentences, text.skipped, start, LazyListState(row))
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

    // Where you are reading in the shown chapter: where it opened, then the first sentence after each scroll by hand.
    var readingAt by remember(load) { mutableIntStateOf((load as? Load.Ready)?.start ?: 0) }

    // The sentence whose rule sheet is open, if any. A chapter that loads again closes it.
    var ruleFor by remember(load) { mutableStateOf<String?>(null) }
    var ruleError by remember(ruleFor) { mutableStateOf<String?>(null) }
    var savingRule by remember { mutableStateOf(false) }

    fun saveRule(state: Load.Ready, draft: RuleDraft) {
        if (savingRule) return
        savingRule = true
        ruleError = null
        scope.launch {
            try {
                library.addRule(if (draft.allBooks) null else bookId, draft.kind, draft.term, draft.replacement)
                // Stored: close the sheet now, so nothing below can leave it open for a second, duplicate Save.
                ruleFor = null
                controller?.let { Commands.rulesChanged(it) }
                // Only what is dimmed changes, so the chapter stays where it is scrolled.
                state.skipped = library.chapterText(state.chapterId).skipped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a blank term or respelling comes back as an IllegalArgumentException with its reason
                ruleError = e.message ?: e.toString()
            } finally {
                savingRule = false
            }
        }
    }

    fun playPause() {
        val c = controller ?: return
        when {
            speaking -> c.pause()
            // Still with the voice: carry on. Scrolled away while paused: start where you are reading
            // (docs/decisions/position-tracking.md), recorded as a jump so Undo goes back to the voice.
            voiceChapter == shown && following -> c.play()
            else -> {
                Commands.playFrom(c, Position(bookId, shown, readingAt), jump = voiceChapter != null)
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
                sleep = { controller?.let { SleepButton(player.sleep, it) } },
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
                        skipped = state.skipped,
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
                            readingAt = index
                            // While the voice is speaking, the service owns the position.
                            if (!speaking) scope.launch { library.savePosition(Position(bookId, state.chapterId, index)) }
                        },
                        listState = state.listState,
                        onLongPress = { index -> ruleFor = state.sentences[index].text },
                    )
                    ruleFor?.let { sentence ->
                        RuleSheet(
                            sentence = sentence,
                            sentences = state.sentences,
                            error = ruleError,
                            saving = savingRule,
                            onPreview = controller?.let { c -> { text: String -> Commands.preview(c, text) } },
                            onSave = { saveRule(state, it) },
                            onDismiss = { ruleFor = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderControls(
    sleep: @Composable () -> Unit,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                sleep()
                if (canUndo) TextButton(onClick = onUndo) { Text("Undo jump") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Text("‹ Chapter") }
                Button(onClick = onPlayPause, enabled = canPlay) { Text(if (speaking) "Pause" else "Play") }
                OutlinedButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("Chapter ›") }
            }
        }
    }
}

private val ROW_PADDING_V = 6.dp
private const val SPINNER_DELAY_MS = 200L
