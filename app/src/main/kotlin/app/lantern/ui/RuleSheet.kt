package app.lantern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lantern.core.Fix
import app.lantern.core.Sentence
import app.lantern.core.SkipRule
import app.lantern.core.SpeechRules
import app.lantern.library.RuleKind
import kotlin.math.abs

/** Tags of the sheet's two text fields that tests type into. */
const val RULE_TEXT_TAG = "rule-text"
const val RULE_SOUNDS_LIKE_TAG = "rule-sounds-like"

/** A rule as the sheet offers it, before it is saved. [allBooks] false means this book only. */
data class RuleDraft(val kind: RuleKind, val term: String, val replacement: String?, val allBooks: Boolean)

/** [RuleSheetContent] in a bottom sheet, opened by a long-press on a sentence in the reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSheet(
    sentence: String,
    sentences: List<Sentence>,
    error: String?,
    saving: Boolean,
    onPreview: ((String) -> Unit)?,
    onSave: (RuleDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    // Skip the half-open stop: the sheet is short, and a half-open one hides Save behind the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RuleSheetContent(sentence, sentences, error, saving, onPreview, onSave)
    }
}

private enum class Choice(val label: String) {
    SKIP("Skip lines like this"),
    FIX("Fix how a word sounds"),
}

/**
 * What the rule sheet offers for [sentence], which is one of [sentences], the chapter's. Each choice shows how many
 * of [sentences] its rule would catch, before anything is saved.
 *
 * - Skip lines like this: a text field that starts as the sentence, to cut down to the part that marks junk.
 * - Fix how a word sounds: tap a word, or two next to each other, to fill "Word", then type how it sounds.
 *   [onPreview] says the respelling without saving; null while there is no player to say it.
 * - [onSave] gets the rule. [error] is why the last save failed, and [saving] disables Save while one is under way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleSheetContent(
    sentence: String,
    sentences: List<Sentence>,
    error: String?,
    saving: Boolean,
    onPreview: ((String) -> Unit)?,
    onSave: (RuleDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choice by remember { mutableStateOf(Choice.SKIP) }
    var skipText by remember { mutableStateOf(sentence) }
    var word by remember { mutableStateOf("") }
    var soundsLike by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<IntRange?>(null) } // indexes into words
    var allBooks by remember { mutableStateOf(false) }
    val words = remember(sentence) { wordsOf(sentence) }

    val matches = remember(choice, skipText, word, soundsLike, sentences) {
        when (choice) {
            Choice.SKIP -> skipText.trim().takeIf { it.isNotEmpty() }
                ?.let { SpeechRules(listOf(SkipRule(it)), emptyList()).skipped(sentences).size }
            Choice.FIX -> word.trim().takeIf { it.isNotEmpty() }?.let {
                val rules = SpeechRules(emptyList(), listOf(Fix(it, soundsLike.trim())))
                sentences.count { s -> rules.spoken(s.text) != s.text }
            }
        }
    }
    val draft = when (choice) {
        Choice.SKIP -> RuleDraft(RuleKind.SKIP, skipText.trim(), null, allBooks)
        Choice.FIX -> RuleDraft(RuleKind.FIX, word.trim(), soundsLike.trim(), allBooks)
    }
    val complete = draft.term.isNotEmpty() && (choice == Choice.SKIP || draft.replacement.orEmpty().isNotEmpty())

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in Choice.entries) FilterChip(selected = choice == c, onClick = { choice = c }, label = { Text(c.label) })
        }
        when (choice) {
            Choice.SKIP -> OutlinedTextField(
                value = skipText,
                onValueChange = { skipText = it },
                label = { Text("Lines containing") },
                supportingText = { Text("A paragraph with this text isn't spoken. Matching ignores case.") },
                modifier = Modifier.fillMaxWidth().testTag(RULE_TEXT_TAG),
            )
            Choice.FIX -> {
                Text("Tap the word, or two words next to each other.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    words.forEachIndexed { i, range ->
                        FilterChip(
                            selected = picked?.contains(i) == true,
                            onClick = {
                                val before = picked
                                val next = if (before != null && before.first == before.last && abs(before.first - i) == 1) {
                                    minOf(before.first, i)..maxOf(before.first, i)
                                } else {
                                    i..i
                                }
                                picked = next
                                word = sentence.substring(words[next.first].first, words[next.last].last + 1)
                            },
                            label = { Text(sentence.substring(range.first, range.last + 1)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = word,
                    onValueChange = {
                        word = it
                        picked = null
                    },
                    label = { Text("Word") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = soundsLike,
                    onValueChange = { soundsLike = it },
                    label = { Text("Sounds like") },
                    supportingText = { Text("Spelled the way it should be said, such as Shao Yen.") },
                    modifier = Modifier.fillMaxWidth().testTag(RULE_SOUNDS_LIKE_TAG),
                )
                OutlinedButton(
                    onClick = { onPreview?.invoke(soundsLike.trim()) },
                    enabled = onPreview != null && soundsLike.isNotBlank(),
                ) { Text("Preview") }
            }
        }
        if (matches != null) Text(matchesText(matches), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !allBooks,
                onClick = { allBooks = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("This book") },
            )
            SegmentedButton(
                selected = allBooks,
                onClick = { allBooks = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("All books") },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { onSave(draft) }, enabled = complete && !saving, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

internal fun matchesText(count: Int) = "Matches ${if (count == 1) "1 sentence" else "$count sentences"} in this chapter"

private val WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")
private val POSSESSIVE = Regex("""['’][sS]$""")

/**
 * Where each word of [sentence] is, as character ranges, for the fix chips. A word is letters and digits, with
 * apostrophes inside it. "Yan's" gives "Yan", because a fix matches a word before an apostrophe.
 */
internal fun wordsOf(sentence: String): List<IntRange> = WORD.findAll(sentence).map { match ->
    if (match.value.length > 2 && POSSESSIVE.containsMatchIn(match.value)) match.range.first..match.range.last - 2 else match.range
}.toList()
