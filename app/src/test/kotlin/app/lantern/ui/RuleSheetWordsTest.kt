package app.lantern.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleSheetWordsTest {
    private fun words(sentence: String) = wordsOf(sentence).map { sentence.substring(it.first, it.last + 1) }

    @Test
    fun `words are letters and digits without the punctuation around them`() {
        assertEquals(listOf("Xiao", "Yan", "said", "hello"), words("\"Xiao Yan,\" said (hello)."))
    }

    @Test
    fun `an apostrophe inside a word stays, and a possessive ending is left off`() {
        assertEquals(listOf("don't", "Yan", "Yan"), words("don't Yan's Yan’s"))
    }

    @Test
    fun `a hyphen splits a name into its words`() {
        assertEquals(listOf("Zhao", "Yun"), words("Zhao-Yun"))
    }

    @Test
    fun `non-English letters and digits count as word characters`() {
        assertEquals(listOf("Élan", "7th", "日本"), words("Élan, 7th, 日本"))
    }

    @Test
    fun `the match line says 1 sentence in the singular`() {
        assertEquals("Matches 1 sentence in this chapter", matchesText(1))
        assertEquals("Matches 0 sentences in this chapter", matchesText(0))
        assertEquals("Matches 2 sentences in this chapter", matchesText(2))
    }
}
