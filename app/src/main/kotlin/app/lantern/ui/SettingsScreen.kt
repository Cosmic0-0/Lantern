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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lantern.lantern
import app.lantern.playback.Commands
import app.lantern.prepare.PreparedAudio
import app.lantern.speech.Phonon

internal val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalContext.current.lantern
    val settings = app.settings
    val controller = LocalPlayer.current.controller
    var voice by remember { mutableStateOf(settings.voice) }
    var speed by remember { mutableFloatStateOf(settings.speed) }
    // Prepared audio in each voice. Changing voice deletes none of it (docs/decisions/prepared-audio.md).
    val made by produceState(emptyMap<String, PreparedAudio.VoiceAudio>()) {
        value = Phonon.VOICES.associateWith { app.prepared.voice(it) }
    }

    Page(title = "Settings", nav = nav) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                for (name in Phonon.VOICES) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(voice == name, role = Role.RadioButton, onClick = {
                                if (name != voice) {
                                    voice = name
                                    settings.voice = name
                                    if (controller != null) Commands.setVoice(controller, name)
                                }
                            })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = voice == name, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(voiceName(name))
                            made[name]?.takeIf { it.chapters > 0 }?.let {
                                Text(
                                    "${chaptersText(it.chapters)} prepared · ${formatSize(it.bytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
}
