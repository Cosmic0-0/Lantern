package app.lantern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.library.Rule
import app.lantern.library.RuleKind
import app.lantern.playback.Commands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A book's skip rules and pronunciation fixes: its own, then the ones for all books. Tap a rule to edit or delete it.
 * Rules are added from the reader, by a long-press on a sentence.
 */
@Composable
fun RulesScreen(nav: Nav, bookId: Long) {
    val library = LocalContext.current.lantern.library
    val controller = LocalPlayer.current.controller
    val scope = rememberCoroutineScope()
    var changes by remember { mutableIntStateOf(0) } // counts saves and deletes, to list the rules again
    val rules by produceState<List<Rule>?>(null, bookId, changes) { value = library.ruleList(bookId) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** Runs [block], tells the playback service the rules changed, and closes the dialog. */
    fun change(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
                controller?.let { Commands.rulesChanged(it) }
                changes += 1
                editing = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a blank term or respelling comes back as an IllegalArgumentException with its reason
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Page(title = "Rules", nav = nav) { padding ->
        val list = rules
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "No rules yet. Long-press a sentence in the reader to add one.",
                Modifier.padding(padding).padding(16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                // A rule with no book is for all books.
                rulesSection("This book", list.filter { it.bookId != null }) { editing = it; error = null }
                rulesSection("All books", list.filter { it.bookId == null }) { editing = it; error = null }
            }
        }
    }

    editing?.let { rule ->
        EditRuleDialog(
            rule = rule,
            error = error,
            busy = busy,
            onSave = { edited -> change { library.updateRule(edited) } },
            onDelete = { change { library.deleteRule(rule.id) } },
            onDismiss = { editing = null },
        )
    }
}

private fun LazyListScope.rulesSection(title: String, rules: List<Rule>, onEdit: (Rule) -> Unit) {
    if (rules.isEmpty()) return
    item(key = "header:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(rules, key = { it.id }) { rule ->
        ListItem(
            overlineContent = { Text(if (rule.kind == RuleKind.FIX) "Pronunciation fix" else "Skip rule") },
            headlineContent = { Text(rule.term, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = rule.replacement?.let { { Text("→ $it") } },
            modifier = Modifier.clickable { onEdit(rule) },
        )
    }
}

/** The term, and for a fix its respelling. Save is off while either is blank; the book and kind can't be changed. */
@Composable
private fun EditRuleDialog(
    rule: Rule,
    error: String?,
    busy: Boolean,
    onSave: (Rule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fix = rule.kind == RuleKind.FIX
    var term by remember(rule) { mutableStateOf(rule.term) }
    var replacement by remember(rule) { mutableStateOf(rule.replacement.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fix) "Pronunciation fix" else "Skip rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(if (fix) "Word" else "Lines containing") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fix) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text("Sounds like") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(rule.copy(term = term, replacement = if (fix) replacement else null)) },
                enabled = !busy && term.isNotBlank() && (!fix || replacement.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
