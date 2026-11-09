package app.lantern.core

import java.security.MessageDigest

/** Text that, found in a sentence, drops that sentence's whole paragraph from the speech. Matches ignoring case. */
data class SkipRule(val text: String)

/** A word or name ([from]) and the respelling Phonon gets instead ([to]). Matches whole words, ignoring case. */
data class Fix(val from: String, val to: String)

/**
 * The skip rules and pronunciation fixes in force for one book (docs/decisions/speech-rules.md). They are applied when
 * speaking, never to the stored sentences.
 */
class SpeechRules(skips: List<SkipRule>, fixes: List<Fix>) {
    private val skipTexts = skips.map { it.text }.filter { it.isNotBlank() }

    // Blank fixes dropped, longest words first so "Xiao Yan" is tried before "Xiao". The sort is stable and a regex
    // tries its alternatives in order, so of two fixes for the same words (ignoring case and spacing) the first wins;
    // the other never matches anything, so it never occurs either.
    private val usable: List<Fix> = fixes
        .map { Fix(collapse(it.from), it.to) }
        .filter { it.from.isNotEmpty() }
        .sortedByDescending { it.from.length }

    // One alternative per fix, each in its own group, so the group that matched says which fix it was.
    // A run of whitespace in a fix matches any run of whitespace in the text; a "word" is a run of letters and
    // digits, so an apostrophe ends one ("Xiao Yan's").
    private val matcher: Regex? = if (usable.isEmpty()) null else Regex(
        usable.joinToString("|", """(?<![\p{L}\p{N}])(?:""", """)(?![\p{L}\p{N}])""") { fix ->
            fix.from.split(' ').joinToString("""\s+""", "(", ")") { Regex.escape(it) }
        },
        RegexOption.IGNORE_CASE,
    )

    /** Indexes into [sentences] of every sentence in a paragraph where any sentence matches a skip rule. */
    fun skipped(sentences: List<Sentence>): Set<Int> {
        if (skipTexts.isEmpty()) return emptySet()
        val hit = sentences
            .filter { s -> skipTexts.any { s.text.contains(it, ignoreCase = true) } }
            .mapTo(HashSet()) { it.paragraph }
        return sentences.indices.filter { sentences[it].paragraph in hit }.toSet()
    }

    /** The text Phonon gets: every fix applied in one pass, so a replacement is never matched again. */
    fun spoken(text: String): String {
        val regex = matcher ?: return text
        return regex.replace(text) { match -> usable[fixIndex(match)].to }
    }

    /**
     * [textHash] when no fix occurs in the chapter, otherwise a hash of it and the fixes that do, so audio made with
     * different fixes is stored apart (docs/decisions/speech-rules.md). A fix occurs when [spoken] applies it to some
     * sentence, so one hidden behind a longer fix doesn't count. Fix order doesn't matter.
     */
    fun audioKey(textHash: String, sentences: List<Sentence>): String {
        val regex = matcher ?: return textHash
        val occurring = sentences.flatMapTo(sortedSetOf<Int>()) { regex.findAll(it.text).map(::fixIndex).asIterable() }
        if (occurring.isEmpty()) return textHash
        val lines = occurring.map { "${usable[it].from.lowercase()}\u0000${usable[it].to}" }.sorted()
        val input = textHash + "\n" + lines.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fixIndex(match: MatchResult): Int = usable.indices.first { match.groups[it + 1] != null }

    private companion object {
        val whitespace = Regex("""\s+""")

        fun collapse(words: String) = words.trim().replace(whitespace, " ")
    }
}
