package app.lantern.speech

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.lantern.core.HeatGuard

/**
 * Whether the phone is too hot to make audio freely, shared by the playback generator and the prepare job
 * (docs/decisions/prepared-audio.md). Reads Android's thermal status and the battery temperature each time it is
 * asked; see [HeatGuard]. Android 9 has no thermal status, so there only the battery check applies.
 */
class Heat(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val guard = HeatGuard()

    @Synchronized
    fun isHot(): Boolean = guard.update(status(), batteryCelsius())

    /** Android's thermal status (0 none, 1 light, 2 moderate, 3 severe...), or null before Android 10. */
    fun status(): Int? = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null

    /** The battery temperature in °C, or null: a backup check in [HeatGuard], and for the logs. */
    fun batteryCelsius(): Float? {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }
}
