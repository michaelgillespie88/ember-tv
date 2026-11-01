package app.embertv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache in front of the Xtream client. Call it from the main thread only:
 * the client does its network work on the IO dispatcher and hands results back.
 */
class Repository(val client: XtreamClient) {
    /** Hides categories the current profile shouldn't see. Lists are cached unfiltered. */
    var filter: (ContentType, String) -> Boolean = { _, _ -> true }
        set(value) {
            field = value
            mergedMovies.clear()
            mergedSeries.clear()
        }

    /** Show each film or show once, with its HD/FHD/4K copies as versions. */
    var mergeVersions: Boolean = true
        set(value) {
            field = value
            mergedMovies.clear()
            mergedSeries.clear()
        }
    private val mergedMovies = HashMap<String, List<Movie>>()
    private val mergedSeries = HashMap<String, List<Series>>()

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
    private val epgGate = Semaphore(2)

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
        mergedMovies.clear()
        mergedSeries.clear()
        movieIndex = null
        seriesIndex = null
        channelSearch = null
        movieSearch = null
        seriesSearch = null
        epg.clear()
        fullEpg.clear()
    }

    /** New films and series found by [refreshFilmsAndSeries]. -1 when there was nothing to compare with. */
    class Fresh(val newMovies: Int, val newSeries: Int)

    /**
     * Fetches the latest film and series lists and swaps them in, so new additions appear without
     * signing in again or pressing Refresh. Lists that haven't been opened yet are just dropped, to
     * be fetched fresh when they are. Channels and the guide are left alone.
     */
    suspend fun refreshFilmsAndSeries(): Fresh {
        // Fetch everything first and only then swap, so a failure part-way leaves the old lists in place.
        val oldMovies = allMovies
        val oldSeries = allSeries
        val newMovieCats = if (movieCats != null) client.movieCategories() else null
        val newMovies = if (oldMovies != null) client.movies(null) else null
        val newSeriesCats = if (seriesCats != null) client.seriesCategories() else null
        val newSeries = if (oldSeries != null) client.series(null) else null

        val (movieCount, seriesCount) = withContext(Dispatchers.Default) {
            val m = if (oldMovies != null && newMovies != null) {
                val had = oldMovies.mapTo(HashSet(oldMovies.size)) { it.streamId }
                newMovies.count { it.streamId !in had }
            } else -1
            val s = if (oldSeries != null && newSeries != null) {
                val had = oldSeries.mapTo(HashSet(oldSeries.size)) { it.seriesId }
                newSeries.count { it.seriesId !in had }
            } else -1
            m to s
        }

        movieCats = newMovieCats
        allMovies = newMovies
        moviesByCat.clear()
        mergedMovies.clear()
        movieIndex = null
        movieSearch = null
        seriesCats = newSeriesCats
        allSeries = newSeries
        seriesByCat.clear()
        mergedSeries.clear()
        seriesIndex = null
        seriesSearch = null
        // Episode lists too, so a show's new episodes turn up.
        seriesInfos.clear()
        return Fresh(movieCount, seriesCount)
    }

    // Everything the provider offers, for managing categories.
    suspend fun rawLiveCategories(): List<Category> = liveCats ?: client.liveCategories().also { liveCats = it }
    suspend fun rawMovieCategories(): List<Category> = movieCats ?: client.movieCategories().also { movieCats = it }
    suspend fun rawSeriesCategories(): List<Category> = seriesCats ?: client.seriesCategories().also { seriesCats = it }

    // What the current profile is allowed to see.
    suspend fun liveCategories(): List<Category> = rawLiveCategories().filter { filter(ContentType.LIVE, it.id) }
    suspend fun movieCategories(): List<Category> = rawMovieCategories().filter { filter(ContentType.MOVIE, it.id) }
    suspend fun seriesCategories(): List<Category> = rawSeriesCategories().filter { filter(ContentType.SERIES, it.id) }

    // The full lists are big. If several parts of the app ask at once (Home, search, a film's
    // qualities), they wait for the one download instead of each starting another.
    private val liveGate = kotlinx.coroutines.sync.Mutex()
    private val moviesGate = kotlinx.coroutines.sync.Mutex()
    private val seriesGate = kotlinx.coroutines.sync.Mutex()

    private suspend fun rawAllChannels(): List<Channel> = allLive ?: liveGate.withLock { allLive ?: client.channels(null).also { allLive = it } }
    private suspend fun rawAllMovies(): List<Movie> = allMovies ?: moviesGate.withLock { allMovies ?: client.movies(null).also { allMovies = it } }
    private suspend fun rawAllSeries(): List<Series> = allSeries ?: seriesGate.withLock { allSeries ?: client.series(null).also { allSeries = it } }

    /** Channels the profile has hidden. The cached lists keep them; only what's handed out leaves them out. */
    var hiddenChannels: Set<Int> = emptySet()

    suspend fun allChannels(): List<Channel> = rawAllChannels().filter { filter(ContentType.LIVE, it.categoryId) && it.streamId !in hiddenChannels }
    suspend fun allMovies(): List<Movie> = movies(ALL)
    suspend fun allSeries(): List<Series> = series(ALL)

    suspend fun channels(categoryId: String): List<Channel> {
        val all = channelsIncludingHidden(categoryId)
        return if (hiddenChannels.isEmpty()) all else all.filter { it.streamId !in hiddenChannels }
    }

    /** A group's channels with hidden ones still in, for choosing what to hide. */
    suspend fun channelsIncludingHidden(categoryId: String): List<Channel> {
        if (categoryId == ALL) return rawAllChannels().filter { filter(ContentType.LIVE, it.categoryId) }
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

    private suspend fun movieCategoryNames(): Map<String, String> =
        try {
            rawMovieCategories().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }

    private suspend fun seriesCategoryNames(): Map<String, String> =
        try {
            rawSeriesCategories().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }

    /** Movies in a category, each film once with its other qualities as [Movie.versions]. */
    suspend fun movies(categoryId: String): List<Movie> {
        if (categoryId == RECENT) {
            val all = movies(ALL)
            return withContext(Dispatchers.Default) { all.filter { it.added > 0 }.sortedByDescending { it.added }.take(RECENT_LIMIT) }
        }
        val raw = rawMovies(categoryId)
        if (!mergeVersions) return raw
        mergedMovies[categoryId]?.let { return it }
        val names = movieCategoryNames()
        val merged = withContext(Dispatchers.Default) { Versions.mergeMovies(raw, names) }
        mergedMovies[categoryId] = merged
        return merged
    }

    suspend fun series(categoryId: String): List<Series> {
        if (categoryId == RECENT) {
            val all = series(ALL)
            return withContext(Dispatchers.Default) { all.filter { it.updated > 0 }.sortedByDescending { it.updated }.take(RECENT_LIMIT) }
        }
        val raw = rawSeries(categoryId)
        if (!mergeVersions) return raw
        mergedSeries[categoryId]?.let { return it }
        val names = seriesCategoryNames()
        val merged = withContext(Dispatchers.Default) { Versions.mergeSeries(raw, names) }
        mergedSeries[categoryId] = merged
        return merged
    }

    /** Every film already loaded, grouped with its other qualities (Home rows, categories, recently added). */
    fun loadedMovieGroups(): List<Movie> = mergedMovies.values.flatten()

    fun loadedSeriesGroups(): List<Series> = mergedSeries.values.flatten()

    /** Every film already loaded (from any list), for matching suggestions. */
    fun loadedMovies(): List<Movie> =
        ((allMovies ?: emptyList()) + moviesByCat.values.flatten()).filter { filter(ContentType.MOVIE, it.categoryId) }

    fun loadedSeries(): List<Series> =
        ((allSeries ?: emptyList()) + seriesByCat.values.flatten()).filter { filter(ContentType.SERIES, it.categoryId) }

    // ---- Search ----
    // Each full list with its names already tidied for matching, built once in the background, so
    // each letter typed only scans plain text. Only the matches are then grouped into qualities.

    private class SearchList<T>(val items: List<T>, val names: Array<String>)

    @Volatile private var channelSearch: SearchList<Channel>? = null
    @Volatile private var movieSearch: SearchList<Movie>? = null
    @Volatile private var seriesSearch: SearchList<Series>? = null

    private suspend fun <T> searchList(items: List<T>, name: (T) -> String): SearchList<T> =
        withContext(Dispatchers.Default) { SearchList(items, Array(items.size) { MatchFinder.normalize(name(items[it])) }) }

    private suspend fun channelSearchList(): SearchList<Channel> =
        channelSearch ?: searchList(rawAllChannels()) { it.name }.also { channelSearch = it }

    private suspend fun movieSearchList(): SearchList<Movie> =
        movieSearch ?: searchList(rawAllMovies()) { it.name }.also { movieSearch = it }

    private suspend fun seriesSearchList(): SearchList<Series> =
        seriesSearch ?: searchList(rawAllSeries()) { it.name }.also { seriesSearch = it }

    /** Downloads what search needs ahead of time (the Search page calls this when it opens). */
    suspend fun prepareSearch(kind: ContentType) {
        when (kind) {
            ContentType.LIVE -> channelSearchList()
            ContentType.MOVIE -> movieSearchList()
            ContentType.SERIES -> seriesSearchList()
        }
    }

    private fun <T> scan(list: SearchList<T>, q: String, keep: (T) -> Boolean): List<T> {
        val starts = ArrayList<T>()
        val contains = ArrayList<T>()
        for (i in list.items.indices) {
            val n = list.names[i]
            if (!n.contains(q)) continue
            val item = list.items[i]
            if (!keep(item)) continue
            // Titles starting with what was typed come first.
            if (n.startsWith(q) || n.contains(" $q")) starts += item else contains += item
        }
        return starts + contains
    }

    suspend fun searchChannels(query: String, limit: Int = 60): List<Channel> {
        val q = MatchFinder.normalize(query)
        if (q.isEmpty()) return emptyList()
        val list = channelSearchList()
        return withContext(Dispatchers.Default) {
            scan(list, q) { filter(ContentType.LIVE, it.categoryId) && it.streamId !in hiddenChannels }.take(limit)
        }
    }

    suspend fun searchMovies(query: String, limit: Int = 60): List<Movie> {
        val q = MatchFinder.normalize(query)
        if (q.isEmpty()) return emptyList()
        val list = movieSearchList()
        val names = if (mergeVersions) movieCategoryNames() else emptyMap()
        return withContext(Dispatchers.Default) {
            val found = scan(list, q) { filter(ContentType.MOVIE, it.categoryId) }.take(limit * 4)
            (if (mergeVersions) Versions.mergeMovies(found, names) else found).take(limit)
        }
    }

    suspend fun searchSeries(query: String, limit: Int = 60): List<Series> {
        val q = MatchFinder.normalize(query)
        if (q.isEmpty()) return emptyList()
        val list = seriesSearchList()
        val names = if (mergeVersions) seriesCategoryNames() else emptyMap()
        return withContext(Dispatchers.Default) {
            val found = scan(list, q) { filter(ContentType.SERIES, it.categoryId) }.take(limit * 4)
            (if (mergeVersions) Versions.mergeSeries(found, names) else found).take(limit)
        }
    }

    // Every title in the library grouped by its title key, built once in the background, so other
    // qualities can be found wherever the provider keeps them (not only in groups named "4K" etc.).
    @Volatile private var movieIndex: Map<String, List<Movie>>? = null
    @Volatile private var seriesIndex: Map<String, List<Series>>? = null

    private suspend fun movieIndex(): Map<String, List<Movie>> {
        movieIndex?.let { return it }
        val all = try {
            rawAllMovies()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        } catch (e: OutOfMemoryError) {
            return emptyMap()
        }
        return withContext(Dispatchers.Default) { all.groupBy { Versions.key(it.name) } }.also { movieIndex = it }
    }

    private suspend fun seriesIndex(): Map<String, List<Series>> {
        seriesIndex?.let { return it }
        val all = try {
            rawAllSeries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        } catch (e: OutOfMemoryError) {
            return emptyMap()
        }
        return withContext(Dispatchers.Default) { all.groupBy { Versions.key(it.name) } }.also { seriesIndex = it }
    }

    /** Every quality of [m] the provider has, wherever it's filed. */
    suspend fun movieVersions(m: Movie): List<Movie> {
        val names = movieCategoryNames()
        val index = movieIndex()
        return withContext(Dispatchers.Default) {
            val pool = ArrayList<Movie>()
            pool += m.versions.ifEmpty { listOf(m) }
            if (index.isNotEmpty()) {
                pool += index[Versions.key(m.name)].orEmpty()
            } else {
                // The full list couldn't be loaded: search what has been.
                moviesByCat.values.forEach { pool += it }
            }
            val visible = pool.filter { filter(ContentType.MOVIE, it.categoryId) || it.streamId == m.streamId }
            Versions.movieCopies(m.name, visible, names)
        }
    }

    suspend fun seriesVersions(s: Series): List<Series> {
        val names = seriesCategoryNames()
        val index = seriesIndex()
        return withContext(Dispatchers.Default) {
            val pool = ArrayList<Series>()
            pool += s.versions.ifEmpty { listOf(s) }
            if (index.isNotEmpty()) {
                pool += index[Versions.key(s.name)].orEmpty()
            } else {
                seriesByCat.values.forEach { pool += it }
            }
            val visible = pool.filter { filter(ContentType.SERIES, it.categoryId) || it.seriesId == s.seriesId }
            Versions.seriesCopies(s.name, visible, names)
        }
    }

    private suspend fun rawMovies(categoryId: String): List<Movie> {
        if (categoryId == ALL) return rawAllMovies().filter { filter(ContentType.MOVIE, it.categoryId) }
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

    private suspend fun rawSeries(categoryId: String): List<Series> {
        if (categoryId == ALL) return rawAllSeries().filter { filter(ContentType.SERIES, it.categoryId) }
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

/** How many titles the Recently added group shows. */
private const val RECENT_LIMIT = 300
