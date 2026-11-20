package app.lantern.prepare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lantern.EventLog
import app.lantern.core.HeatGuard
import app.lantern.lantern
import app.lantern.library.Chapter
import app.lantern.model.ModelInstaller
import app.lantern.speech.Phonon
import app.lantern.speech.PhononTTS
import app.lantern.speech.SpeechWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.withLock

/**
 * Prepares audio for chapters you chose (docs/decisions/prepared-audio.md): first fetches the text of every chapter
 * in the range, then makes their audio in order from the book's position, into the cache playback reads.
 *
 * Runs in the foreground with a notification, since a background app gets almost no CPU (Spike 1). Waits while you
 * listen (playback makes its own audio then) and while the battery is hot. Every sentence is made under
 * [SpeechWork.lock] and checked on disk first, so a stopped job carries on where it was and never repeats work.
 */
class PrepareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.lantern
    private val library = app.library
    private val audio = app.audio
    private val heat = HeatGuard()
    private var title = ""
    private var tts: PhononTTS? = null

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(Prepare.KEY_BOOK, -1)
        val count = inputData.getInt(Prepare.KEY_CHAPTERS, Prepare.REST_OF_BOOK)
        val book = library.book(bookId) ?: return Result.failure()
        title = book.title
        report(PrepareState.Stage.STARTING, 0, 0, null)
        val start = library.startOf(bookId) ?: return Result.success()
        val chapters = library.chaptersFrom(start.chapterId, if (count > 0) count else Int.MAX_VALUE)
        log("start book=$bookId chapters=${chapters.size} from chapter=${start.chapterId} sentence=${start.sentence}")

        return try {
            val failed = fetch(chapters)
            val voice = app.settings.voice
            for ((i, chapter) in chapters.withIndex()) {
                report(PrepareState.Stage.MAKING, i, chapters.size, null)
                if (!make(chapter, if (i == 0) start.sentence else 0, voice, i, chapters.size)) {
                    finish("Stopped: the voice changed")
                    return Result.success()
                }
            }
            val made = chapters.size - failed
            finish(if (failed == 0) "$made chapters ready" else "$made chapters ready, $failed couldn't be fetched")
            Result.success()
        } catch (e: CancellationException) {
            log("stopped")
            throw e
        } finally {
            tts?.close()
            tts = null
        }
    }

    /** Fetches every chapter without text, one at a time and a little apart. Returns how many failed. */
    private suspend fun fetch(chapters: List<Chapter>): Int {
        var failed = 0
        for ((i, chapter) in chapters.withIndex()) {
            if (library.chapter(chapter.id)?.textHash != null) continue
            report(PrepareState.Stage.FETCHING, i, chapters.size, null)
            if (!fetchWithRetries(chapter.id)) failed++
            delay(FETCH_GAP_MS)
        }
        log("fetched failed=$failed")
        return failed
    }

    private suspend fun fetchWithRetries(chapterId: Long): Boolean {
        for (attempt in 1..FETCH_TRIES) {
            try {
                library.chapterText(chapterId)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("fetch of chapter $chapterId failed, try $attempt of $FETCH_TRIES: $e")
                if (attempt < FETCH_TRIES) delay(FETCH_RETRY_MS)
            }
        }
        return false
    }

    /** Makes the chapter's missing audio from sentence [from]. Returns false if the voice changed meanwhile. */
    private suspend fun make(chapter: Chapter, from: Int, voice: String, done: Int, total: Int): Boolean {
        if (library.chapter(chapter.id)?.textHash == null) return true // its fetch failed
        val rules = library.rules(chapter.bookId)
        val speech = library.speech(chapter.id, rules)
        for (index in from until speech.sentences.size) {
            if (index in speech.skipped || audio.isReady(voice, speech.key, index)) continue
            waitUntilFree(done, total)
            if (app.settings.voice != voice) return false
            val text = rules.spoken(speech.sentences[index].text)
            withContext(Dispatchers.IO) {
                SpeechWork.lock.withLock {
                    if (!audio.isReady(voice, speech.key, index)) speak(voice, speech.key, index, text, chapter.id)
                }
            }
        }
        return true
    }

    /** Waits while you listen or the battery is hot, saying so in the notification. */
    private suspend fun waitUntilFree(done: Int, total: Int) {
        var said: String? = null
        while (true) {
            val celsius = batteryCelsius()
            val why = when {
                SpeechWork.listening.value -> "Paused while you listen"
                heat.update(celsius) -> "Paused: phone is hot (${"%.0f".format(Locale.ROOT, celsius)} °C)"
                else -> null
            }
            if (why != said) {
                log(why ?: "carrying on")
                report(PrepareState.Stage.MAKING, done, total, why)
                said = why
            }
            if (why == null) return
            delay(WAIT_MS)
        }
    }

    /** Blocking, under the lock. */
    private fun speak(voice: String, key: String, index: Int, text: String, chapterId: Long) {
        val engine = tts ?: Phonon.load(ModelInstaller.ensureInstalled(applicationContext), voice).also { tts = it }
        engine.setVoice(voice)
        val start = System.nanoTime()
        val samples = audio.write(voice, key, index, onError = { e ->
            log("error chapter=$chapterId sentence=$index $e text=${text.take(60)}")
        }) { w -> engine.speak(text) { pcm -> w.write(pcm); true } }
        val wallMs = (System.nanoTime() - start) / 1e6
        val audioMs = samples * 1000.0 / Phonon.SAMPLE_RATE
        log(
            "chapter=$chapterId sentence=$index wall_ms=${"%.0f".format(Locale.ROOT, wallMs)} " +
                "audio_ms=${"%.0f".format(Locale.ROOT, audioMs)} rtf=${"%.3f".format(Locale.ROOT, wallMs / audioMs)} " +
                "temp=${batteryCelsius()}",
        )
    }

    private fun batteryCelsius(): Float? {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private suspend fun report(stage: PrepareState.Stage, done: Int, total: Int, waiting: String?) {
        setProgress(
            workDataOf(
                Prepare.KEY_STAGE to stage.name,
                Prepare.KEY_DONE to done,
                Prepare.KEY_TOTAL to total,
                Prepare.KEY_WAITING to waiting,
            ),
        )
        val text = waiting ?: when (stage) {
            PrepareState.Stage.STARTING -> "Starting"
            PrepareState.Stage.FETCHING -> "Fetching chapter text: ${done + 1} of $total"
            PrepareState.Stage.MAKING -> "$done of $total chapters ready"
        }
        setForeground(foregroundInfo(text))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Starting")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Preparing $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** A notification that stays after the job ends, saying how it went. */
    private fun finish(text: String) {
        log("done: $text")
        val notification = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Prepared $title")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun channel(): String {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing audio", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun log(message: String) = EventLog.log("PREPARE", message)

    private companion object {
        const val CHANNEL = "prepare"
        const val NOTIFICATION_ID = 7301
        const val DONE_NOTIFICATION_ID = 7302
        const val FETCH_GAP_MS = 2_000L
        const val FETCH_RETRY_MS = 30_000L
        const val FETCH_TRIES = 3
        const val WAIT_MS = 10_000L
    }
}
