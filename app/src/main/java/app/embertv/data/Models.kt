package app.embertv.data

/** Virtual category ids used alongside the provider's own categories. */
const val ALL = "__all"
const val FAVOURITES = "__fav"

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
)

data class Movie(
    val streamId: Int,
    val name: String,
    val poster: String,
    val categoryId: String,
    val rating: String,
    val ext: String,
    val added: Long,
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
)

/** A guide entry. Times are epoch seconds. */
data class Programme(val title: String, val description: String, val start: Long, val end: Long) {
    fun isLive(now: Long): Boolean = now >= start && now < end

    fun progress(now: Long): Float =
        if (end > start) ((now - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) else 0f
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
