package app.embertv.data

/** Virtual category ids used alongside the provider's own categories. */
const val ALL = "__all"
const val FAVOURITES = "__fav"
/** The newest films or series from the provider, newest first. */
const val RECENT = "__recent"

fun nowSeconds(): Long = System.currentTimeMillis() / 1000

data class Account(val server: String, val username: String, val password: String)

data class AccountInfo(
    val status: String,
    val expiry: Long?,
    val maxConnections: String,
    val activeConnections: String,
    val formats: List<String>,
    val serverTimezone: String,
)

data class Category(val id: String, val name: String)

enum class ContentType { LIVE, MOVIE, SERIES }

data class Channel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val icon: String,
    val categoryId: String,
    val epgId: String,
    val hasArchive: Boolean,
    /** How many days of catch-up the provider keeps (0 when it doesn't say). */
    val archiveDays: Int = 0,
)

data class Movie(
    val streamId: Int,
    val name: String,
    val poster: String,
    val categoryId: String,
    val rating: String,
    val ext: String,
    val added: Long,
    /** Its quality, when known (from the title or its category). */
    val quality: Quality = Quality.UNKNOWN,
    /** The other copies of this film in other qualities, best first (empty if there's only one). */
    val versions: List<Movie> = emptyList(),
)

data class Series(
    val seriesId: Int,
    val name: String,
    val cover: String,
    val categoryId: String,
    val plot: String,
    val genre: String,
    val rating: String,
    val year: String,
    /** When the provider last added to it (epoch seconds), for Recently Added. */
    val updated: Long = 0L,
    val quality: Quality = Quality.UNKNOWN,
    /** The same show in other qualities, best first (empty if there's only one). */
    val versions: List<Series> = emptyList(),
)

data class MovieInfo(
    val plot: String,
    val genre: String,
    val cast: String,
    val director: String,
    val year: String,
    val duration: String,
    val rating: String,
    val backdrop: String,
    val poster: String,
    /** YouTube video id of the trailer, when the provider has one. */
    val trailer: String = "",
    /** The film's id on TMDB, when the provider says. */
    val tmdbId: Int = 0,
)

data class Episode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val duration: String,
)

data class SeriesInfo(
    val plot: String,
    val genre: String,
    val cast: String,
    val year: String,
    val rating: String,
    val backdrop: String,
    val cover: String,
    val seasons: List<Int>,
    val episodes: Map<Int, List<Episode>>,
    val trailer: String = "",
    val tmdbId: Int = 0,
)

/** A guide entry. Times are epoch seconds. */
data class Programme(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    /** Whether the provider says this one is on catch-up; null when the guide doesn't say. */
    val archive: Boolean? = null,
    /** The start time exactly as the provider wrote it ("2026-10-30 21:00:00"), for catch-up links. */
    val serverStart: String = "",
) {
    fun isLive(now: Long): Boolean = now >= start && now < end

    fun progress(now: Long): Float =
        if (end > start) ((now - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) else 0f
}

/** Whether [p] on [channel] can be watched from catch-up (it has started and is still kept). */
fun catchupAvailable(channel: Channel, p: Programme, now: Long): Boolean {
    if (!channel.hasArchive || p.start >= now) return false
    if (p.isLive(now)) return true
    val days = if (channel.archiveDays > 0) channel.archiveDays else 7
    if (p.start < now - days * 86_400L) return false
    return p.archive ?: true
}

/** A programme someone asked to be reminded about. Saved per profile. */
data class Reminder(
    val streamId: Int,
    val channelName: String,
    val channelIcon: String,
    val categoryId: String,
    val num: Int,
    val title: String,
    val start: Long,
    val end: Long,
) {
    val key: String get() = "$streamId:$start"

    fun toChannel(): Channel = Channel(streamId, num, channelName, channelIcon, categoryId, "", false)
}

data class Favourite(
    val type: ContentType,
    val id: Int,
    val name: String,
    val image: String,
    val categoryId: String = "",
    val ext: String = "",
    val num: Int = 0,
) {
    val key: String get() = "${type.name}:$id"
}

fun favouriteKey(type: ContentType, id: Int): String = "${type.name}:$id"

fun Favourite.toChannel(): Channel = Channel(id, num, name, image, categoryId, "", false)

fun Favourite.toMovie(): Movie = Movie(id, name, image, categoryId, "", ext.ifEmpty { "mp4" }, 0L)

fun Favourite.toSeries(): Series = Series(id, name, image, categoryId, "", "", "", "")

/** Something part-watched, for the Continue Watching row. One entry per movie and per series. */
data class HistoryEntry(
    val key: String,
    val type: ContentType,
    val id: Int,
    val seriesId: Int,
    val title: String,
    val subtitle: String,
    val image: String,
    val seriesCover: String,
    val ext: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

/** The first profile. It keeps the data saved before profiles existed. */
const val MAIN_PROFILE = "main"

/**
 * A person using the app: their own favourites, Continue Watching and resume points.
 * Kids profiles only see the categories chosen for them; [pinHash] locks a profile behind a PIN.
 */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val kids: Boolean = false,
    val pinHash: String = "",
) {
    val locked: Boolean get() = pinHash.isNotEmpty()
}

/**
 * Which categories a profile sees. Normal profiles list the ones they've hidden; kids profiles
 * list the ones they're allowed, so anything new from the provider stays hidden from children.
 */
data class CategoryRules(val allowMode: Boolean, val keys: Set<String>) {
    fun visible(type: ContentType, categoryId: String): Boolean {
        val key = categoryRuleKey(type, categoryId)
        return if (allowMode) key in keys else key !in keys
    }
}

fun categoryRuleKey(type: ContentType, categoryId: String): String = "${type.name}:$categoryId"

private val KIDS_WORDS = listOf(
    "kid", "child", "cartoon", "family", "junior", "jr", "animation", "animated", "anime", "disney", "pixar",
    "dreamworks", "nick", "boomerang", "cbeebies", "cbbc", "baby", "toon", "pop", "tiny", "school",
)

/** PINs are never saved as typed, only as a salted SHA-256 of the digits. */
fun pinHash(profileId: String, pin: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest("ember:$profileId:$pin".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** A sensible starting point for a kids profile: categories whose names sound child-friendly. */
fun looksKidsFriendly(name: String): Boolean {
    val n = name.lowercase()
    if (listOf("adult", "xxx", "18+", "+18", "porn", "erotic").any { n.contains(it) }) return false
    return KIDS_WORDS.any { w -> Regex("\\b" + Regex.escape(w)).containsMatchIn(n) }
}
