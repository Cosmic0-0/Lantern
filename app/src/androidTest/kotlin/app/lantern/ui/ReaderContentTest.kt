package app.lantern.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [ReaderContent] alone, with the player replaced by plain state. */
class ReaderContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val sentences = List(80) { "Sentence number $it, which is long enough to be a line of its own on a phone." }

    private class Probe {
        val taps = mutableListOf<Int>()
        val followChanges = mutableListOf<Boolean>()
        val firstVisible = mutableListOf<Int>()
        var following by mutableStateOf(true)
        var highlighted by mutableStateOf<Int?>(null)
        val listState = LazyListState()
    }

    private fun show(probe: Probe) = rule.setContent {
        MaterialTheme {
            ReaderContent(
                sentences = sentences,
                highlighted = probe.highlighted,
                following = probe.following,
                onTap = { probe.taps += it },
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
}
