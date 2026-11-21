package app.lantern.prepare

import android.content.Context
import android.os.BatteryManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where a prepare job is. [waiting] says why it is paused ("Paused while you listen"), or is null. */
data class PrepareState(val bookId: Long, val stage: Stage, val done: Int, val total: Int, val waiting: String?) {
    enum class Stage { STARTING, FETCHING, MAKING }
}

/**
 * Preparing audio ahead of time for chapters you choose (docs/decisions/prepared-audio.md). One job at a time for the
 * whole app: starting one replaces any other. Screens use this; [PrepareWorker] does the work.
 */
object Prepare {
    /** For [start]: every chapter from the book's position to its end. */
    const val REST_OF_BOOK = 0

    /** Rough minutes of making per chapter on the A26 (16 minutes of audio at RTF 0.33), for the battery warning. */
    const val MINUTES_PER_CHAPTER = 6

    internal const val WORK = "prepare"
    internal const val KEY_BOOK = "book"
    internal const val KEY_CHAPTERS = "chapters"
    internal const val KEY_STAGE = "stage"
    internal const val KEY_DONE = "done"
    internal const val KEY_TOTAL = "total"
    internal const val KEY_WAITING = "waiting"
    private const val BOOK_TAG = "book:"

    /** Prepares [chapters] chapters of [bookId] from its position, or [REST_OF_BOOK]. Replaces a job already running. */
    fun start(context: Context, bookId: Long, chapters: Int) {
        val request = OneTimeWorkRequestBuilder<PrepareWorker>()
            .setInputData(workDataOf(KEY_BOOK to bookId, KEY_CHAPTERS to chapters))
            .addTag("$BOOK_TAG$bookId")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** The job waiting or running, or null when there is none. */
    fun state(context: Context): Flow<PrepareState?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).map { infos ->
            infos.firstOrNull { !it.state.isFinished }?.let(::toState)
        }

    fun isCharging(context: Context): Boolean =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

    private fun toState(info: WorkInfo): PrepareState? {
        val bookId = info.tags.firstOrNull { it.startsWith(BOOK_TAG) }?.removePrefix(BOOK_TAG)?.toLongOrNull() ?: return null
        val p = info.progress
        val stage = p.getString(KEY_STAGE)?.let { runCatching { PrepareState.Stage.valueOf(it) }.getOrNull() }
        return PrepareState(
            bookId = bookId,
            stage = stage ?: PrepareState.Stage.STARTING,
            done = p.getInt(KEY_DONE, 0),
            total = p.getInt(KEY_TOTAL, 0),
            waiting = p.getString(KEY_WAITING),
        )
    }
}
