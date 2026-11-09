package app.lantern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.library.Book
import app.lantern.playback.Commands
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(nav: Nav) {
    val library = LocalContext.current.lantern.library
    val player = LocalPlayer.current
    val controller = player.controller
    val scope = rememberCoroutineScope()
    val books by remember { library.books() }.collectAsState(initial = null)
    var deleting by remember { mutableStateOf<Book?>(null) }

    Page(
        title = "Lantern",
        nav = nav,
        actions = {
            TextButton(onClick = { nav.open(Screen.Search) }) { Text("Search") }
            TextButton(onClick = { nav.open(Screen.Paste) }) { Text("Paste") }
            TextButton(onClick = { nav.open(Screen.Settings) }) { Text("Settings") }
        },
    ) { padding ->
        val list = books
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No books yet. Use Search to find one.")
            }
            else -> {
                val last = list.firstOrNull { it.lastPlayedAt != null }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    if (last != null) item {
                        Button(
                            onClick = {
                                controller?.let { Commands.continueBook(it, null) }
                                scope.launch { nav.openReader(library, last.id) }
                            },
                            enabled = controller != null,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text("Continue ${last.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(list, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${sourceName(book.sourceId)} · ${chaptersText(book.chapterCount)}") },
                            modifier = Modifier.combinedClickable(
                                onClick = { nav.open(Screen.Book(book.id)) },
                                onLongClick = { deleting = book },
                            ),
                        )
                    }
                }
            }
        }
    }

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
