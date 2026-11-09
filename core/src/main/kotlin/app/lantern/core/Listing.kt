package app.lantern.core

/** A novel as one source shows it: its title, cover and address on that source. */
data class Listing(val sourceId: String, val path: String, val title: String, val coverUrl: String?)

/** One chapter as its source lists it. The order of a list of these is the chapter order. */
data class ChapterRef(val path: String, val title: String)
