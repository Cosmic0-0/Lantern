package app.lantern.core

/** Where you are in a book: a chapter and a sentence in it. Reading and listening share one per book. */
data class Position(val bookId: Long, val chapterId: Long, val sentence: Int)
