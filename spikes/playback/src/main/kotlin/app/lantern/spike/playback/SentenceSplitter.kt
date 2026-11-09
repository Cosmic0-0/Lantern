package app.lantern.spike.playback

object SentenceSplitter {
    // Whitespace right after a sentence-ending mark (a run of them is fine) plus any closing quotes or
    // brackets. The lookbehind is bounded because Java requires that.
    private val boundary = Regex("""(?<=[.!?…][.!?…"”’')\]]{0,8})\s+""")

    // A piece ending in one of these, or in a single letter and a dot ("A.I."), is not a sentence end.
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "lt", "col", "gen", "capt", "sgt",
        "vol", "ch", "vs", "etc", "e.g", "i.e", "approx", "mt", "ft",
    )

    private fun endsInAbbreviation(piece: String): Boolean {
        if (!piece.endsWith('.')) return false
        val word = piece.dropLast(1).substringAfterLast(' ').trimStart('"', '“', '‘', '\'', '(', '[').lowercase()
        return word in abbreviations || Regex("""(\p{L}\.)*\p{L}""").matches(word)
    }

    fun split(text: String): List<String> =
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { paragraph ->
                val merged = ArrayList<String>()
                for (piece in paragraph.split(boundary)) {
                    if (merged.isNotEmpty() && endsInAbbreviation(merged.last())) {
                        merged[merged.size - 1] = merged.last() + " " + piece
                    } else {
                        merged += piece
                    }
                }
                merged
            }
            .map { it.trim() }
            .filter { piece -> piece.isNotEmpty() && piece.any { it.isLetterOrDigit() } }
}
