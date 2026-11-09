package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ListingsTest {
    private fun listing(source: String, title: String) = Listing(source, "/$source/${title.hashCode()}", title, null)

    private val order = listOf("a", "b", "c")

    @Test
    fun `empty input gives no groups`() {
        assertEquals(emptyList(), Listings.groupByTitle(emptyList(), order))
    }

    @Test
    fun `the same novel on different sources lands in one group`() {
        val plain = listing("a", "Shadow Slave")
        val bracket = listing("b", "shadow slave (Web Novel)")
        val punct = listing("c", "Shadow  Slave!")
        assertEquals(listOf(listOf(plain, bracket, punct)), Listings.groupByTitle(listOf(plain, bracket, punct), order))
    }

    @Test
    fun `a different number in the title is a different novel`() {
        val one = listing("a", "Shadow Slave")
        val two = listing("a", "Shadow Slave 2")
        assertEquals(listOf(listOf(one), listOf(two)), Listings.groupByTitle(listOf(one, two), order))
    }

    @Test
    fun `a trailing bracketed part is dropped but a middle one is not`() {
        val done = listing("a", "Omniscient Reader [Completed]")
        val plain = listing("b", "Omniscient Reader")
        val middle = listing("c", "Omniscient (Reader) Extra")
        assertEquals(
            listOf(listOf(done, plain), listOf(middle)),
            Listings.groupByTitle(listOf(done, plain, middle), order),
        )
    }

    @Test
    fun `more than one trailing part and full-width brackets are dropped`() {
        val a = listing("a", "Lord of Mysteries (Web Novel) [Completed]")
        val b = listing("b", "Lord of Mysteries（Web Novel）")
        assertEquals(listOf(listOf(a, b)), Listings.groupByTitle(listOf(a, b), order))
    }

    @Test
    fun `apostrophes are deleted and other punctuation becomes a space`() {
        val dont = listing("a", "Don't Stop")
        val curly = listing("b", "Don’t Stop")
        val plain = listing("c", "Dont Stop")
        assertEquals(listOf(listOf(dont, curly, plain)), Listings.groupByTitle(listOf(dont, curly, plain), order))

        val hyphen = listing("a", "Re-Zero")
        val colon = listing("b", "Re: Zero")
        val spaced = listing("c", "Re Zero")
        assertEquals(listOf(listOf(hyphen, colon, spaced)), Listings.groupByTitle(listOf(hyphen, colon, spaced), order))

        // A hyphen is not an apostrophe: "Re-Zero" does not become "Rezero".
        val joined = listing("a", "Rezero")
        assertEquals(listOf(listOf(joined), listOf(hyphen)), Listings.groupByTitle(listOf(joined, hyphen), order))
    }

    @Test
    fun `groups appear in the order their first listing appears`() {
        val x1 = listing("c", "X")
        val y1 = listing("a", "Y")
        val x2 = listing("a", "x")
        assertEquals(listOf(listOf(x2, x1), listOf(y1)), Listings.groupByTitle(listOf(x1, y1, x2), order))
    }

    @Test
    fun `inside a group listings follow the source order`() {
        val fromC = listing("c", "Book")
        val fromA = listing("a", "Book")
        val fromB = listing("b", "Book")
        assertEquals(listOf(listOf(fromA, fromB, fromC)), Listings.groupByTitle(listOf(fromC, fromA, fromB), order))
    }

    @Test
    fun `unknown sources go last in input order`() {
        val u1 = listing("zz", "Book")
        val fromB = listing("b", "Book")
        val u2 = listing("yy", "Book")
        val fromA = listing("a", "Book")
        assertEquals(
            listOf(listOf(fromA, fromB, u1, u2)),
            Listings.groupByTitle(listOf(u1, fromB, u2, fromA), order),
        )
    }

    @Test
    fun `a title that is only punctuation or only a bracket still groups with its own kind`() {
        val bang1 = listing("a", "!!!")
        val bang2 = listing("b", "!!!")
        val question = listing("a", "???")
        val bracket = listing("a", "(Web Novel)")
        assertEquals(
            listOf(listOf(bang1, bang2), listOf(question), listOf(bracket)),
            Listings.groupByTitle(listOf(bang1, question, bracket, bang2), order),
        )
    }
}
