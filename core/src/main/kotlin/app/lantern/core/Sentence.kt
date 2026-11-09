package app.lantern.core

/** One sentence of chapter text and the paragraph it belongs to, 0-based within its chapter (docs/decisions/paragraphs.md). */
data class Sentence(val text: String, val paragraph: Int)
