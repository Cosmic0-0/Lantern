package app.lantern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.library.Book
import app.lantern.playback.Commands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(nav: Nav, bookId: Long) {
    val library = LocalContext.current.lantern.library
    val controller = LocalPlayer.current.controller
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val book by produceState<Book?>(null, bookId) { value = library.book(bookId) }
    val chapters by remember(bookId) { library.chapters(bookId) }.collectAsState(initial = emptyList())
    val position by remember(bookId) { library.position(bookId) }.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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
                            onClick = {
                                controller?.let { Commands.continueBook(it, bookId) }
                                scope.launch { nav.openReader(library, bookId) }
                            },
                            enabled = controller != null && chapters.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text(if (position == null) "Start reading" else "Continue") }
                    }
                }
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { _, chapter ->
                    val saved = position?.takeIf { it.chapterId == chapter.id }
                    ListItem(
                        headlineContent = { Text(chapter.title, fontWeight = if (saved != null) FontWeight.Bold else null) },
                        supportingContent = if (saved != null) { { Text("You are here, sentence ${saved.sentence + 1}") } } else null,
                        modifier = Modifier.clickable { nav.open(Screen.Reader(bookId, chapter.id)) },
                    )
                }
            }
        }
    }
}
