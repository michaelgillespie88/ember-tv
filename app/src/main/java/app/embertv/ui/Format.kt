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

private val CHANNEL_TAG_FRONT = Regex("^\\s*[\\[(]?(UHD|4K|FHD|HD|SD|HEVC|H\\.?265)[\\])]?\\s*[:|\\-–]+\\s*", RegexOption.IGNORE_CASE)
private val CHANNEL_TAG_BRACKET = Regex("^\\s*[\\[(](UHD|4K|FHD|HD|SD|HEVC)[\\])]\\s*", RegexOption.IGNORE_CASE)
private val CHANNEL_TAG_END = Regex("\\s*[\\[(|\\-–]?\\s*\\b(UHD|4K|FHD|HD|SD|HEVC)\\b\\s*[\\])]?\\s*$", RegexOption.IGNORE_CASE)

/**
 * A channel's name without the quality providers put in it, and that quality on its own:
 * "FHD : BBC One" → ("BBC One", "FHD"); "Sky Sports Main Event HD" → ("Sky Sports Main Event", "HD").
 */
fun channelParts(name: String): Pair<String, String?> {
    CHANNEL_TAG_FRONT.find(name)?.let { m ->
        val rest = name.substring(m.range.last + 1).trim()
        if (rest.isNotEmpty()) return rest to normaliseTag(m.groupValues[1])
    }
    CHANNEL_TAG_BRACKET.find(name)?.let { m ->
        val rest = name.substring(m.range.last + 1).trim()
        if (rest.isNotEmpty()) return rest to normaliseTag(m.groupValues[1])
    }
    CHANNEL_TAG_END.find(name)?.let { m ->
        val rest = name.substring(0, m.range.first).trim()
        if (rest.isNotEmpty()) return rest to normaliseTag(m.groupValues[1])
    }
    return name.trim() to null
}

private fun normaliseTag(t: String): String = when (t.uppercase()) {
    "UHD", "4K" -> "4K"
    "HEVC", "H265", "H.265" -> "FHD"
    else -> t.uppercase()
}

fun tagRank(t: String?): Int = when (t) {
    "4K" -> 4
    "FHD" -> 3
    "HD" -> 2
    "SD" -> 1
    else -> 0
}

/** A channel shown once in the guide, standing in for all its qualities. */
class GuideChannel(val channel: app.embertv.data.Channel, val name: String, val tag: String?, val copies: Int)

/**
 * Where to read a channel's TV listings from, when it's a quality copy without its own: providers
 * often attach the listings to just one copy (frequently the SD one). Filled in by
 * [combineChannelQualities]; [rememberProgrammes] uses it everywhere.
 */
object EpgAlias {
    private val map = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun of(streamId: Int): Int = map[streamId] ?: streamId
    fun set(streamId: Int, source: Int) {
        if (streamId != source) map[streamId] = source
    }
}

/**
 * The same channel in several qualities (BBC One FHD, HD and SD) shown as one row, playing the copy
 * that suits Settings → Quality to play: Full HD or HD when that's chosen, otherwise the best.
 * Only real quality copies are combined: channels that merely share a name, with no quality in
 * it, stay separate. With [combine] off every copy is listed, as the provider sends them.
 */
fun combineChannelQualities(channels: List<app.embertv.data.Channel>, qualityChoice: String, combine: Boolean = true): List<GuideChannel> {
    if (!combine) return channels.distinctBy { it.streamId }.map { c -> channelParts(c.name).let { (n, t) -> GuideChannel(c, n, t, 1) } }
    val groups = LinkedHashMap<String, MutableList<Pair<app.embertv.data.Channel, String?>>>()
    for (c in channels) {
        val (clean, tag) = channelParts(c.name)
        val key = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
        groups.getOrPut(key.ifEmpty { "id" + c.streamId }) { ArrayList() }.add(c to tag)
    }
    val want = when (qualityChoice) {
        "FHD" -> 3
        "HD" -> 2
        else -> null
    }
    val out = ArrayList<GuideChannel>()
    for (copies in groups.values) {
        // Two or more without a quality are different channels with the same name: keep them all.
        if (copies.size == 1 || copies.count { it.second == null } > 1) {
            copies.forEach { (c, t) -> out += GuideChannel(c, channelParts(c.name).first, t, 1) }
            continue
        }
        val pick = (if (want != null) copies.firstOrNull { tagRank(it.second) == want } else null)
            ?: copies.maxByOrNull { tagRank(it.second) }!!
        // Listings from the copy that has them, preferring the one being played.
        val guide = copies.firstOrNull { it.first.streamId == pick.first.streamId && it.first.epgId.isNotBlank() }
            ?: copies.firstOrNull { it.first.epgId.isNotBlank() }
        if (guide != null) EpgAlias.set(pick.first.streamId, guide.first.streamId)
        out += GuideChannel(pick.first, channelParts(pick.first.name).first, pick.second, copies.size)
    }
    // The very same channel listed twice would trip up the lists; show it once.
    return out.distinctBy { it.channel.streamId }
}

/** The same for every quality copy of a channel: "FHD : BBC One" and "SD : BBC ONE" both give "bbcone". */
fun channelKey(name: String): String = channelParts(name).first.lowercase().replace(Regex("[^a-z0-9]"), "")

/** Each channel once (see [combineChannelQualities]), as plain channels. */
fun channelsOnce(channels: List<app.embertv.data.Channel>, qualityChoice: String, combine: Boolean = true): List<app.embertv.data.Channel> =
    combineChannelQualities(channels, qualityChoice, combine).map { it.channel }
