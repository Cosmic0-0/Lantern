package app.lantern.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.lantern.core.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [ReaderContent] alone, with the player replaced by plain state. */
class ReaderContentTest {
    @get:Rule
    val rule = createComposeRule()

    // Stored before paragraphs: each sentence is its own paragraph, so each has its own row.
    private val oldStyle = List(80) { Sentence("Sentence number $it, which is long enough to be a line of its own on a phone.", it) }

    private class Probe {
        val taps = mutableListOf<Int>()
        val longPresses = mutableListOf<Int>()
        val followChanges = mutableListOf<Boolean>()
        val firstVisible = mutableListOf<Int>()
        var following by mutableStateOf(true)
        var highlighted by mutableStateOf<Int?>(null)
        val listState = LazyListState()
    }

    private fun show(probe: Probe, sentences: List<Sentence> = oldStyle) = rule.setContent {
        MaterialTheme {
            ReaderContent(
                sentences = sentences,
                highlighted = probe.highlighted,
                following = probe.following,
                onTap = { probe.taps += it },
                onLongPress = { probe.longPresses += it },
                onFollowChange = {
                    probe.followChanges += it
                    probe.following = it
                },
                onFirstVisible = { probe.firstVisible += it },
                listState = probe.listState,
            )
        }
    }

    @Test
    fun tappingASentenceReportsItsIndex() {
        val probe = Probe()
        show(probe)

        rule.onNodeWithTag(sentenceTag(3)).performClick()

        assertEquals(listOf(3), probe.taps)
    }

    @Test
    fun tappingTheSecondSentenceOfAParagraphReportsItsIndex() {
        val probe = Probe()
        val long = "Then the second sentence goes on and on, long enough to fill several lines of the paragraph on a phone screen, so its last line is far below the first."
        show(probe, listOf(Sentence("Before.", 0), Sentence("Hi.", 1), Sentence(long, 1), Sentence("After.", 2)))

        // Low in the paragraph's row: the first sentence, "Hi.", is on the first line only.
        rule.onNodeWithTag(sentenceTag(1)).performTouchInput { click(percentOffset(0.5f, 0.85f)) }
        // On the "H" of "Hi.": just inside the row's 16dp side and 6dp top padding, where the text starts.
        rule.onNodeWithTag(sentenceTag(1)).performTouchInput { click(Offset(20.dp.toPx(), 16.dp.toPx())) }

        assertEquals(listOf(2, 1), probe.taps)
    }

    @Test
    fun aLongPressNamesTheSameSentenceATapWould() {
        val probe = Probe()
        val long = "Then the second sentence goes on and on, long enough to fill several lines of the paragraph on a phone screen, so its last line is far below the first."
        show(probe, listOf(Sentence("Before.", 0), Sentence("Hi.", 1), Sentence(long, 1), Sentence("After.", 2)))

        rule.onNodeWithTag(sentenceTag(0)).performTouchInput { longClick() }
        // The same two spots as in the tap test above: low in the paragraph's row, then on the "H" of "Hi.".
        rule.onNodeWithTag(sentenceTag(1)).performTouchInput { longClick(percentOffset(0.5f, 0.85f)) }
        rule.onNodeWithTag(sentenceTag(1)).performTouchInput { longClick(Offset(20.dp.toPx(), 16.dp.toPx())) }

        assertEquals(listOf(0, 2, 1), probe.longPresses)
        assertTrue("a long-press is not also a tap: ${probe.taps}", probe.taps.isEmpty())
    }

    @Test
    fun aChapterStoredBeforeParagraphsShowsOneSentencePerRow() {
        val probe = Probe()
        show(probe, oldStyle.take(3))

        // Every sentence starts a row of its own; in a paragraph only the first sentence does.
        (0..2).forEach { rule.onNodeWithTag(sentenceTag(it)).assertIsDisplayed() }
    }

    @Test
    fun sentencesOfOneParagraphShareARow() {
        val probe = Probe()
        show(probe, listOf(Sentence("One.", 0), Sentence("Two.", 0), Sentence("Three.", 1)))

        rule.onNodeWithTag(sentenceTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(sentenceTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(sentenceTag(2)).assertIsDisplayed()
    }

    @Test
    fun aSwipeWhileFollowingStopsFollowingAndShowsBackToVoice() {
        val probe = Probe().apply { highlighted = 0 }
        show(probe)
        rule.onNodeWithText("Back to voice").assertDoesNotExist()

        rule.onNodeWithTag(sentenceTag(0)).performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertEquals(listOf(false), probe.followChanges)
        rule.onNodeWithText("Back to voice").assertIsDisplayed()
        // The scroll came from the swipe and settled, so the first fully visible sentence is reported once.
        assertEquals(1, probe.firstVisible.size)
        assertTrue("first visible was ${probe.firstVisible}", probe.firstVisible.single() > 0)
    }

    @Test
    fun backToVoiceTurnsFollowingOnAndScrollsToTheHighlight() {
        val probe = Probe().apply { highlighted = 0 }
        show(probe)
        rule.onNodeWithTag(sentenceTag(0)).performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue(probe.listState.firstVisibleItemIndex > 0)

        rule.onNodeWithText("Back to voice").performClick()
        rule.waitForIdle()

        assertEquals(listOf(false, true), probe.followChanges)
        rule.onNodeWithText("Back to voice").assertDoesNotExist()
        rule.onNodeWithTag(sentenceTag(0)).assertIsDisplayed()
    }

    @Test
    fun theHighlightedSentenceIsSelectedAndTheOthersAreNot() {
        val probe = Probe().apply { highlighted = 2 }
        show(probe)

        rule.onNodeWithTag(sentenceTag(2)).assertIsSelected()
        rule.onNodeWithTag(sentenceTag(1)).assertIsNotSelected()

        probe.highlighted = 1
        rule.waitForIdle()
        rule.onNodeWithTag(sentenceTag(1)).assertIsSelected()
        rule.onNodeWithTag(sentenceTag(2)).assertIsNotSelected()
    }

    @Test
    fun followingScrollsTheHighlightIntoViewWithoutCountingAsAHand() {
        val probe = Probe()
        show(probe)

        probe.highlighted = 60
        rule.waitForIdle()

        rule.onNodeWithTag(sentenceTag(60)).assertIsDisplayed()
        assertTrue(probe.followChanges.isEmpty())
        assertTrue(probe.firstVisible.isEmpty())
    }

    @Test
    fun followingReachesASentenceLowInALongParagraph() {
        val probe = Probe()
        // One paragraph far taller than the screen, then a short one.
        val long = List(60) { Sentence("Sentence $it of a very long paragraph that keeps going for many lines.", 0) } + Sentence("End.", 1)
        show(probe, long)

        probe.highlighted = 55
        rule.waitForIdle()

        // The row starts far above the screen: following scrolled inside the paragraph, not just to its top.
        assertTrue("first visible row ${probe.listState.firstVisibleItemIndex} offset ${probe.listState.firstVisibleItemScrollOffset}", probe.listState.firstVisibleItemScrollOffset > 0)
        assertTrue(probe.followChanges.isEmpty())
    }
}
