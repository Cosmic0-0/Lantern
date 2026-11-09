package app.lantern.core

import java.text.Normalizer

object TextPrep {
    /** Chapter text (one paragraph per line) to the ordered sentences Lantern reads and speaks. */
    fun sentences(chapterText: String): List<String> =
        Normalizer.normalize(chapterText, Normalizer.Form.NFKC)
            .split('\n')
            .mapNotNull(::cleanLine)
            .flatMap(::splitParagraph)
            .map { it.trim() }
            .filter { piece -> piece.any { it.isLetterOrDigit() } }

    // A URL or bare domain: optional scheme, dotted labels, a TLD of 2 to 6 letters, optional path. The TLD must
    // be all lower or all upper case, so a missing space after a full stop ("left.Then") is not taken for a domain.
    private val domain = Regex("""(?:https?://)?(?:[A-Za-z0-9-]+\.)+(?:[a-z]{2,6}|[A-Z]{2,6})(?:/\S*)?""")

    /** The line without trailing domains, or null if it is junk. Expects NFKC text. */
    internal fun cleanLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("read more", ignoreCase = true) && domain.containsMatchIn(trimmed)) return null
        return stripTrailingDomains(trimmed).ifEmpty { null }
    }

    // Only a whole whitespace-separated token counts, so a domain inside a sentence is never touched.
    private fun stripTrailingDomains(line: String): String {
        val token = line.takeLastWhile { !it.isWhitespace() }
        return if (domain.matches(token)) stripTrailingDomains(line.dropLast(token.length).trimEnd()) else line
    }

    // The splitter from the playback spike, unchanged in behaviour.

    // Whitespace right after a sentence-ending mark (a run of them is fine) plus any closing quotes or
    // brackets. The lookbehind is bounded because Java requires that.
    private val boundary = Regex("""(?<=[.!?…][.!?…"”’')\]]{0,8})\s+""")

    // A piece ending in one of these, or in a single letter and a dot ("A.I."), is not a sentence end.
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "lt", "col", "gen", "capt", "sgt",
        "vol", "ch", "vs", "etc", "e.g", "i.e", "approx", "mt", "ft",
    )

    private val initials = Regex("""(\p{L}\.)*\p{L}""")

    private fun endsInAbbreviation(piece: String): Boolean {
        if (!piece.endsWith('.')) return false
        val word = piece.dropLast(1).substringAfterLast(' ').trimStart('"', '“', '‘', '\'', '(', '[').lowercase()
        return word in abbreviations || initials.matches(word)
    }

    private fun splitParagraph(paragraph: String): List<String> {
        val merged = ArrayList<String>()
        for (piece in paragraph.split(boundary)) {
            if (merged.isNotEmpty() && endsInAbbreviation(merged.last())) {
                merged[merged.size - 1] = merged.last() + " " + piece
            } else {
                merged += piece
            }
        }
        return merged
    }
}
