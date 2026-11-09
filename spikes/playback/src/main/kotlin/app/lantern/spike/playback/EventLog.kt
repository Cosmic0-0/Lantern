package app.lantern.spike.playback

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Every event of the run goes to events.log in the app's files folder, one tab-separated line each:
 * local time, elapsedRealtime in ms, kind, message. Each line is a single unbuffered append, so it is
 * in the file even if the process is killed a moment later.
 */
object EventLog {
    private const val TAG = "LanternPlay"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val lock = Any()
    private var out: FileOutputStream? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (out != null) return
            val dir = context.applicationContext.getExternalFilesDir(null) ?: return
            out = try {
                FileOutputStream(File(dir, "events.log"), true)
            } catch (e: Exception) {
                Log.e(TAG, "cannot open events.log", e)
                null
            }
        }
    }

    fun log(kind: String, msg: String) {
        Log.i(TAG, "$kind $msg")
        val line = "${OffsetDateTime.now().format(timeFormat)}\t${SystemClock.elapsedRealtime()}\t$kind\t" +
            msg.replace('\n', ' ').replace('\t', ' ') + "\n"
        synchronized(lock) {
            try {
                out?.write(line.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "cannot write events.log", e)
            }
        }
    }

    /** Screen state and the process importance (100 foreground, 125 foreground service, 300 service, 400 cached). */
    fun state(ctx: Context): String {
        val screen = if (ctx.getSystemService(PowerManager::class.java).isInteractive) "on" else "off"
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return "screen=$screen imp=${info.importance}"
    }
}
