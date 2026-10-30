package app.embertv.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFormat = SimpleDateFormat("HH:mm", Locale.UK)
private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.UK)

/** Epoch seconds to "21:30" in the TV's time zone. */
fun formatClock(epochSeconds: Long): String = clockFormat.format(Date(epochSeconds * 1000))

fun formatDate(epochSeconds: Long): String = dateFormat.format(Date(epochSeconds * 1000))

private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.UK)

/** Local midnight at the start of the day containing [epochSeconds]. */
fun startOfDay(epochSeconds: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = epochSeconds * 1000
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

/** "Today", "Yesterday", "Tomorrow" or "Tue 28 Oct". */
fun formatDay(epochSeconds: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val days = Math.round((startOfDay(epochSeconds) - startOfDay(now)) / 86_400.0).toInt()
    return when (days) {
        0 -> "Today"
        -1 -> "Yesterday"
        1 -> "Tomorrow"
        else -> dayFormat.format(Date(epochSeconds * 1000))
    }
}

/** Milliseconds to "1:02:03" or "4:05". */
fun formatMs(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.UK, "%d:%02d", m, s)
}

/** Provider durations usually look like "01:52:31"; show them as "1h 52m". */
fun prettyDuration(raw: String): String {
    val parts = raw.split(":").map { it.trim().toIntOrNull() }
    if (parts.any { it == null }) return raw
    val p = parts.filterNotNull()
    return when (p.size) {
        3 -> if (p[0] > 0) "${p[0]}h ${p[1]}m" else "${p[1]}m"
        2 -> if (p[0] >= 60) "${p[0] / 60}h ${p[0] % 60}m" else "${p[0]}m"
        else -> raw
    }
}

/** "01:52:31" or "45:10" to seconds, or null if it isn't a time. */
fun durationSeconds(raw: String): Long? {
    val parts = raw.split(":").map { it.trim().toLongOrNull() ?: return null }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> null
    }
}

fun ratingLabel(raw: String): String? {
    val value = raw.toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    return "★ " + String.format(Locale.UK, "%.1f", value)
}

fun initials(name: String): String {
    val words = name.replace(Regex("^[A-Z]{2,3}\\s*[:|\\-]\\s*"), "")
        .split(' ', '-', '|', ':')
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
}
