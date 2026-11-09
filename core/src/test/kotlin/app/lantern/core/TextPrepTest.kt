package app.lantern.core

import java.io.File
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextPrepTest {
    private fun sentences(text: String) = TextPrep.sentences(text)

    // Junk lines

    @Test
    fun watermarkInStyledMathsLettersIsRemovedAndTheDialogueKept() {
        assertEquals(
            listOf("\"Where’s Thomas?\""),
            sentences("\"Where’s Thomas?\" 𝙛𝓻𝒆𝒆𝒘𝙚𝓫𝙣𝙤𝒗𝙚𝓵.𝙘𝙤𝙢"),
        )
    }

    @Test
    fun readMoreAdLineIsDroppedAndStoryLinesKept() {
        val text = "He turned away.\nREAD MORE ChapterS HERE : https://beastnovels.com/\nShe followed him."
        assertEquals(listOf("He turned away.", "She followed him."), sentences(text))
    }

    @Test
    fun readMoreAdIsMatchedIgnoringCaseAndWithABareDomain() {
        assertEquals(emptyList(), sentences("read more chapters at novgo.net"))
    }

    @Test
    fun readMoreWithoutAnyLinkIsStoryText() {
        assertEquals(listOf("Read more books, she said."), sentences("Read more books, she said."))
    }

    @Test
    fun lineThatIsOnlyAUrlOrDomainIsDropped() {
        val text = "First.\nhttps://novgo.net/\nnovgo.net\n   novgo.net   \nhttp://www.novgo.net/book/chapter-1\nSecond."
        assertEquals(listOf("First.", "Second."), sentences(text))
    }

    @Test
    fun trailingDomainIsStrippedWithOrWithoutScheme() {
        assertEquals(listOf("\"Visit us.\""), sentences("\"Visit us.\" https://novgo.net/"))
        assertEquals(listOf("The end."), sentences("The end. www.freewebnovel.com"))
    }

    @Test
    fun websiteNamedInsideADialogueSentenceIsKept() {
        // The splitter ends the first sentence at the closing quote, so the tag becomes its own piece.
        assertEquals(
            listOf("\"Did you check novgo.net for the next part?\"", "she asked."),
            sentences("\"Did you check novgo.net for the next part?\" she asked."),
        )
    }

    @Test
    fun ordinarySentenceEndsAreNotTakenForDomains() {
        for (line in listOf("Mr. Smith.", "This is the end.", "Use tags, e.g.", "It cost 3.50", "He lived in the U.S.A", "Wait...what")) {
            assertEquals(line, TextPrep.cleanLine(line), line)
        }
    }

    @Test
    fun missingSpaceAfterAFullStopIsNotTakenForADomain() {
        assertEquals("He left.Then", TextPrep.cleanLine("He left.Then"))
    }

    // Splitting

    @Test
    fun abbreviationsDoNotEndASentence() {
        assertEquals(listOf("Mr. Smith arrived.", "Dr. Wu left."), sentences("Mr. Smith arrived. Dr. Wu left."))
    }

    @Test
    fun initialsDoNotEndASentence() {
        assertEquals(listOf("The A.I. spoke.", "It was calm."), sentences("The A.I. spoke. It was calm."))
    }

    @Test
    fun eGDoesNotEndASentence() {
        assertEquals(listOf("e.g. this"), sentences("e.g. this"))
    }

    @Test
    fun closingQuoteAfterAnEllipsisEndsTheSentence() {
        assertEquals(listOf("\"Wait...\"", "he said.", "\"Now!\""), sentences("\"Wait...\" he said. \"Now!\""))
    }

    @Test
    fun ellipsisBeforeACapitalEndsTheSentence() {
        // NFKC turns the single ellipsis character into three full stops, which is fine for a voice.
        assertEquals(listOf("He paused...", "Then ran."), sentences("He paused… Then ran."))
    }

    @Test
    fun paragraphsAreSeparateSentencesEvenWithoutEndPunctuation() {
        assertEquals(listOf("Chapter 1", "It began."), sentences("Chapter 1\nIt began."))
    }

    @Test
    fun shortDialogueLinesSurvive() {
        assertEquals(listOf("\"No.\"", "\"Huh?\""), sentences("\"No.\"\n\"Huh?\""))
    }

    @Test
    fun linesWithoutLettersOrDigitsAreDropped() {
        assertEquals(listOf("\"No.\""), sentences("\"...\"\n...\n…\n😉\n\"No.\""))
    }

    @Test
    fun emptyAndWhitespaceOnlyInputGiveNoSentences() {
        assertEquals(emptyList(), sentences(""))
        assertEquals(emptyList(), sentences(" \n\t\n\r\n  "))
    }

    @Test
    fun windowsLineEndingsAreHandled() {
        assertEquals(listOf("One.", "Two."), sentences("One.\r\nTwo.\r\n"))
    }

    // Real chapters (gitignored, so this skips on a machine that does not have them)

    private val whitespace = Regex("\\s+")

    private fun realChapters(): List<File> {
        val dir = javaClass.classLoader.getResource("chapters")?.let { File(it.toURI()) }
        return dir?.listFiles { f -> f.extension == "txt" && f.length() > 0 }?.sortedBy { it.name }.orEmpty()
    }

    // The tokens that carry words. Punctuation-only tokens are left out because the splitter
    // drops pieces made only of those.
    private fun wordTokens(text: String) =
        text.split(whitespace).filter { token -> token.any { it.isLetterOrDigit() } }

    @Test
    fun realChaptersLoseNoStoryText() {
        val chapters = realChapters()
        if (chapters.isEmpty()) {
            println("No real chapters in src/test/resources/chapters, skipping")
            return
        }
        for (file in chapters) {
            val text = file.readText()
            val sentences = TextPrep.sentences(text)
            val lines = Normalizer.normalize(text, Normalizer.Form.NFKC).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val cleaned = lines.map { it to TextPrep.cleanLine(it) }

            println("${file.name}: ${sentences.size} sentences")
            for ((line, clean) in cleaned) {
                when {
                    clean == null -> println("  dropped: $line")
                    clean != line -> println("  changed: $line  ->  $clean")
                }
            }

            // Only URL-looking text may go: a dropped line has a URL or is all dotted tokens,
            // and a changed line lost only dotted tokens from its end.
            for ((line, clean) in cleaned) {
                if (clean == null) {
                    assertTrue("://" in line || line.split(whitespace).all { '.' in it }, "dropped story text? $line")
                } else if (clean != line) {
                    val removed = line.removePrefix(clean).trim()
                    assertTrue(line.startsWith(clean) && removed.split(whitespace).all { '.' in it }, "changed story text? $line")
                }
            }

            assertEquals(
                wordTokens(cleaned.mapNotNull { it.second }.joinToString(" ")),
                wordTokens(sentences.joinToString(" ")),
                "${file.name}: story text lost or changed",
            )

            // A short line can hold two sentences ("Damn it. What about Falke?"), so look for it across the
            // sentences read in order rather than inside one.
            val spoken = sentences.joinToString(" ")
            for ((_, clean) in cleaned) {
                if (clean != null && clean.length <= 30 && clean.any { it.isLetter() }) {
                    assertTrue(clean.replace(whitespace, " ") in spoken, "${file.name}: short line missing from the sentences: $clean")
                }
            }
        }
    }
}
