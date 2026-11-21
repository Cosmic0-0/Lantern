package app.lantern.ui

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lantern.R
import app.lantern.core.Listing
import app.lantern.core.Listings
import app.lantern.lantern
import app.lantern.sources.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the + button opens (docs/decisions/ui-design.md): search the sites by title, or paste text. Listings of the
 * same novel on different sources form one result with a button per source (docs/decisions/choosing-a-source.md).
 * Adding a book closes the sheet and opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSheet(nav: Nav, onDismiss: () -> Unit) {
    val app = LocalContext.current.lantern
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var sheetView by remember { mutableStateOf<View?>(null) } // the sheet's own window, which holds the keyboard
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var term by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<String, Result<List<Listing>>>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) } // a finished older search must not touch a newer one's state
    var job by remember { mutableStateOf<Job?>(null) }
    var adding by remember { mutableStateOf<Listing?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }

    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }

    fun submit() {
        val query = term.trim()
        if (query.isEmpty()) return
        // Inside a sheet, clearing focus and the keyboard controller both leave the keyboard up over the results
        // (seen on the A26), so the sheet's own window is told to hide it as well.
        focus.clearFocus(force = true)
        keyboard?.hide()
        sheetView?.let { it.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
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
                val bookId = app.library.add(listing)
                sheet.hide()
                onDismiss()
                nav.open(Screen.Book(bookId))
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val view = LocalView.current
        SideEffect { sheetView = view }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 20.dp)) {
            Text("Add a novel", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Search ${Sources.DEFAULT_SOURCES.joinToString(", ") { sourceName(it) }} by title.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextField(
                value = term,
                onValueChange = { term = it },
                placeholder = { Text("Title") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (term.isNotEmpty()) {
                        IconButton(onClick = {
                            // Back to the start: results go, and Paste text comes back.
                            job?.cancel()
                            generation += 1
                            term = ""
                            results = emptyMap()
                            searching = false
                            searched = false
                            requester.requestFocus()
                        }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).focusRequester(requester),
            )

            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                if (!searched) {
                    item {
                        Surface(
                            onClick = {
                                onDismiss()
                                nav.open(Screen.Paste)
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Icon(
                                        painterResource(R.drawable.ic_content_paste),
                                        contentDescription = null,
                                        modifier = Modifier.padding(10.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                    Text("Paste text", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Listen to anything you copied",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                addError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) } }
                items(failures, key = { "failed:${it.first}" }) { (id, error) ->
                    Text(
                        "${sourceName(id)} didn't answer: ${error.message ?: error::class.simpleName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(groups, key = { group -> group.first().let { "${it.sourceId}:${it.path}" } }) { group ->
                    val first = group.first()
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        BookCover(first.title, group.firstNotNullOfOrNull { it.coverUrl }, Modifier.width(64.dp).aspectRatio(2f / 3f), letterSize = MaterialTheme.typography.headlineMedium.fontSize)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(first.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (listing in group) {
                                    FilledTonalButton(onClick = { add(listing) }, enabled = adding == null) {
                                        if (adding == listing) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Text("Adding…", Modifier.padding(start = 8.dp))
                                        } else {
                                            Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
                                            Text(sourceName(listing.sourceId), Modifier.padding(start = 6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    when {
                        searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("Asking the sites…")
                            }
                        }
                        searched && groups.isEmpty() && failures.size < Sources.DEFAULT_SOURCES.size ->
                            Text("Nothing found for \"${term.trim()}\". Try fewer words.", Modifier.padding(vertical = 16.dp))
                    }
                }
            }
        }
    }
}
