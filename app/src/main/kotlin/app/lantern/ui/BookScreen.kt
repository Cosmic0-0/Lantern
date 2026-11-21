package app.lantern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.library.Book
import app.lantern.playback.Commands
import app.lantern.prepare.Prepare
import app.lantern.prepare.PrepareState
import app.lantern.prepare.PreparedAudio.BookAudio
import app.lantern.speech.Phonon
import app.lantern.ui.theme.redButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the ready chapters are counted again while this book is being prepared. */
private const val REFRESH_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(nav: Nav, bookId: Long) {
    val context = LocalContext.current
    val library = context.lantern.library
    val controller = LocalPlayer.current.controller
    val voiceCheck = LocalVoiceCheck.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val book by produceState<Book?>(null, bookId) { value = library.book(bookId) }
    val chapters by remember(bookId) { library.chapters(bookId) }.collectAsState(initial = emptyList())
    val position by remember(bookId) { library.position(bookId) }.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val prepareState by remember(context) { Prepare.state(context) }.collectAsState(initial = null)
    val preparing = prepareState?.takeIf { it.bookId == bookId }
    var audio by remember(bookId) { mutableStateOf(BookAudio(emptySet(), 0)) }
    var otherVoices by remember(bookId) { mutableStateOf(emptyMap<String, BookAudio>()) } // kept when the voice changes
    var choosing by remember { mutableStateOf(false) }
    // Chapters from where you are to the end: what "rest of book" means.
    val remaining = chapters.size - chapters.indexOfFirst { it.id == position?.chapterId }.coerceAtLeast(0)

    // The job changing is the cue to count again; while it makes this book's chapters, it also gets counted on a timer.
    LaunchedEffect(bookId, preparing) {
        val prepared = context.lantern.prepared
        val voice = context.lantern.settings.voice
        otherVoices = Phonon.VOICES.filter { it != voice }.associateWith { prepared.book(bookId, it) }.filterValues { it.ready.isNotEmpty() }
        while (true) {
            audio = prepared.book(bookId, voice)
            if (preparing == null) break
            delay(REFRESH_MS)
        }
    }

    // A long book opens at the chapter you are on, once.
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(chapters, position) {
        if (scrolled || chapters.isEmpty()) return@LaunchedEffect
        scrolled = true
        val at = chapters.indexOfFirst { it.id == position?.chapterId }
        if (at > 0) listState.scrollToItem(at + 1) // the header is item 0
    }

    Page(
        title = book?.title.orEmpty(),
        nav = nav,
        actions = {
            TextButton(onClick = { nav.open(Screen.Rules(bookId)) }) { Text("Rules") }
            // Pasted text has no source to switch from.
            if (book?.sourceId != null) TextButton(onClick = { nav.open(Screen.SwitchSource(bookId)) }) { Text("Switch source") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    val message = try {
                        when (val added = library.refresh(bookId)) {
                            0 -> "No new chapters"
                            1 -> "1 new chapter"
                            else -> "$added new chapters"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Couldn't check for new chapters: ${e.message ?: e}"
                    }
                    refreshing = false
                    snackbar.showSnackbar(message)
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("${sourceName(book?.sourceId)} · ${chaptersText(chapters.size)}", style = MaterialTheme.typography.bodyMedium)
                        Button(
                            colors = redButtonColors(),
                            onClick = {
                                voiceCheck.start({ library.startOf(bookId) }) { Commands.continueBook(it, bookId) }
                                scope.launch { nav.openReader(library, bookId) }
                            },
                            enabled = controller != null && chapters.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text(if (position == null) "Start reading" else "Continue") }
                        if (preparing != null) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(statusText(preparing), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { Prepare.cancel(context) }, modifier = Modifier.padding(start = 12.dp)) { Text("Cancel") }
                            }
                        } else {
                            if (audio.ready.isNotEmpty()) {
                                Text(
                                    "${chaptersText(audio.ready.size)} ready · ${formatSize(audio.bytes)}",
                                    Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            for ((other, made) in otherVoices) {
                                Text(
                                    "Also ready in ${voiceName(other)}: ${chaptersText(made.ready.size)} · ${formatSize(made.bytes)}",
                                    Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            OutlinedButton(
                                onClick = { choosing = true },
                                enabled = chapters.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            ) { Text("Prepare audio") }
                        }
                    }
                }
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { _, chapter ->
                    val saved = position?.takeIf { it.chapterId == chapter.id }
                    val notes = listOfNotNull(
                        saved?.let { "You are here, sentence ${it.sentence + 1}" },
                        "Audio ready".takeIf { chapter.id in audio.ready },
                    )
                    ListItem(
                        headlineContent = { Text(chapter.title, fontWeight = if (saved != null) FontWeight.Bold else null) },
                        supportingContent = if (notes.isNotEmpty()) { { Text(notes.joinToString(" · ")) } } else null,
                        modifier = Modifier.clickable { nav.open(Screen.Reader(bookId, chapter.id)) },
                    )
                }
            }
        }
    }

    if (choosing) {
        PrepareDialog(
            bookId = bookId,
            remaining = remaining,
            replacesOther = prepareState != null,
            onDismiss = { choosing = false },
        )
    }
}

private fun statusText(state: PrepareState): String = state.waiting ?: when (state.stage) {
    PrepareState.Stage.STARTING -> "Starting…"
    PrepareState.Stage.FETCHING -> "Fetching chapter text: ${state.done + 1} of ${state.total}"
    PrepareState.Stage.MAKING -> "${state.done} of ${state.total} chapters ready"
}

/** One way to choose how many chapters. [chapters] is what [Prepare.start] takes; [count] is how many that is. */
private class HowMany(val label: String, val chapters: Int, val count: Int)

/**
 * How many chapters to prepare, then, when the phone is not charging, a second question before it starts.
 * [replacesOther]: a job for another book is running, and starting this one drops it.
 */
@Composable
private fun PrepareDialog(bookId: Long, remaining: Int, replacesOther: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = listOf(5, 10, 25).filter { it < remaining }.map { HowMany("Next $it chapters", it, it) } +
        HowMany("Rest of book (${chaptersText(remaining)})", Prepare.REST_OF_BOOK, remaining)
    var selected by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf<HowMany?>(null) }
    val at = selected.coerceAtMost(options.lastIndex)

    fun start(how: HowMany) {
        Prepare.start(context, bookId, how.chapters)
        onDismiss()
    }

    val unplugged = confirming
    if (unplugged != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Not charging") },
            text = {
                Text(
                    "Your phone isn't charging. Preparing ${chaptersText(unplugged.count)} keeps the processor busy for about " +
                        "${formatDuration(unplugged.count * Prepare.MINUTES_PER_CHAPTER)} and uses a lot of battery. It's best done on the charger.",
                )
            },
            confirmButton = { TextButton(onClick = { start(unplugged) }) { Text("Prepare anyway") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Prepare audio") },
            text = {
                Column {
                    Column(Modifier.selectableGroup()) {
                        options.forEachIndexed { index, how ->
                            Row(
                                Modifier.fillMaxWidth().selectable(index == at, onClick = { selected = index }, role = Role.RadioButton).padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = index == at, onClick = null)
                                Text(how.label, Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                    if (replacesOther) Text("This replaces the audio being prepared for another book.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { if (Prepare.isCharging(context)) start(options[at]) else confirming = options[at] }) { Text("Prepare") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
