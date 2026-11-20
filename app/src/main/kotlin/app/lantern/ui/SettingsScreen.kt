package app.lantern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.playback.Commands
import app.lantern.prepare.PreparedAudio.VoiceAudio
import app.lantern.speech.Phonon
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalContext.current.lantern
    val settings = app.settings
    val controller = LocalPlayer.current.controller
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf(settings.voice) }
    var speed by remember { mutableFloatStateOf(settings.speed) }
    var checking by remember { mutableStateOf(false) } // looking up what a voice change would delete
    var changing by remember { mutableStateOf<Pair<String, VoiceAudio>?>(null) }

    fun change(name: String) {
        voice = name
        settings.voice = name
        if (controller != null) Commands.setVoice(controller, name)
    }

    Page(title = "Settings", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                for (name in Phonon.VOICES) {
                    val pick = {
                        if (name != voice && !checking) {
                            checking = true
                            scope.launch {
                                try {
                                    // Prepared audio is in the old voice and goes when the voice changes: say so first.
                                    val made = app.prepared.voice(voice)
                                    if (made.chapters > 0) changing = name to made else change(name)
                                } finally {
                                    checking = false
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(voice == name, onClick = pick, role = Role.RadioButton).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = voice == name, onClick = null)
                        Text(name.replaceFirstChar(Char::titlecase), Modifier.padding(start = 16.dp))
                    }
                }
            }
            Text("Speed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in SPEEDS) {
                    FilterChip(
                        selected = speed == step,
                        onClick = {
                            speed = step
                            if (controller != null) controller.setPlaybackSpeed(step) else settings.speed = step
                        },
                        label = { Text("${step}x") },
                    )
                }
            }
        }
    }

    changing?.let { (name, made) ->
        ChangeVoiceDialog(
            made = made,
            onConfirm = {
                changing = null
                change(name)
            },
            onDismiss = { changing = null },
        )
    }
}

/** Says what a voice change deletes, and keeps Change voice off until the box is ticked. */
@Composable
private fun ChangeVoiceDialog(made: VoiceAudio, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change voice?") },
        text = {
            Column {
                Text("Changing the voice deletes the prepared audio for ${chaptersText(made.chapters)} (${formatSize(made.bytes)}). It would have to be made again.")
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp).toggleable(understood, onValueChange = { understood = it }, role = Role.Checkbox),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Text("I understand the audio will be deleted", Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = understood) { Text("Change voice") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
