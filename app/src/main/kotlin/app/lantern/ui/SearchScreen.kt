package app.lantern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.lantern.core.Listing
import app.lantern.core.Listings
import app.lantern.lantern
import app.lantern.sources.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Listings of the same novel on different sources form one block (docs/decisions/choosing-a-source.md). */
@Composable
fun SearchScreen(nav: Nav) {
    val app = LocalContext.current.lantern
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var term by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<String, Result<List<Listing>>>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) } // a finished older search must not touch a newer one's state
    var job by remember { mutableStateOf<Job?>(null) }
    var adding by remember { mutableStateOf<Listing?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }

    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (term.isEmpty()) requester.requestFocus() }

    fun submit() {
        val query = term.trim()
        if (query.isEmpty()) return
        focus.clearFocus()
        job?.cancel()
        generation += 1
        val mine = generation
        results = emptyMap()
        searching = true
        searched = true
        addError = null
        job = scope.launch {
            try {
                app.sources.search(query).collect { (id, result) -> if (mine == generation) results = results + (id to result) }
            } finally {
                if (mine == generation) searching = false
            }
        }
    }

    fun add(listing: Listing) {
        if (adding != null) return
        adding = listing
        addError = null
        scope.launch {
            try {
                nav.replace(Screen.Book(app.library.add(listing)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError = "Couldn't add ${listing.title}: ${e.message ?: e}"
                adding = null
            }
        }
    }

    val listings = Sources.DEFAULT_SOURCES.flatMap { results[it]?.getOrNull().orEmpty() }
    val groups = remember(results) { Listings.groupByTitle(listings, Sources.DEFAULT_SOURCES) }
    val failures = Sources.DEFAULT_SOURCES.mapNotNull { id -> results[id]?.exceptionOrNull()?.let { id to it } }

    Page(title = "Search", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(requester),
            )
            if (adding != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                addError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                items(failures, key = { "failed:${it.first}" }) { (id, error) ->
                    Text(
                        "${sourceName(id)}: ${error.message ?: error::class.simpleName}",
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
                                if (adding == listing) "Adding from ${sourceName(listing.sourceId)}…" else "On ${sourceName(listing.sourceId)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = adding == null) { add(listing) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                item {
                    when {
                        searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("Asking the sources…")
                            }
                        }
                        searched && groups.isEmpty() && failures.size < Sources.DEFAULT_SOURCES.size ->
                            Text("Nothing found.", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
