package app.embertv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * In-memory cache in front of the Xtream client. Call it from the main thread only:
 * the client does its network work on the IO dispatcher and hands results back.
 */
class Repository(val client: XtreamClient) {
    /** Hides categories the current profile shouldn't see. Lists are cached unfiltered. */
    var filter: (ContentType, String) -> Boolean = { _, _ -> true }

    private var liveCats: List<Category>? = null
    private var movieCats: List<Category>? = null
    private var seriesCats: List<Category>? = null

    private val liveByCat = HashMap<String, List<Channel>>()
    private val moviesByCat = HashMap<String, List<Movie>>()
    private val seriesByCat = HashMap<String, List<Series>>()

    private var allLive: List<Channel>? = null
    private var allMovies: List<Movie>? = null
    private var allSeries: List<Series>? = null

    private val movieInfos = HashMap<Int, MovieInfo>()
    private val seriesInfos = HashMap<Int, SeriesInfo>()

    private class EpgEntry(val fetchedAt: Long, val items: List<Programme>)

    private val epg = HashMap<Int, EpgEntry>()
    private val fullEpg = HashMap<Int, EpgEntry>()
    private val epgGate = Semaphore(6)

    fun clearCaches() {
        liveCats = null
        movieCats = null
        seriesCats = null
        liveByCat.clear()
        moviesByCat.clear()
        seriesByCat.clear()
        allLive = null
        allMovies = null
        allSeries = null
        movieInfos.clear()
        seriesInfos.clear()
        epg.clear()
        fullEpg.clear()
    }

    // Everything the provider offers, for managing categories.
    suspend fun rawLiveCategories(): List<Category> = liveCats ?: client.liveCategories().also { liveCats = it }
    suspend fun rawMovieCategories(): List<Category> = movieCats ?: client.movieCategories().also { movieCats = it }
    suspend fun rawSeriesCategories(): List<Category> = seriesCats ?: client.seriesCategories().also { seriesCats = it }

    // What the current profile is allowed to see.
    suspend fun liveCategories(): List<Category> = rawLiveCategories().filter { filter(ContentType.LIVE, it.id) }
    suspend fun movieCategories(): List<Category> = rawMovieCategories().filter { filter(ContentType.MOVIE, it.id) }
    suspend fun seriesCategories(): List<Category> = rawSeriesCategories().filter { filter(ContentType.SERIES, it.id) }

    private suspend fun rawAllChannels(): List<Channel> = allLive ?: client.channels(null).also { allLive = it }
    private suspend fun rawAllMovies(): List<Movie> = allMovies ?: client.movies(null).also { allMovies = it }
    private suspend fun rawAllSeries(): List<Series> = allSeries ?: client.series(null).also { allSeries = it }

    suspend fun allChannels(): List<Channel> = rawAllChannels().filter { filter(ContentType.LIVE, it.categoryId) }
    suspend fun allMovies(): List<Movie> = rawAllMovies().filter { filter(ContentType.MOVIE, it.categoryId) }
    suspend fun allSeries(): List<Series> = rawAllSeries().filter { filter(ContentType.SERIES, it.categoryId) }

    suspend fun channels(categoryId: String): List<Channel> {
        if (categoryId == ALL) return allChannels()
        if (!filter(ContentType.LIVE, categoryId)) return emptyList()
        liveByCat[categoryId]?.let { return it }
        val everything = allLive
        val result = if (everything != null) {
            everything.filter { it.categoryId == categoryId }
        } else {
            client.channels(categoryId).onlyCategory(categoryId) { it.categoryId }
        }
        liveByCat[categoryId] = result
        return result
    }

    suspend fun movies(categoryId: String): List<Movie> {
        if (categoryId == ALL) return allMovies()
        if (!filter(ContentType.MOVIE, categoryId)) return emptyList()
        moviesByCat[categoryId]?.let { return it }
        val everything = allMovies
        val result = if (everything != null) {
            everything.filter { it.categoryId == categoryId }
        } else {
            client.movies(categoryId).onlyCategory(categoryId) { it.categoryId }
        }
        moviesByCat[categoryId] = result
        return result
    }

    suspend fun series(categoryId: String): List<Series> {
        if (categoryId == ALL) return allSeries()
        if (!filter(ContentType.SERIES, categoryId)) return emptyList()
        seriesByCat[categoryId]?.let { return it }
        val everything = allSeries
        val result = if (everything != null) {
            everything.filter { it.categoryId == categoryId }
        } else {
            client.series(categoryId).onlyCategory(categoryId) { it.categoryId }
        }
        seriesByCat[categoryId] = result
        return result
    }

    fun peekMovieInfo(id: Int): MovieInfo? = movieInfos[id]
    fun peekSeriesInfo(id: Int): SeriesInfo? = seriesInfos[id]

    suspend fun movieInfo(id: Int): MovieInfo =
        movieInfos[id] ?: client.movieInfo(id).also { movieInfos[id] = it }

    suspend fun seriesInfo(id: Int): SeriesInfo =
        seriesInfos[id] ?: client.seriesInfo(id).also { seriesInfos[id] = it }

    /** Returns cached guide data if it is fresh and still covers the present. */
    fun cachedEpg(streamId: Int): List<Programme>? {
        val entry = epg[streamId] ?: return null
        val now = nowSeconds()
        val fresh = now - entry.fetchedAt < 900
        val stillCurrent = entry.items.isEmpty() || entry.items.last().end > now
        return if (fresh && stillCurrent) entry.items else null
    }

    suspend fun epg(streamId: Int): List<Programme> {
        cachedEpg(streamId)?.let { return it }
        val items = epgGate.withPermit {
            try {
                client.shortEpg(streamId, 12)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        epg[streamId] = EpgEntry(nowSeconds(), items)
        return items
    }

    fun cachedFullEpg(streamId: Int): List<Programme>? {
        val entry = fullEpg[streamId] ?: return null
        return if (nowSeconds() - entry.fetchedAt < 1800) entry.items else null
    }

    /** The whole guide for a channel, past and future. Falls back to the short guide. */
    suspend fun fullEpg(streamId: Int): List<Programme> {
        cachedFullEpg(streamId)?.let { return it }
        val items = epgGate.withPermit {
            try {
                client.fullEpg(streamId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }.ifEmpty { epg(streamId) }
        fullEpg[streamId] = EpgEntry(nowSeconds(), items)
        return items
    }

    /**
     * Channels saved as favourites or history don't remember whether they have catch-up, so look
     * them up in their category. Returns [channel] unchanged if it can't be found.
     */
    suspend fun resolve(channel: Channel): Channel {
        if (channel.hasArchive || channel.categoryId.isEmpty()) return channel
        val list = try {
            channels(channel.categoryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return list.firstOrNull { it.streamId == channel.streamId } ?: channel
    }

    suspend fun resolveAll(channels: List<Channel>): List<Channel> = channels.map { resolve(it) }

    /** Some panels ignore category_id and return everything, so filter defensively. */
    private inline fun <T> List<T>.onlyCategory(id: String, category: (T) -> String): List<T> {
        val matching = filter { category(it) == id }
        return if (matching.isEmpty() || matching.size == size) this else matching
    }
}
