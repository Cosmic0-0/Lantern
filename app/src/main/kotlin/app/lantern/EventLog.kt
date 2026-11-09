package app.lantern

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
 * Playback events, for the phone checks: logcat (tag "Lantern") and events.log in the app's external files folder,
 * which survives a reboot (logcat doesn't) and `adb pull` can reach. One tab-separated line per event: local time,
 * elapsedRealtime in ms, kind, message. Each line is one unbuffered append, so it is on disk even if the process
 * is killed a moment later. Ported from Spike 2; the file is cut back when it passes 2 MB.
 */
object EventLog {
    private const val TAG = "Lantern"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val lock = Any()
    private var file: File? = null
    private var out: FileOutputStream? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = context.applicationContext.getExternalFilesDir(null) ?: return
            file = File(dir, "events.log")
            open()
        }
    }

    fun log(kind: String, msg: String) {
        Log.i(TAG, "$kind $msg")
        val line = "${OffsetDateTime.now().format(timeFormat)}\t${SystemClock.elapsedRealtime()}\t$kind\t" +
            msg.replace('\n', ' ').replace('\t', ' ') + "\n"
        synchronized(lock) {
            try {
                if ((file?.length() ?: 0) > MAX_BYTES) {
                    out?.close()
                    file?.renameTo(File(file!!.parentFile, "events.old.log"))
                    open()
                }
                out?.write(line.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "cannot write events.log", e)
            }
        }
    }

    /** Screen state and process importance (100 foreground, 125 foreground service, 300 service, 400 cached). */
    fun state(ctx: Context): String {
        val screen = if (ctx.getSystemService(PowerManager::class.java).isInteractive) "on" else "off"
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return "screen=$screen imp=${info.importance}"
    }

    private fun open() {
        out = try {
            FileOutputStream(file, true)
        } catch (e: Exception) {
            Log.e(TAG, "cannot open events.log", e)
            null
        }
    }
}
