package app.lantern.spike.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Spike 1: run Phonon on this phone and measure it. One run per process; the driver force-stops between runs. */
class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 16, 16, 16)
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(view)
        }
        setContentView(scroll)
        current = this

        val text = intent.getStringExtra("text") ?: "chapter.txt"
        val voice = intent.getStringExtra("voice") ?: "everly"
        val threads = intent.getIntExtra("threads", 2)
        val mode = intent.getStringExtra("mode") ?: "sentences"
        val label = intent.getStringExtra("label") ?: "${text.substringBeforeLast('.')}-$voice-${threads}t-$mode"

        if (text == "none.txt") {
            // The driver's install step launches this once so Android creates the external files dir.
            getExternalFilesDir(null)
            show("Initialised. Push the model and texts, then start a run with run.sh.")
        } else if (!started.compareAndSet(false, true)) {
            show("A run already started in this process, so this launch does nothing. See logcat (tag $TAG).")
        } else {
            val ctx = applicationContext
            Thread({ runSpike(ctx, text, voice, threads, mode, label) }, "spike-run").start()
        }
        refresh()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun refresh() {
        view.text = synchronized(screen) { screen.toString() }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val TAG = "LanternSpike"
        const val SAMPLE_RATE = 24_000

        val started = AtomicBoolean(false)
        val screen = StringBuilder()

        // The worker thread outlives the activity (rotation, Back), so what it shows is kept here.
        @Volatile
        var current: MainActivity? = null

        fun show(msg: String) {
            synchronized(screen) { screen.append(msg).append('\n') }
            current?.let { a -> a.runOnUiThread { a.refresh() } }
        }

        fun say(msg: String) {
            Log.i(TAG, msg)
            show(msg)
        }

        fun msSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1e6

        fun f(x: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", x)

        fun round(x: Double, digits: Int = 1): Double {
            val p = Math.pow(10.0, digits.toDouble())
            return Math.round(x * p) / p
        }

        fun batteryTempC(ctx: Context): Double? {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return if (t == Int.MIN_VALUE) null else t / 10.0
        }

        /** A kB figure from /proc/self/status, such as VmHWM. */
        fun procKb(key: String): Long? =
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }?.filter { it.isDigit() }?.toLongOrNull()
            }

        fun runSpike(ctx: Context, text: String, voice: String, threads: Int, mode: String, label: String) {
            var out: File? = null
            try {
                // Rayon reads this once, at the first model load, so it must be set before PhononTTS is touched.
                Os.setenv("RAYON_NUM_THREADS", threads.toString(), true)
                say("START $label threads=$threads")
                require(mode == "sentences" || mode == "whole") { "mode must be 'sentences' or 'whole', got '$mode'" }

                val base = ctx.getExternalFilesDir(null) ?: error("no external files dir")
                val outDir = File(base, "out").also { it.mkdirs() }
                out = outDir
                val tempStart = batteryTempC(ctx)

                // Read the text before loading, so a wrong file name fails in a moment rather than after the model load.
                val raw = File(base, "input/$text").readText(Charsets.UTF_8).removePrefix("﻿")
                val pieces = if (mode == "sentences") SentenceSplitter.split(raw) else listOf(raw.trim())
                require(pieces.any { it.isNotEmpty() }) { "no text to speak in input/$text" }

                val loadStart = System.nanoTime()
                val tts = PhononTTS(File(base, "model").path, "en")
                val loadMs = msSince(loadStart)
                try {
                    val voices = tts.voices
                    say("loaded in ${f(loadMs)} ms, voices: ${voices.joinToString()}")
                    require(voice in voices) { "voice '$voice' not found, available: ${voices.joinToString()}" }
                    tts.setVoice(voice)

                    var synthMs = 0.0
                    var samples = 0L
                    var chars = 0L
                    var maxRtf = 0.0
                    var slow = 0
                    var firstAudioMs = 0.0
                    File(outDir, "$label.sentences.tsv").bufferedWriter().use { tsv ->
                        WavWriter(File(outDir, "$label.wav")).use { wav ->
                            tsv.write("index\tchars\taudio_ms\twall_ms\tttfa_ms\trtf\ttext\n")
                            pieces.forEachIndexed { i, piece ->
                                // Audio is written as it arrives, so a whole-chapter piece never sits in memory.
                                // The write is a buffered copy of about 48 KB per second of audio, small next to synthesis.
                                var n = 0L
                                val start = System.nanoTime()
                                val res = tts.speak(piece) { pcm -> wav.write(pcm); n += pcm.size; true }
                                val wallMs = msSince(start)
                                if (n != res.samples) Log.w(TAG, "piece $i: got $n samples, Result says ${res.samples}")
                                val audioMs = n * 1000.0 / SAMPLE_RATE
                                val rtf = if (audioMs > 0) wallMs / audioMs else Double.NaN
                                if (audioMs > 0) maxRtf = maxOf(maxRtf, rtf)
                                if (wallMs > audioMs) slow++
                                if (i == 0) firstAudioMs = res.ttfaMs
                                synthMs += wallMs
                                samples += n
                                chars += piece.length

                                val flat = piece.replace(Regex("[\t\r\n]"), " ")
                                tsv.write("$i\t${piece.length}\t${f(audioMs)}\t${f(wallMs)}\t${f(res.ttfaMs)}\t${f(rtf, 3)}\t$flat\n")
                                if ((i + 1) % 10 == 0) {
                                    val audioS = samples.toDouble() / SAMPLE_RATE
                                    say("progress ${i + 1}/${pieces.size} audio=${f(audioS)}s synth=${f(synthMs / 1000)}s rtf=${f(synthMs / 1000 / audioS, 3)}")
                                }
                            }
                        }
                    }
                    val audioS = samples.toDouble() / SAMPLE_RATE
                    require(audioS > 0) { "the engine produced no audio" }

                    // Memory is read while the model is still loaded.
                    val json = JSONObject()
                        .put("label", label)
                        .put("text", text)
                        .put("voice", voice)
                        .put("threads", threads)
                        .put("mode", mode)
                        .put("chars", chars)
                        .put("pieces", pieces.size)
                        .put("load_ms", round(loadMs))
                        .put("synth_ms", round(synthMs))
                        .put("audio_s", round(audioS, 2))
                        .put("rtf", round(synthMs / 1000 / audioS, 4))
                        .put("first_audio_ms", round(firstAudioMs))
                        .put("max_piece_rtf", round(maxRtf, 4))
                        .put("slow_pieces", slow)
                        .put("vm_hwm_kb", procKb("VmHWM") ?: JSONObject.NULL)
                        .put("vm_rss_kb", procKb("VmRSS") ?: JSONObject.NULL)
                        .put("battery_temp_start_c", tempStart ?: JSONObject.NULL)
                        .put("battery_temp_end_c", batteryTempC(ctx) ?: JSONObject.NULL)
                        .put("device", Build.MODEL)
                        .put("time", OffsetDateTime.now().withNano(0).toString())
                    File(outDir, "results.jsonl").appendText(json.toString() + "\n")
                    Log.i(TAG, "DONE $json")
                    show("DONE\n${json.toString(2)}")
                } finally {
                    tts.close()
                }
            } catch (t: Throwable) {
                val trace = Log.getStackTraceString(t)
                Log.e(TAG, "FAIL $label $trace")
                show("FAIL $label\n$trace")
                try {
                    out?.let {
                        File(it, "results.jsonl").appendText(JSONObject().put("label", label).put("error", trace).toString() + "\n")
                    }
                } catch (_: Throwable) {
                    // Nothing more to do; the failure is already in logcat and on screen.
                }
            }
        }
    }
}

/** Streams 24 kHz mono 16-bit PCM into a WAV file: a placeholder header first, the sizes patched on close. */
private class WavWriter(private val file: File) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L

    init {
        out.write(ByteArray(HEADER_BYTES))
    }

    fun write(pcm: FloatArray) {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putShort(Math.round(x.coerceIn(-1f, 1f) * 32767f).toShort())
        out.write(buf.array())
        dataBytes += buf.capacity()
    }

    override fun close() {
        out.close()
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(24_000).putInt(24_000 * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use { it.write(h.array()) }
    }

    private companion object {
        const val HEADER_BYTES = 44
    }
}
