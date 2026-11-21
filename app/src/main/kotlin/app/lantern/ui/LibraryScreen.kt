package app.lantern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lantern.R
import app.lantern.Settings
import app.lantern.lantern
import app.lantern.library.Book
import app.lantern.playback.Commands
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

private const val COLUMNS = 3

/**
 * The home screen (docs/decisions/ui-design.md): a greeting, the book you were last in with a play button, then the
 * library as covers or a list, with its order and an "Audio ready" filter. The + button adds a book.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(nav: Nav) {
    val app = LocalContext.current.lantern
    val library = app.library
    val settings = app.settings
    val player = LocalPlayer.current
    val controller = player.controller
    val scope = rememberCoroutineScope()
    val books by remember { library.books() }.collectAsState(initial = null)
    var deleting by remember { mutableStateOf<Book?>(null) }
    var sort by remember { mutableStateOf(settings.librarySort) }
    var grid by remember { mutableStateOf(settings.libraryGrid) }
    var readyOnly by remember { mutableStateOf(false) }
    var addingBook by rememberSaveable { mutableStateOf(false) }

    // Chapters with audio ready, per book, in the current voice. Counted again when the library changes.
    val ready by produceState(emptyMap<Long, Int>(), books) {
        val list = books ?: return@produceState
        val voice = settings.voice
        value = list.associate { it.id to app.prepared.book(it.id, voice).ready.size }
    }

    Scaffold(
        bottomBar = { MiniPlayer(nav) },
        floatingActionButton = {
            FloatingActionButton(onClick = { addingBook = true }, shape = CircleShape) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add a book")
            }
        },
    ) { padding ->
        val list = books ?: return@Scaffold Box(Modifier.fillMaxSize().padding(padding))
        val last = list.firstOrNull { it.lastPlayedAt != null }
        val shown = list
            .filter { !readyOnly || (ready[it.id] ?: 0) > 0 }
            .let { all ->
                when (sort) {
                    "title" -> all.sortedBy { it.title.lowercase() }
                    "added" -> all.sortedByDescending { it.addedAt }
                    else -> all // the database's order: last played first, then newest added
                }
            }
        val open = { book: Book -> nav.open(Screen.Book(book.id)) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp, // clear of the + button
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 20.dp else 4.dp),
        ) {
            full {
                Header(onSettings = { nav.open(Screen.Settings) })
            }
            if (last != null) full { ContinueCard(nav, last, ready[last.id] ?: 0) }
            if (list.isEmpty()) {
                full {
                    Column(Modifier.padding(top = 48.dp)) {
                        Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Add a novel with the + button: search by title, or paste text to listen to.",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyVerticalGrid
            }
            full {
                LibraryBar(
                    count = list.size,
                    sort = sort,
                    onSort = {
                        sort = it
                        settings.librarySort = it
                    },
                    grid = grid,
                    onGrid = {
                        grid = it
                        settings.libraryGrid = it
                    },
                )
            }
            full {
                Row(Modifier.padding(bottom = 4.dp)) {
                    FilterChip(
                        selected = readyOnly,
                        onClick = { readyOnly = !readyOnly },
                        label = { Text("Audio ready") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (shown.isEmpty()) {
                full {
                    Text(
                        "No book has audio ready in this voice. Prepare chapters from a book's page.",
                        Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.id }, span = { GridItemSpan(if (grid) 1 else COLUMNS) }) { book ->
                val clicks = Modifier.combinedClickable(onClick = { open(book) }, onLongClick = { deleting = book })
                if (grid) GridBook(book, ready[book.id] ?: 0, clicks) else ListBook(book, ready[book.id] ?: 0, clicks)
            }
        }
    }

    if (addingBook) AddSheet(nav, onDismiss = { addingBook = false })

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this book?") },
            text = {
                Column {
                    Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Its chapters and your place in it will be removed from this phone.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        // Audio of a book that is going away would otherwise keep playing from sentences already made.
                        val chapters = library.chapters(book.id).first()
                        if (player.state.chapterId in chapters.map { it.id }) {
                            controller?.pause()
                            controller?.clearMediaItems()
                        }
                        library.delete(book.id)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(greeting(), Modifier.weight(1f), style = MaterialTheme.typography.displaySmall)
        IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning."
    in 12..17 -> "Good afternoon."
    else -> "Good evening."
}

/**
 * The book you were last in. The card opens it in the reader; the round button plays or pauses it from here, so a
 * listen on the bus needs no reader at all.
 */
@Composable
private fun ContinueCard(nav: Nav, book: Book, readyChapters: Int) {
    val app = LocalContext.current.lantern
    val library = app.library
    val player = LocalPlayer.current
    val controller = player.controller
    val voiceCheck = LocalVoiceCheck.current
    val scope = rememberCoroutineScope()
    val loadedBook by produceState<Long?>(null, player.state.chapterId) {
        value = player.state.chapterId?.let { library.chapter(it)?.bookId }
    }
    val loaded = loadedBook == book.id
    val playing = loaded && player.state.isPlaying

    Surface(
        onClick = { scope.launch { nav.openReader(library, book.id) } },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, book.coverUrl, Modifier.width(84.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(book.atTitle, sourceName(book.sourceId)).joinToString(" · "),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Progress(book, Modifier.padding(top = 16.dp))
                if (readyChapters > 0) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_graphic_eq), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "${chaptersText(readyChapters)} ready",
                            Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FilledIconButton(
                onClick = {
                    val c = controller ?: return@FilledIconButton
                    when {
                        playing -> c.pause()
                        loaded -> c.play()
                        else -> voiceCheck.start({ library.startOf(book.id) }) { Commands.continueBook(it, book.id) }
                    }
                },
                enabled = controller != null,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/** A thin red bar and the percentage, by chapters read. */
@Composable
private fun Progress(book: Book, modifier: Modifier = Modifier) {
    val percent = (book.progress * 100).roundToInt()
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { book.progress },
            modifier = Modifier.weight(1f).height(4.dp).semantics { contentDescription = "$percent percent through" },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            "$percent%",
            Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryBar(count: Int, sort: String, onSort: (String) -> Unit, grid: Boolean, onGrid: (Boolean) -> Unit) {
    var sorting by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Library", style = MaterialTheme.typography.titleLarge)
        Text(
            "$count",
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { sorting = true }) { Icon(painterResource(R.drawable.ic_sort), contentDescription = "Order") }
            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                for (option in Settings.LIBRARY_SORTS) {
                    DropdownMenuItem(
                        text = { Text(sortName(option)) },
                        onClick = {
                            sorting = false
                            onSort(option)
                        },
                        trailingIcon = {
                            if (option == sort) Icon(painterResource(R.drawable.ic_check), contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
        IconButton(onClick = { onGrid(!grid) }) {
            Icon(
                painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view),
                contentDescription = if (grid) "Show as a list" else "Show as covers",
            )
        }
    }
}

private fun sortName(sort: String) = when (sort) {
    "title" -> "Title"
    "added" -> "Recently added"
    else -> "Recently played"
}

@Composable
private fun GridBook(book: Book, readyChapters: Int, modifier: Modifier) {
    Column(modifier) {
        Box {
            BookCover(book.title, book.coverUrl, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            if (readyChapters > 0) ReadyBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            book.title,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            whereText(book),
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ListBook(book: Book, readyChapters: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book.title, book.coverUrl, Modifier.width(56.dp).aspectRatio(2f / 3f), letterSize = 24.sp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${whereText(book)} · ${sourceName(book.sourceId)}",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Progress(book, Modifier.padding(top = 8.dp))
        }
        if (readyChapters > 0) ReadyBadge(Modifier.padding(start = 12.dp))
    }
}

/** Marks a book with audio ready to play in the current voice. */
@Composable
private fun ReadyBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(
            painterResource(R.drawable.ic_graphic_eq),
            contentDescription = "Audio ready",
            modifier = Modifier.padding(5.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** "Ch. 15 of 112", or the chapter count for a book not started. */
private fun whereText(book: Book): String =
    if (book.atTitle == null) chaptersText(book.chapterCount) else "Ch. ${book.chaptersBefore + 1} of ${book.chapterCount}"
