package app.lantern.spike.playback

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.RandomAccessFile

/** A thin remote control for PlaybackService and a view of events.log. The playback logic lives in the service. */
class MainActivity : Activity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            status.text = describe()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(16, 16, 16, 16)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("Start chapter 1") {
                controller?.sendCustomCommand(
                    SessionCommand("start_chapter", Bundle.EMPTY),
                    Bundle().apply { putInt("chapter", 1) },
                )
            })
            addView(button("Resume") {
                controller?.sendCustomCommand(SessionCommand("resume", Bundle.EMPTY), Bundle.EMPTY)
            })
            addView(button("Play/Pause") {
                controller?.let { if (it.playWhenReady) it.pause() else it.play() }
            })
            addView(button("Back 1 sentence") { controller?.seekToPreviousMediaItem() })
            addView(button("Forward 1 sentence") { controller?.seekToNextMediaItem() })
            addView(status)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = try { future.get() } catch (e: Exception) { null } }, mainExecutor)
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun describe(): String {
        val c = controller
        val head = if (c == null) {
            "not connected to the service yet"
        } else {
            val state = when (c.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            "item:  ${c.currentMediaItem?.mediaId ?: "-"}\n" +
                "text:  ${c.mediaMetadata.artist ?: "-"}\n" +
                "state: $state playWhenReady=${c.playWhenReady} playing=${c.isPlaying}"
        }
        return head + "\n\n" + tailOfLog(15)
    }

    /** The last [n] lines of events.log, reading only the end of the file. */
    private fun tailOfLog(n: Int): String {
        val file = File(getExternalFilesDir(null), "events.log")
        if (!file.isFile) return "(no events.log yet)"
        return try {
            RandomAccessFile(file, "r").use { f ->
                val size = f.length()
                val from = maxOf(0L, size - 8192)
                val bytes = ByteArray((size - from).toInt())
                f.seek(from)
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().filter { it.isNotEmpty() }.takeLast(n).joinToString("\n") {
                    // Drop the date, keep time, uptime and event so lines fit the screen.
                    it.substringAfter('T').replace('\t', ' ')
                }
            }
        } catch (e: Exception) {
            "(cannot read events.log: $e)"
        }
    }
}
