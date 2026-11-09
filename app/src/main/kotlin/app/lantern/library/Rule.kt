package app.lantern.library

/** The two kinds of rule (docs/decisions/speech-rules.md). */
enum class RuleKind { SKIP, FIX }

/**
 * A skip rule or a pronunciation fix, as screens see it. [bookId] null means all books. [term] is the text a skip
 * rule looks for, or the word a fix replaces; [replacement] is how a fix should sound, and null for a skip rule.
 */
data class Rule(val id: Long, val bookId: Long?, val kind: RuleKind, val term: String, val replacement: String?)
