package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SpeechRulesTest {
    private fun skips(vararg text: String) = SpeechRules(text.map(::SkipRule), emptyList())

    private fun sentences(vararg paragraphs: List<String>) =
        paragraphs.flatMapIndexed { paragraph, texts -> texts.map { Sentence(it, paragraph) } }

    // Skip rules

    @Test
    fun `a match in one sentence skips its whole paragraph`() {
        val text = sentences(
            listOf("He woke up.", "Translator: Bob."),
            listOf("She smiled."),
        )
        assertEquals(setOf(0, 1), skips("Translator:").skipped(text))
    }

    @Test
    fun `skip rules ignore case`() {
        val text = sentences(listOf("Support us on PATREON.COM today."), listOf("The end."))
        assertEquals(setOf(0), skips("patreon.com").skipped(text))
    }

    @Test
    fun `with no rules nothing is skipped`() {
        val text = sentences(listOf("Translator: Bob.", "He woke up."))
        assertEquals(emptySet(), skips().skipped(text))
    }

    @Test
    fun `a blank rule is ignored`() {
        val text = sentences(listOf("He woke up.", "She smiled."))
        assertEquals(emptySet(), skips("", "   ").skipped(text))
    }

    @Test
    fun `in an old-style chapter a match skips only its own sentence`() {
        val text = listOf("He woke up.", "Translator: Bob.", "She smiled.").mapIndexed { i, t -> Sentence(t, i) }
        assertEquals(setOf(1), skips("Translator:").skipped(text))
    }

    @Test
    fun `a rule that matches in two paragraphs skips both`() {
        val text = sentences(
            listOf("Edited by Ann."),
            listOf("A quiet street."),
            listOf("More story.", "Edited by Ann again."),
        )
        assertEquals(setOf(0, 2, 3), skips("edited by").skipped(text))
    }

    @Test
    fun `any one of several rules is enough`() {
        val text = sentences(listOf("Visit patreon.com."), listOf("Plain."), listOf("Translator: Bob."))
        assertEquals(setOf(0, 2), skips("Translator:", "patreon.com").skipped(text))
    }

    // Fixes

    private fun fixes(vararg pairs: Pair<String, String>) =
        SpeechRules(emptyList(), pairs.map { (from, to) -> Fix(from, to) })

    @Test
    fun `a fix matches whole words only`() {
        assertEquals("Yen was Yanking it.", fixes("Yan" to "Yen").spoken("Yan was Yanking it."))
    }

    @Test
    fun `a fix does not match inside a longer word on either side`() {
        assertEquals("Mayan Yanks Yen", fixes("Yan" to "Yen").spoken("Mayan Yanks Yan"))
    }

    @Test
    fun `a fix ignores case`() {
        val rules = fixes("xiao yan" to "Shao Yen")
        assertEquals("Shao Yen, Shao Yen and Shao Yen", rules.spoken("Xiao Yan, XIAO YAN and xiao yan"))
    }

    @Test
    fun `the possessive of a name still matches`() {
        assertEquals("Shao Yen's sword", fixes("Xiao Yan" to "Shao Yen").spoken("Xiao Yan's sword"))
    }

    @Test
    fun `the longest match wins`() {
        val rules = fixes("Xiao" to "Shao", "Xiao Yan" to "Shao Yen")
        assertEquals("Shao Yen met Shao.", rules.spoken("Xiao Yan met Xiao."))
    }

    @Test
    fun `the longest match wins whatever order the fixes are listed in`() {
        val rules = fixes("Xiao Yan" to "Shao Yen", "Xiao" to "Shao")
        assertEquals("Shao Yen met Shao.", rules.spoken("Xiao Yan met Xiao."))
    }

    @Test
    fun `two fixes in one sentence both apply`() {
        val rules = fixes("Xiao Yan" to "Shao Yen", "Hai" to "High")
        assertEquals("Shao Yen thanked High.", rules.spoken("Xiao Yan thanked Hai."))
    }

    @Test
    fun `a replacement is never matched again`() {
        val rules = fixes("A" to "B C", "C" to "D")
        assertEquals("B C", rules.spoken("A"))
    }

    @Test
    fun `a fix with a space matches any run of whitespace in the text`() {
        assertEquals("Shao Yen left.", fixes("Xiao Yan" to "Shao Yen").spoken("Xiao  Yan left."))
    }

    @Test
    fun `a double space in a fix matches a single space in the text`() {
        assertEquals("Shao Yen left.", fixes("Xiao  Yan" to "Shao Yen").spoken("Xiao Yan left."))
    }

    @Test
    fun `with no fixes the text is unchanged`() {
        assertEquals("Xiao Yan left.", fixes().spoken("Xiao Yan left."))
    }

    @Test
    fun `a blank fix is ignored`() {
        assertEquals("Xiao Yan left.", fixes("" to "x", "  " to "y").spoken("Xiao Yan left."))
    }

    @Test
    fun `with two fixes for the same words the first wins`() {
        val rules = fixes("Yan" to "Yen", "YAN" to "Yahn")
        assertEquals("Yen", rules.spoken("yan"))
    }

    @Test
    fun `stray spaces around a fix are not part of the match`() {
        assertEquals("Yen left.", fixes(" Yan " to "Yen").spoken("Yan left."))
    }

    @Test
    fun `regex characters in a fix are matched literally`() {
        assertEquals("Mister left (twice).", fixes("Mr." to "Mister").spoken("Mr. left (twice)."))
        assertEquals("a C-plus-plus b", fixes("C++" to "C-plus-plus").spoken("a C++ b"))
    }

    @Test
    fun `a replacement is spoken literally even with dollar signs and backslashes`() {
        assertEquals("""cost \1 $1 left""", fixes("X" to """\1 $1""").spoken("cost X left"))
    }

    @Test
    fun `a fix works on non-ASCII names`() {
        assertEquals("Zoë", fixes("Zoe" to "Zoë").spoken("Zoe"))
        assertEquals("Ünal ist Ünal", fixes("ünal" to "Ünal").spoken("ÜNAL ist ünal"))
        assertEquals("Ecu et Écuelle", fixes("écu" to "Ecu").spoken("Écu et Écuelle"))
    }

    // Audio key

    private val chapter = sentences(listOf("Xiao Yan drew his sword.", "Hai ran."), listOf("It was quiet."))

    private fun key(vararg pairs: Pair<String, String>, text: List<Sentence> = chapter) =
        fixes(*pairs).audioKey("abc", text)

    @Test
    fun `the key is the plain text hash when no fix occurs in the chapter`() {
        assertEquals("abc", key("Zhou" to "Joe"))
    }

    @Test
    fun `the key is the plain text hash when there are no fixes at all`() {
        assertEquals("abc", key())
    }

    @Test
    fun `the key of a chapter with a fix is a lowercase hex SHA-256 of the hash and the fix`() {
        // sha256 of "abc\nxiao yan\u0000shao yen", worked out with sha256sum
        assertEquals(
            "5dae10c7a772cb933df80972b24d6357a814af74d7bc4066751db84cc12a0ac2",
            key("Xiao Yan" to "shao yen"),
        )
    }

    @Test
    fun `fixes are sorted and joined one per line in the key`() {
        // sha256 of "abc\nhai\u0000high\nxiao yan\u0000shao yen", worked out with sha256sum
        assertEquals(
            "6867e8255c3db39db5c82c70fa29d96e8a4edf2e2ef0e8ad2615c2d6abbd6409",
            key("Xiao Yan" to "shao yen", "Hai" to "high"),
        )
    }

    @Test
    fun `adding a fix that occurs changes the key`() {
        assertNotEquals(key("Xiao Yan" to "Shao Yen"), key("Xiao Yan" to "Shao Yen", "Hai" to "High"))
    }

    @Test
    fun `a chapter with a fix does not have the plain text hash as its key`() {
        assertNotEquals("abc", key("Hai" to "High"))
    }

    @Test
    fun `editing what a fix says changes the key`() {
        assertNotEquals(key("Xiao Yan" to "Shao Yen"), key("Xiao Yan" to "Shao Yan"))
    }

    @Test
    fun `editing the words a fix matches changes the key`() {
        assertNotEquals(key("Xiao Yan" to "Shao Yen"), key("Hai" to "Shao Yen"))
    }

    @Test
    fun `removing a fix that occurs changes the key`() {
        assertNotEquals(key("Xiao Yan" to "Shao Yen", "Hai" to "High"), key("Xiao Yan" to "Shao Yen"))
    }

    @Test
    fun `adding a fix that does not occur leaves the key alone`() {
        assertEquals(key("Xiao Yan" to "Shao Yen"), key("Xiao Yan" to "Shao Yen", "Zhou" to "Joe"))
    }

    @Test
    fun `a fix that only matches inside a longer word does not occur`() {
        assertEquals(key("Xiao Yan" to "Shao Yen"), key("Xiao Yan" to "Shao Yen", "Ha" to "Hah", "wor" to "wer"))
    }

    @Test
    fun `a fix swallowed by a longer fix does not occur`() {
        assertEquals(key("Xiao Yan" to "Shao Yen"), key("Xiao Yan" to "Shao Yen", "Xiao" to "Shao"))
    }

    @Test
    fun `the key does not depend on the order of the fixes`() {
        val one = "Xiao Yan" to "Shao Yen"
        val two = "Hai" to "High"
        val three = "Xiao" to "Shao"
        val sameLengthAsTwo = "ran" to "run"
        assertEquals(key(one, two, three, sameLengthAsTwo), key(sameLengthAsTwo, three, two, one))
        assertEquals(key(one, two, three, sameLengthAsTwo), key(two, sameLengthAsTwo, one, three))
    }

    @Test
    fun `case and spacing in the words of a fix do not change the key`() {
        assertEquals(key("Xiao Yan" to "Shao Yen"), key("  xiao   YAN " to "Shao Yen"))
    }

    @Test
    fun `the first of two fixes for the same words decides the key`() {
        val keepFirst = key("Hai" to "High", "HAI" to "Hi")
        assertNotEquals(keepFirst, key("HAI" to "Hi", "Hai" to "High"))
        assertEquals(keepFirst, key("Hai" to "High"))
    }

    @Test
    fun `the key depends on the text hash`() {
        val rules = fixes("Hai" to "High")
        assertNotEquals(rules.audioKey("abc", chapter), rules.audioKey("abd", chapter))
    }

    @Test
    fun `a fix that occurs in any sentence counts`() {
        val late = sentences(listOf("Nothing here."), listOf("More nothing."), listOf("Then Hai came."))
        assertNotEquals("abc", key("Hai" to "High", text = late))
    }

    @Test
    fun `an empty chapter has the plain text hash`() {
        assertEquals("abc", key("Hai" to "High", text = emptyList()))
    }
}
