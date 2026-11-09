package app.lantern.core

import app.lantern.core.SwitchPlan.Row.Added
import app.lantern.core.SwitchPlan.Row.KeptOld
import app.lantern.core.SwitchPlan.Row.Matched
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChapterMatchTest {
    private fun oldList(vararg titles: String, fetched: Set<Int> = emptySet()) =
        titles.mapIndexed { i, title -> OldChapter(title, i in fetched) }

    private fun refs(vararg titles: String) = titles.mapIndexed { i, title -> ChapterRef("/c/$i", title) }

    private fun chaptersUpTo(count: Int) = List(count) { "Chapter ${it + 1}" }

    /** Every new index once in new order, every old index exactly once as Matched, KeptOld or removed. */
    private fun assertCovers(old: List<OldChapter>, new: List<ChapterRef>, plan: SwitchPlan) {
        val newIndexes = plan.rows.mapNotNull { row ->
            when (row) {
                is Matched -> row.new
                is Added -> row.new
                is KeptOld -> null
            }
        }
        assertEquals(new.indices.toList(), newIndexes, "new chapters in new order")

        val oldIndexes = plan.rows.mapNotNull { row ->
            when (row) {
                is Matched -> row.old
                is KeptOld -> row.old
                is Added -> null
            }
        } + plan.removed
        assertEquals(old.indices.toList(), oldIndexes.sorted(), "every old chapter once")
        assertEquals(plan.removed.sorted(), plan.removed, "removed ascending")
    }

    private fun checkedPlan(old: List<OldChapter>, new: List<ChapterRef>) =
        ChapterMatch.plan(old, new).also { assertCovers(old, new, it) }

    // number

    @Test
    fun `a keyword followed by digits is the number`() {
        assertEquals(57, ChapterMatch.number("Chapter 57"))
        assertEquals(57, ChapterMatch.number("Ch. 57: Title"))
        assertEquals(57, ChapterMatch.number("Ch.57"))
        assertEquals(57, ChapterMatch.number("Chap 57 - Title"))
        assertEquals(57, ChapterMatch.number("Episode 57"))
        assertEquals(57, ChapterMatch.number("EP. 57"))
        assertEquals(57, ChapterMatch.number("CHAPTER 57"))
        assertEquals(57, ChapterMatch.number("Chapter 057"))
    }

    @Test
    fun `a leading number is the number`() {
        assertEquals(57, ChapterMatch.number("57 - The Gate"))
        assertEquals(57, ChapterMatch.number("  57. The Gate"))
        assertEquals(57, ChapterMatch.number("57"))
    }

    @Test
    fun `the keyword wins over a leading number and over other numbers`() {
        assertEquals(57, ChapterMatch.number("Volume 2 Chapter 57: The Gate"))
        assertEquals(57, ChapterMatch.number("3. Chapter 57"))
        assertEquals(57, ChapterMatch.number("Vol. 2 - Ch 57"))
    }

    @Test
    fun `a title with no number has none`() {
        assertNull(ChapterMatch.number("Prologue"))
        assertNull(ChapterMatch.number("Side Story: The Gate"))
        assertNull(ChapterMatch.number("Epilogue"))
        assertNull(ChapterMatch.number("Volume 2"))
        assertNull(ChapterMatch.number(""))
    }

    @Test
    fun `the keyword must start a word`() {
        assertNull(ChapterMatch.number("Epoch 5"))
        assertNull(ChapterMatch.number("Stretch 5"))
        assertNull(ChapterMatch.number("The Reach 12"))
    }

    @Test
    fun `numbers of more than three digits are read whole`() {
        assertEquals(1000, ChapterMatch.number("Chapter 1000"))
        assertEquals(1000, ChapterMatch.number("1000 - Title"))
        assertEquals(12345, ChapterMatch.number("Chapter 12345: Title"))
    }

    @Test
    fun `full-width digits and letters count as the plain ones`() {
        assertEquals(57, ChapterMatch.number("Chapter ５７"))
        assertEquals(57, ChapterMatch.number("Ｃｈａｐｔｅｒ ５７"))
        assertEquals(57, ChapterMatch.number("５７ - Title"))
    }

    @Test
    fun `a number too big for an Int is no number`() {
        assertNull(ChapterMatch.number("Chapter 99999999999999999999"))
    }

    // plan: matching

    @Test
    fun `identical lists match one to one`() {
        val titles = arrayOf("Prologue", "Chapter 1: Start", "Chapter 2: Middle", "Chapter 3: End")
        val plan = checkedPlan(oldList(*titles), refs(*titles))

        assertEquals(listOf(Matched(0, 0), Matched(1, 1), Matched(2, 2), Matched(3, 3)), plan.rows)
        assertEquals(emptyList(), plan.removed)
    }

    @Test
    fun `the same chapter numbered in a different style still matches`() {
        val plan = checkedPlan(
            oldList("Chapter 1: Start", "Chapter 2: Middle", "Episode 3"),
            refs("1 - Start", "Ch. 2 Middle", "Chapter 3 - End"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(1, 1), Matched(2, 2)), plan.rows)
    }

    @Test
    fun `a prologue matches by title`() {
        val plan = checkedPlan(
            oldList("Prologue", "Chapter 1", "Chapter 2"),
            refs("PROLOGUE:", "Chapter 1 - Start", "Chapter 2 - Middle"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(1, 1), Matched(2, 2)), plan.rows)
        assertEquals(emptyList(), plan.removed)
    }

    @Test
    fun `titles match ignoring case, apostrophes and punctuation`() {
        val plan = checkedPlan(
            oldList("Don't Stop", "Re-Zero", "Side   Story"),
            refs("DON’T STOP", "Re: Zero", "side story!"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(1, 1), Matched(2, 2)), plan.rows)
    }

    @Test
    fun `a title with different words does not match`() {
        val plan = checkedPlan(oldList("Prologue"), refs("Prelude"))

        assertEquals(listOf(Added(0)), plan.rows)
        assertEquals(listOf(0), plan.removed)
    }

    @Test
    fun `titles that are only punctuation match only themselves`() {
        val plan = checkedPlan(oldList("???", "!!!"), refs("!!!", "***"))

        assertEquals(listOf(Matched(1, 0), Added(1)), plan.rows)
        assertEquals(listOf(0), plan.removed)
    }

    @Test
    fun `a numbered chapter never matches an unnumbered one`() {
        val plan = checkedPlan(oldList("Chapter 5"), refs("Interlude"))

        assertEquals(listOf(Added(0)), plan.rows)
        assertEquals(listOf(0), plan.removed)
    }

    @Test
    fun `duplicate numbers match in order`() {
        val plan = checkedPlan(
            oldList("Chapter 11", "Chapter 12 part 1", "Chapter 12 part 2", "Chapter 13"),
            refs("Chapter 11", "Chapter 12 (1)", "Chapter 12 (2)", "Chapter 13"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(1, 1), Matched(2, 2), Matched(3, 3)), plan.rows)
    }

    @Test
    fun `a duplicate number the old side lacks is added`() {
        val plan = checkedPlan(
            oldList("Chapter 12"),
            refs("Chapter 12 part 1", "Chapter 12 part 2"),
        )

        assertEquals(listOf(Matched(0, 0), Added(1)), plan.rows)
    }

    @Test
    fun `a duplicate number the new side lacks is removed or kept by whether it was fetched`() {
        val unfetched = checkedPlan(oldList("Chapter 12 part 1", "Chapter 12 part 2"), refs("Chapter 12"))
        assertEquals(listOf(Matched(0, 0)), unfetched.rows)
        assertEquals(listOf(1), unfetched.removed)

        val fetched = checkedPlan(oldList("Chapter 12 part 1", "Chapter 12 part 2", fetched = setOf(1)), refs("Chapter 12"))
        assertEquals(listOf(Matched(0, 0), KeptOld(1)), fetched.rows)
        assertEquals(emptyList(), fetched.removed)
    }

    @Test
    fun `duplicate titles match in order`() {
        val plan = checkedPlan(
            oldList("Interlude", "Chapter 1", "Interlude"),
            refs("Interlude", "Interlude", "Chapter 1", "Interlude"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(2, 1), Matched(1, 2), Added(3)), plan.rows)
    }

    // plan: rows

    @Test
    fun `the new source's order wins when the sources order chapters differently`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3"),
            refs("Chapter 3", "Chapter 1", "Chapter 2"),
        )

        assertEquals(listOf(Matched(2, 0), Matched(0, 1), Matched(1, 2)), plan.rows)
    }

    @Test
    fun `chapters only on the new source are added where it lists them`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2"),
            refs("Chapter 1", "Side Story: The Cook", "Chapter 2", "Side Story: The Thief"),
        )

        assertEquals(listOf(Matched(0, 0), Added(1), Matched(1, 2), Added(3)), plan.rows)
        assertEquals(emptyList(), plan.removed)
    }

    // plan: chapters only on the old source

    @Test
    fun `old chapters the new source lacks are kept if fetched and removed if not`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5", "Chapter 6", fetched = setOf(2, 4)),
            refs("Chapter 1", "Chapter 2", "Chapter 4", "Chapter 6"),
        )

        assertEquals(
            listOf(Matched(0, 0), Matched(1, 1), KeptOld(2), Matched(3, 2), KeptOld(4), Matched(5, 3)),
            plan.rows,
        )
        assertEquals(emptyList(), plan.removed)
    }

    @Test
    fun `old chapters not fetched go to removed in ascending order`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5"),
            refs("Chapter 1", "Chapter 4"),
        )

        assertEquals(listOf(Matched(0, 0), Matched(3, 1)), plan.rows)
        assertEquals(listOf(1, 2, 4), plan.removed)
    }

    @Test
    fun `a kept chapter before every matched one goes at the start`() {
        val plan = checkedPlan(
            oldList("Chapter 0", "Chapter 1", "Chapter 2", fetched = setOf(0)),
            refs("Chapter 1", "Chapter 2"),
        )

        assertEquals(listOf(KeptOld(0), Matched(1, 0), Matched(2, 1)), plan.rows)
    }

    @Test
    fun `several kept chapters in a row keep their old order`() {
        val start = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", fetched = setOf(0, 1)),
            refs("Chapter 3", "Chapter 4"),
        )
        assertEquals(listOf(KeptOld(0), KeptOld(1), Matched(2, 0), Matched(3, 1)), start.rows)

        val end = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", fetched = setOf(2, 3)),
            refs("Chapter 1", "Chapter 2"),
        )
        assertEquals(listOf(Matched(0, 0), Matched(1, 1), KeptOld(2), KeptOld(3)), end.rows)
    }

    @Test
    fun `a kept chapter follows the nearest earlier old chapter that has a place`() {
        // Old chapter 2 is removed and has no place, so old chapter 3 goes after old chapter 1.
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", fetched = setOf(2)),
            refs("Chapter 1"),
        )

        assertEquals(listOf(Matched(0, 0), KeptOld(2)), plan.rows)
        assertEquals(listOf(1), plan.removed)
    }

    @Test
    fun `a kept chapter goes right after the row of its predecessor, wherever that is`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", fetched = setOf(1)),
            refs("Chapter 3", "Chapter 1"),
        )

        assertEquals(listOf(Matched(2, 0), Matched(0, 1), KeptOld(1)), plan.rows)
    }

    @Test
    fun `a kept chapter goes before an added chapter that follows its predecessor`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", fetched = setOf(1)),
            refs("Chapter 1", "Side Story: Extra"),
        )

        assertEquals(listOf(Matched(0, 0), KeptOld(1), Added(1)), plan.rows)
    }

    // plan: empty lists

    @Test
    fun `an empty old list adds every new chapter`() {
        val plan = checkedPlan(emptyList(), refs("Prologue", "Chapter 1"))

        assertEquals(listOf(Added(0), Added(1)), plan.rows)
        assertEquals(emptyList(), plan.removed)
    }

    @Test
    fun `an empty new list keeps fetched chapters and removes the rest`() {
        val plan = checkedPlan(
            oldList("Chapter 1", "Chapter 2", "Chapter 3", fetched = setOf(0, 2)),
            emptyList(),
        )

        assertEquals(listOf(KeptOld(0), KeptOld(2)), plan.rows)
        assertEquals(listOf(1), plan.removed)
    }

    @Test
    fun `two empty lists give an empty plan`() {
        assertEquals(SwitchPlan(emptyList(), emptyList()), ChapterMatch.plan(emptyList(), emptyList()))
    }

    // plan: the same input gives the same plan

    @Test
    fun `a messy mix covers every chapter and gives the same plan twice`() {
        val old = oldList(
            "Prologue", "Chapter 1", "Chapter 2 part 1", "Chapter 2 part 2", "Interlude", "Chapter 3",
            "Chapter 4", "Interlude", "Epilogue",
            fetched = setOf(0, 3, 6, 8),
        )
        val new = refs(
            "Interlude", "Chapter 1", "Chapter 2", "Side Story", "Chapter 4", "Chapter 5", "Interlude", "Chapter 3",
        )

        val first = checkedPlan(old, new)

        assertEquals(first, checkedPlan(old, new))
        assertEquals(
            listOf(
                KeptOld(0),
                Matched(4, 0),
                Matched(1, 1),
                Matched(2, 2),
                KeptOld(3),
                Added(3),
                Matched(6, 4),
                Added(5),
                Matched(7, 6),
                KeptOld(8),
                Matched(5, 7),
            ),
            first.rows,
        )
        assertEquals(emptyList(), first.removed)
    }

    // plan: the real lists of Ode of the Brave, titles only, as the two sources listed them

    private fun chapterList(name: String): List<String> {
        val stream = assertNotNull(javaClass.getResourceAsStream("/chapter-lists/$name"), "missing resource $name")
        return stream.bufferedReader().use { reader -> reader.readLines().filter { it.isNotBlank() } }
    }

    private val anf by lazy { chapterList("ode-of-the-brave-anf.net.txt") }
    private val novelBuddy by lazy { chapterList("ode-of-the-brave-novelbuddy.txt") }

    @Test
    fun `the real lists have the counts the sources gave`() {
        assertEquals(104, anf.size)
        assertEquals(105, novelBuddy.size)
        assertEquals((1..104).toList(), anf.map { ChapterMatch.number(it) })
        assertEquals((1..105).toList(), novelBuddy.map { ChapterMatch.number(it) })
    }

    @Test
    fun `switching Ode of the Brave from AllNovelFull to NovelBuddy matches all 104 and adds the 105th`() {
        val old = anf.mapIndexed { i, title -> OldChapter(title, fetched = i < 57) }
        val new = novelBuddy.mapIndexed { i, title -> ChapterRef("/nb/$i", title) }

        val plan = checkedPlan(old, new)

        assertEquals(104, plan.rows.count { it is Matched })
        assertEquals(listOf<SwitchPlan.Row>(Added(104)), plan.rows.filterIsInstance<Added>())
        assertEquals(0, plan.rows.count { it is KeptOld })
        assertEquals(emptyList(), plan.removed)

        val chapter57Old = anf.indexOf("Chapter 57")
        val chapter57New = novelBuddy.indexOf("Chapter 57")
        assertEquals(56, chapter57Old)
        assertEquals(Matched(chapter57Old, chapter57New), plan.rows[chapter57New])
        assertEquals(List(104) { Matched(it, it) }, plan.rows.take(104))
    }

    @Test
    fun `switching back from NovelBuddy to AllNovelFull matches 104 and drops or keeps the 105th`() {
        val new = anf.mapIndexed { i, title -> ChapterRef("/anf/$i", title) }

        val unfetched = checkedPlan(novelBuddy.map { OldChapter(it, fetched = false) }, new)
        assertEquals(List(104) { Matched(it, it) }, unfetched.rows)
        assertEquals(listOf(104), unfetched.removed)

        val fetched = checkedPlan(novelBuddy.map { OldChapter(it, fetched = true) }, new)
        assertEquals(List(104) { Matched(it, it) } + KeptOld(104), fetched.rows)
        assertEquals(emptyList(), fetched.removed)
    }
}
