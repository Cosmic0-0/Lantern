package app.lantern.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.lantern.core.Sentence
import app.lantern.library.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** [RuleSheetContent] alone, for a fixed chapter. */
class RuleSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val junk = "Read more at patreon.com"

    // Sentences 0 and 2 are the junk line, each its own paragraph; 3 and 4 are one paragraph.
    private val chapter = listOf(
        Sentence(junk, 0),
        Sentence("The story goes on.", 1),
        Sentence(junk, 2),
        Sentence("Xiao Yan ran.", 3),
        Sentence("Then Xiao Yan won.", 3),
        Sentence("Yan stood alone.", 4),
    )

    private val saved = mutableListOf<RuleDraft>()
    private val previewed = mutableListOf<String>()

    private fun show(sentence: String, error: String? = null) = rule.setContent {
        MaterialTheme {
            RuleSheetContent(
                sentence = sentence,
                sentences = chapter,
                error = error,
                saving = false,
                onPreview = { previewed += it },
                onSave = { saved += it },
            )
        }
    }

    // Save is at the foot of a scrolling sheet.
    private fun save() = rule.onNodeWithText("Save").performScrollTo().performClick()

    @Test
    fun theSkipSheetShowsHowManySentencesTheSentenceMatches() {
        show(junk)

        rule.onNodeWithText("Matches 2 sentences in this chapter").assertExists()
    }

    @Test
    fun cuttingTheTextDownChangesTheCount() {
        show(junk)

        rule.onNodeWithTag(RULE_TEXT_TAG).performTextReplacement("PATREON")
        rule.onNodeWithText("Matches 2 sentences in this chapter").assertExists()

        // Case is ignored, and a skipped paragraph counts all its sentences.
        rule.onNodeWithTag(RULE_TEXT_TAG).performTextReplacement("Xiao Yan ran")
        rule.onNodeWithText("Matches 2 sentences in this chapter").assertExists()

        rule.onNodeWithTag(RULE_TEXT_TAG).performTextReplacement("The story")
        rule.onNodeWithText("Matches 1 sentence in this chapter").assertExists()
    }

    @Test
    fun blankTextDisablesSave() {
        show(junk)
        rule.onNodeWithText("Save").assertIsEnabled()

        rule.onNodeWithTag(RULE_TEXT_TAG).performTextReplacement("   ")

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Matches", substring = true).assertDoesNotExist()
    }

    @Test
    fun savingASkipRuleSendsItForThisBookOrForAllBooks() {
        show(junk)

        save()
        rule.onNodeWithText("All books").performClick()
        save()

        assertEquals(listOf(RuleDraft(RuleKind.SKIP, junk, null, allBooks = false), RuleDraft(RuleKind.SKIP, junk, null, allBooks = true)), saved)
    }

    @Test
    fun aFixTakesTwoAdjacentWordsAndCountsTheSentencesItChanges() {
        show("Xiao Yan ran.")
        rule.onNodeWithText("Fix how a word sounds").performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        // "Yan" alone is in three sentences; with "Xiao" next to it, in two.
        rule.onNodeWithText("Yan").performClick()
        rule.onNodeWithText("Matches 3 sentences in this chapter").assertExists()
        rule.onNodeWithText("Xiao").performClick()
        rule.onNodeWithText("Matches 2 sentences in this chapter").assertExists()

        rule.onNodeWithTag(RULE_SOUNDS_LIKE_TAG).performTextInput("Shao Yen")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Preview").performScrollTo().performClick()
        save()

        assertEquals(listOf("Shao Yen"), previewed)
        assertEquals(listOf(RuleDraft(RuleKind.FIX, "Xiao Yan", "Shao Yen", allBooks = false)), saved)
    }

    @Test
    fun theSaveErrorIsShown() {
        show(junk, error = "A rule needs some text")

        rule.onNodeWithText("A rule needs some text").assertExists()
    }
}
