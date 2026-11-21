package app.lantern.ui

import java.util.Locale

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

/** Disk size for the prepared-audio screens: whole megabytes ("450 MB", rounded down), or from 1 GB one decimal ("1.1 GB"). */
fun formatSize(bytes: Long): String =
    if (bytes < GB) "${bytes / MB} MB" else String.format(Locale.ROOT, "%.1f GB", bytes.toDouble() / GB)

/** A length of time in words: "45 minutes", "1 hour 30 minutes". */
fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    val parts = buildList {
        if (hours > 0) add(counted(hours, "hour"))
        if (rest > 0 || hours == 0) add(counted(rest, "minute"))
    }
    return parts.joinToString(" ")
}

private fun counted(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
