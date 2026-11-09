package app.lantern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lantern.core.Listing
import app.lantern.core.Listings
import app.lantern.lantern
import app.lantern.library.Book
import app.lantern.library.SwitchPreview
import app.lantern.sources.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Moving a book to another source (docs/decisions/switching-source.md): the other sources are searched for the book's
 * title, you pick a listing, Lantern shows how its chapters match yours and where your position lands, and nothing
 * changes until you press Switch.
 */
@Composable
fun SwitchSourceScreen(nav: Nav, bookId: Long) {
    val app = LocalContext.current.lantern
    val scope = rememberCoroutineScope()
    val book by produceState<Book?>(null, bookId) { value = app.library.book(bookId) }
    var results by remember { mutableStateOf<Map<String, Result<List<Listing>>>>(emptyMap()) }
    var searching by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) } // what is happening, while a preview or switch runs
    var preview by remember { mutableStateOf<SwitchPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = book
    val others = Sources.DEFAULT_SOURCES.filter { it != current?.sourceId }
    LaunchedEffect(current?.title) {
        val title = current?.title ?: return@LaunchedEffect
        try {
            app.sources.search(title).collect { (id, result) -> if (id in others) results = results + (id to result) }
        } finally {
            searching = false
        }
    }

    fun previewOf(listing: Listing) {
        if (busy != null) return
        busy = "Reading the chapter list on ${sourceName(listing.sourceId)}…"
        error = null
        scope.launch {
            try {
                preview = app.library.previewSwitch(bookId, listing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't read ${listing.title} on ${sourceName(listing.sourceId)}: ${e.message ?: e}"
            } finally {
                busy = null
            }
        }
    }

    fun switch(p: SwitchPreview) {
        if (busy != null) return
        busy = "Switching…"
        error = null
        scope.launch {
            try {
                app.library.switchSource(p)
                nav.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't switch: ${e.message ?: e}"
                preview = null
                busy = null
            }
        }
    }

    // Listings with the book's own title first; sources return plenty of near misses.
    val listings = others.flatMap { results[it]?.getOrNull().orEmpty() }
    val groups = remember(results, current?.title) {
        Listings.groupByTitle(listings, Sources.DEFAULT_SOURCES)
            .sortedBy { group -> if (current != null && Listings.sameTitle(group.first().title, current.title)) 0 else 1 }
    }
    val failures = others.mapNotNull { id -> results[id]?.exceptionOrNull()?.let { id to it } }

    Page(title = "Switch source", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy.orEmpty(), modifier = Modifier.padding(16.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val shown = preview
            if (shown != null) {
                PreviewPanel(shown, enabled = busy == null, onSwitch = { switch(shown) }, onBack = { preview = null })
                return@Column
            }
            Text(
                "Now on ${sourceName(current?.sourceId)}. Pick the same novel on another source.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(failures, key = { "failed:${it.first}" }) { (id, e) ->
                    Text(
                        "${sourceName(id)}: ${e.message ?: e::class.simpleName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(groups, key = { group -> group.first().let { "${it.sourceId}:${it.path}" } }) { group ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(
                            group.first().title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        for (listing in group) {
                            Text(
                                "On ${sourceName(listing.sourceId)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = busy == null) { previewOf(listing) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                item {
                    when {
                        searching -> Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Asking the other sources…")
                        }
                        groups.isEmpty() -> Text("No other source has this title.", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** The counts and the position's landing place, with Switch and Back. Nothing has changed yet. */
@Composable
private fun PreviewPanel(preview: SwitchPreview, enabled: Boolean, onSwitch: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${preview.listing.title} on ${sourceName(preview.listing.sourceId)}", style = MaterialTheme.typography.titleMedium)
        Text("Matches ${preview.matched} of your ${chaptersText(preview.oldCount)}.")
        if (preview.added > 0) Text("Adds ${chaptersText(preview.added)} it has and the old source didn't.")
        if (preview.keptOld > 0) Text("Keeps ${chaptersText(preview.keptOld)} only on the old source, already loaded.")
        if (preview.removed > 0) Text("Removes ${chaptersText(preview.removed)} only on the old source, not loaded yet.")
        if (preview.positionFrom != null) {
            Text("You're at ${preview.positionFrom}. After the switch: ${preview.positionTo}.", fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onSwitch, enabled = enabled) { Text("Switch") }
            OutlinedButton(onClick = onBack, enabled = enabled) { Text("Back") }
        }
    }
}
