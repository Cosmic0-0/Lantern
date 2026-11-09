package app.lantern.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Pasted text becomes a book with no source and one chapter (docs/decisions/pasted-text.md). */
@Composable
fun PasteScreen(nav: Nav) {
    val library = LocalContext.current.lantern.library
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Page(title = "Paste text", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                label = { Text("Text to read") },
                isError = error != null,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val bookId = library.paste(text)
                            val chapter = library.chapters(bookId).first().first()
                            nav.replace(Screen.Reader(bookId, chapter.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text(if (saving) "Saving…" else "Save") }
        }
    }
}
