package app.lantern.library

import app.lantern.core.ChapterRef
import app.lantern.core.Listing
import app.lantern.sources.Sources

/** The seam to the sources module, so tests can use a fake. Failures are [java.io.IOException]s. */
interface ChapterSource {
    suspend fun chapters(listing: Listing): List<ChapterRef>
    suspend fun chapterText(sourceId: String, path: String): String
}

class SourcesChapterSource(private val sources: Sources) : ChapterSource {
    override suspend fun chapters(listing: Listing) = sources.chapters(listing)
    override suspend fun chapterText(sourceId: String, path: String) = sources.chapterText(sourceId, path)
}
