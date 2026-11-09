package app.lantern.core

import java.text.Normalizer

/** A chapter of the book as it is now, before a switch: its title, and whether its text is already stored. */
data class OldChapter(val title: String, val fetched: Boolean)

/**
 * What a source switch does to the chapter list, as a list of rows in the order the book will show them.
 * [removed] holds the old indexes that go away (ascending). Every old index is in exactly one of a
 * [Row.Matched], a [Row.KeptOld] or [removed]; every new index is in exactly one [Row.Matched] or [Row.Added].
 */
data class SwitchPlan(val rows: List<Row>, val removed: List<Int>) {
    sealed interface Row {
        /** The old chapter [old] continues as the new source's chapter [new]. */
        data class Matched(val old: Int, val new: Int) : Row

        /** A chapter only the new source lists. */
        data class Added(val new: Int) : Row

        /** A chapter only the old source lists, kept because its text was fetched. */
        data class KeptOld(val old: Int) : Row
    }
}

object ChapterMatch {
    private val keywordNumber = Regex("""\b(?:chapter|chap|ch|episode|ep)\.?\s*(\d+)""")
    private val leadingNumber = Regex("""^\s*(\d+)""")
    private val apostrophes = Regex("['’]")
    private val punctuation = Regex("\\p{P}")
    private val whitespace = Regex("\\s+")

    /**
     * The chapter number a title states: the digits after "chapter", "ch", "episode" and the like ("Volume 2 Chapter 57"
     * is 57), otherwise a number the title starts with ("57 - The Gate"). Null for a prologue, a side story and so on.
     */
    fun number(title: String): Int? {
        val text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
        val digits = (keywordNumber.find(text) ?: leadingNumber.find(text))?.groupValues?.get(1)
        return digits?.toIntOrNull()
    }

    /**
     * The chapter list after a switch, in the new source's order. Chapters pair by number, in order of appearance when
     * a number repeats; chapters with no number on either side pair by equal [normalizedTitle]. Old chapters left over
     * stay if fetched, each right after the one that came before it on the old source, and go otherwise.
     */
    fun plan(old: List<OldChapter>, new: List<ChapterRef>): SwitchPlan {
        val oldNumbers = old.map { number(it.title) }
        val newNumbers = new.map { number(it.title) }
        val oldToNew = HashMap<Int, Int>()
        val newToOld = HashMap<Int, Int>()

        fun <K> pair(oldGroups: Map<K, List<Int>>, newGroups: Map<K, List<Int>>) {
            for ((key, olds) in oldGroups) {
                val news = newGroups[key] ?: continue
                for ((o, n) in olds.zip(news)) {
                    oldToNew[o] = n
                    newToOld[n] = o
                }
            }
        }

        pair(
            old.indices.filter { oldNumbers[it] != null }.groupBy { oldNumbers[it] },
            new.indices.filter { newNumbers[it] != null }.groupBy { newNumbers[it] },
        )
        pair(
            old.indices.filter { oldNumbers[it] == null }.groupBy { normalizedTitle(old[it].title) },
            new.indices.filter { newNumbers[it] == null }.groupBy { normalizedTitle(new[it].title) },
        )

        val rows = new.indices.mapTo(ArrayList()) { n ->
            newToOld[n]?.let { SwitchPlan.Row.Matched(it, n) } ?: SwitchPlan.Row.Added(n)
        }

        // Ascending, so a run of kept chapters finds the one before it already placed and keeps its order.
        val placed = BooleanArray(old.size) { it in oldToNew }
        for (o in old.indices) {
            if (placed[o] || !old[o].fetched) continue
            val before = (o - 1 downTo 0).firstOrNull { placed[it] }
            val at = if (before == null) 0 else rows.indexOfFirst { it.holds(before) } + 1
            rows.add(at, SwitchPlan.Row.KeptOld(o))
            placed[o] = true
        }

        val removed = old.indices.filter { it !in oldToNew && !old[it].fetched }
        return SwitchPlan(rows, removed)
    }

    private fun SwitchPlan.Row.holds(oldIndex: Int) = when (this) {
        is SwitchPlan.Row.Matched -> old == oldIndex
        is SwitchPlan.Row.KeptOld -> old == oldIndex
        is SwitchPlan.Row.Added -> false
    }

    // Apostrophes vanish ("Don't" is "Dont"); other punctuation is a word break ("Re-Zero" is "Re Zero").
    // A title with nothing left falls back to itself, so "!!!" never pairs with "???".
    private fun normalizedTitle(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase()
        .replace(apostrophes, "")
        .replace(punctuation, " ")
        .replace(whitespace, " ")
        .trim()
        .ifEmpty { title.trim() }
}
