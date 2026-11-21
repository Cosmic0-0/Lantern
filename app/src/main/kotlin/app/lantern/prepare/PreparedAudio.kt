package app.lantern.prepare

import app.lantern.core.Position
import app.lantern.library.Library
import app.lantern.speech.Phonon
import app.lantern.speech.SentenceAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What audio is already made, for the book screen, the settings screen and the voice question. Reads files and the database only. */
class PreparedAudio(private val library: Library, private val audio: SentenceAudio) {
    /** [ready]: chapters from the book's position on whose every unskipped sentence has audio. [bytes]: their audio and any partly made. */
    data class BookAudio(val ready: Set<Long>, val bytes: Long)

    /** [chapters] with any audio in a voice, and its size. Phrases are left out. */
    data class VoiceAudio(val chapters: Int, val bytes: Long)

    suspend fun book(bookId: Long, voice: String): BookAudio = withContext(Dispatchers.IO) {
        val start = library.startOf(bookId) ?: return@withContext BookAudio(emptySet(), 0)
        val rules = library.rules(bookId)
        val ready = HashSet<Long>()
        var bytes = 0L
        for (chapter in library.chaptersFrom(start.chapterId, Int.MAX_VALUE)) {
            if (chapter.textHash == null) continue
            val speech = library.speech(chapter.id, rules)
            val made = audio.ready(voice, speech.key)
            if (made.isEmpty()) continue
            bytes += audio.bytes(voice, speech.key)
            val from = if (chapter.id == start.chapterId) start.sentence else 0
            if ((from until speech.sentences.size).all { it in speech.skipped || it in made }) ready += chapter.id
        }
        BookAudio(ready, bytes)
    }

    suspend fun voice(voice: String): VoiceAudio = withContext(Dispatchers.IO) {
        val keys = audio.chapterKeys(voice)
        VoiceAudio(keys.size, keys.sumOf { audio.bytes(voice, it) })
    }

    /**
     * Another voice with [position]'s chapter made from its sentence to the end, when [voice] doesn't have it. Null
     * otherwise, so playback can just start (docs/decisions/prepared-audio.md).
     */
    suspend fun preparedIn(position: Position, voice: String): String? = withContext(Dispatchers.IO) {
        val chapter = library.chapter(position.chapterId)?.takeIf { it.textHash != null } ?: return@withContext null
        val speech = library.speech(chapter.id, library.rules(chapter.bookId))
        fun complete(v: String): Boolean {
            val made = audio.ready(v, speech.key)
            return (position.sentence until speech.sentences.size).all { it in speech.skipped || it in made }
        }
        if (complete(voice)) null else Phonon.VOICES.firstOrNull { it != voice && complete(it) }
    }
}
