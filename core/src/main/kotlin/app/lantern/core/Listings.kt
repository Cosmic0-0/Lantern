package app.lantern.core

import java.text.Normalizer

object Listings {
    private val trailingBrackets = Regex("""(\s*[(\[][^)\]]*[)\]])+\s*$""")
    private val apostrophes = Regex("['’]")
    private val punctuation = Regex("\\p{P}")
    private val whitespace = Regex("\\s+")

    /**
     * Groups search results that are the same novel on different sources, for the search screen.
     * Groups appear in the order their first listing appears; inside a group, listings follow [sourceOrder]
     * (sources not in it go last, in input order).
     */
    fun groupByTitle(listings: List<Listing>, sourceOrder: List<String>): List<List<Listing>> {
        fun rank(listing: Listing) = sourceOrder.indexOf(listing.sourceId).let { if (it < 0) Int.MAX_VALUE else it }
        return listings.groupBy { key(it.title) }.values.map { group -> group.sortedBy(::rank) }
    }

    /** Whether two titles are the same novel by the rule [groupByTitle] uses. */
    fun sameTitle(a: String, b: String): Boolean = key(a) == key(b)

    private fun key(title: String) = normalize(title).ifEmpty { title }

    // Apostrophes vanish ("Don't" is "Dont"); other punctuation is a word break ("Re-Zero" is "Re Zero").
    // A title with nothing left falls back to itself in groupByTitle, so "!!!" never merges with "???".
    private fun normalize(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase()
        .replace(trailingBrackets, "")
        .replace(apostrophes, "")
        .replace(punctuation, " ")
        .replace(whitespace, " ")
        .trim()
}
