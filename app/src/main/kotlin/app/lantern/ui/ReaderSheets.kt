package app.lantern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.lantern.R
import app.lantern.lantern
import app.lantern.library.Chapter
import app.lantern.playback.Commands
import app.lantern.speech.Phonon
import app.lantern.ui.theme.READER_SIZE_MAX
import app.lantern.ui.theme.READER_SIZE_MIN
import app.lantern.ui.theme.ReaderFont
import app.lantern.ui.theme.ReaderTheme
import kotlin.math.roundToInt

/** Chapters per block in the chapters sheet, as in the reference app. */
private const val BLOCK = 100

/**
 * Every chapter of the book in blocks of [BLOCK], the block with [current] open and scrolled to it. Chapters before
 * [current] are ticked as read, and chapters with audio ready in the current voice carry the audio mark.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChaptersSheet(bookId: Long, chapters: List<Chapter>, current: Long?, onPick: (Chapter) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.lantern
    val ready by produceState(emptySet<Long>(), bookId) { value = app.prepared.book(bookId, app.settings.voice).ready }
    val currentIndex = chapters.indexOfFirst { it.id == current }
    val blocks = remember(chapters) { chapters.chunked(BLOCK) }
    val open = remember { mutableStateMapOf<Int, Boolean>().apply { put((currentIndex.coerceAtLeast(0)) / BLOCK, true) } }
    val list = rememberLazyListState()

    // Rows before the current chapter: each open block's header and chapters, each closed block's header.
    LaunchedEffect(Unit) {
        if (currentIndex < 0) return@LaunchedEffect
        val block = currentIndex / BLOCK
        val before = (0 until block).sumOf { 1 + if (open[it] == true) blocks[it].size else 0 } + 1 + currentIndex % BLOCK
        list.scrollToItem((before - 2).coerceAtLeast(0))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Chapters", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${chapters.size}",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), state = list) {
            blocks.forEachIndexed { b, block ->
                val first = b * BLOCK
                val isOpen = open[b] == true
                stickyHeader(key = "block-$b") {
                    val readHere = block.indices.count { first + it < currentIndex }
                    val readyHere = block.count { it.id in ready }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(role = Role.Button, onClickLabel = if (isOpen) "Close" else "Open") { open[b] = !isOpen }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${first + 1}–${first + block.size}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildList {
                                add("$readHere read")
                                if (readyHere > 0) add("$readyHere with audio")
                            }.joinToString(" · "),
                            Modifier.weight(1f).padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            painterResource(if (isOpen) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isOpen) {
                    items(block, key = { it.id }) { chapter ->
                        val index = chapters.indexOf(chapter)
                        ChapterRow(
                            chapter = chapter,
                            read = index < currentIndex,
                            current = chapter.id == current,
                            ready = chapter.id in ready,
                            onClick = { onPick(chapter) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, read: Boolean, current: Boolean, ready: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) colors.secondaryContainer else colors.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics {
                stateDescription = listOfNotNull(
                    "You are here".takeIf { current },
                    "Read".takeIf { read },
                    "Audio ready".takeIf { ready },
                ).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp)) {
            if (read) Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        Text(
            chapter.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (read) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (ready) Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, Modifier.padding(start = 12.dp).size(20.dp), tint = colors.primary)
        if (current) Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null, Modifier.padding(start = 12.dp).size(20.dp), tint = colors.primary)
    }
}

/**
 * Listening controls that aren't needed every few seconds: voice, speed, the sleep timer and Undo jump. Changing the
 * voice here works as in Settings: nothing prepared is deleted (docs/decisions/prepared-audio.md).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListenSheet(controller: MediaController, sleep: SleepState, canUndo: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.lantern
    var voice by remember { mutableStateOf(app.settings.voice) }
    var speed by remember { mutableFloatStateOf(controller.playbackParameters.speed) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Listen", style = MaterialTheme.typography.headlineSmall)

            SheetSection("Voice")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in Phonon.VOICES) {
                    FilterChip(
                        selected = voice == name,
                        onClick = {
                            if (name != voice) {
                                voice = name
                                app.settings.voice = name
                                Commands.setVoice(controller, name)
                            }
                        },
                        label = { Text(voiceName(name)) },
                    )
                }
            }

            SheetSection("Speed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in SPEEDS) {
                    FilterChip(
                        selected = speed == step,
                        onClick = {
                            speed = step
                            controller.setPlaybackSpeed(step)
                        },
                        label = { Text("${step}x") },
                    )
                }
            }

            SheetSection("Sleep timer")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val minutes = listOf(15, 30, 45, 60)
                FilterChip(selected = !sleep.isSet, onClick = { Commands.sleep(controller, null) }, label = { Text("Off") })
                for (m in minutes) {
                    FilterChip(selected = false, onClick = { Commands.sleep(controller, Commands.Sleep.Minutes(m)) }, label = { Text("$m min") })
                }
                FilterChip(
                    selected = sleep.endOfChapter,
                    onClick = { Commands.sleep(controller, Commands.Sleep.EndOfChapter) },
                    label = { Text("End of chapter") },
                )
            }
            if (sleep.at != null) {
                Text(
                    "Stops at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleep.at))}",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider(Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Undo jump", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Go back to where you were before the last jump",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.padding(start = 12.dp)) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = null, Modifier.size(18.dp))
                    Text("Undo", Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun SheetSection(name: String) {
    Text(
        name,
        Modifier.padding(top = 24.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The reader's look, as the Display sheet edits it. */
data class ReaderLook(val theme: ReaderTheme, val font: ReaderFont, val size: Float)

/** Text size, font and theme for the reader. Changes show at once behind the sheet and are kept. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DisplaySheet(look: ReaderLook, onChange: (ReaderLook) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        scrimColor = Color.Transparent, // the page behind shows each change as it is made
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Display", style = MaterialTheme.typography.headlineSmall)

            SheetSection("Text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = look.size,
                    onValueChange = { onChange(look.copy(size = it.roundToInt().toFloat())) },
                    valueRange = READER_SIZE_MIN..READER_SIZE_MAX,
                    steps = (READER_SIZE_MAX - READER_SIZE_MIN).toInt() - 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp).semantics { stateDescription = "${look.size.roundToInt()} point" },
                )
                Text("A", style = MaterialTheme.typography.headlineSmall)
            }

            SheetSection("Font")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (font in ReaderFont.entries) {
                    FilterChip(
                        selected = look.font == font,
                        onClick = { onChange(look.copy(font = font)) },
                        label = { Text(font.label, fontFamily = font.family) },
                    )
                }
            }

            SheetSection("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (theme in ReaderTheme.entries) {
                    val chosen = look.theme == theme
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(theme.ground)
                            .border(if (chosen) 3.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant, MaterialTheme.shapes.medium)
                            .clickable(role = Role.RadioButton, onClickLabel = theme.label) { onChange(look.copy(theme = theme)) }
                            .semantics { stateDescription = if (chosen) "${theme.label}, chosen" else theme.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = theme.ink, style = MaterialTheme.typography.titleLarge, fontFamily = look.font.family)
                    }
                }
            }
        }
    }
}
