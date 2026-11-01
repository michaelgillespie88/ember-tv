package app.embertv.ui

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.embertv.data.Category
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.HistoryEntry
import app.embertv.data.MatchState
import app.embertv.data.Movie
import app.embertv.data.Repository
import app.embertv.data.Series
import app.embertv.data.favouriteKey
import app.embertv.data.toChannel
import app.embertv.data.toMovie
import app.embertv.data.toSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ---------------- Data ----------------

class HomeFeed(
    val heroes: List<Movie>,
    val liveRow: Pair<Category, List<Channel>>?,
    val movieRows: List<Pair<Category, List<Movie>>>,
    val seriesRows: List<Pair<Category, List<Series>>>,
    /** Newest films and shows (Movie or Series), newest first. */
    val recent: List<Any> = emptyList(),
)

private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Builds the Home rows from the provider's first few categories. Each piece is optional. */
internal suspend fun loadHomeFeed(repo: Repository): HomeFeed = kotlinx.coroutines.coroutineScope {
    // The lists are fetched a few at a time rather than one after another, which is much quicker.
    val gate = kotlinx.coroutines.sync.Semaphore(4)
    suspend fun <T> limited(block: suspend () -> T): T = gate.withPermit { block() }
    val movieCats = async { orNull { repo.movieCategories() }.orEmpty().take(8) }
    val seriesCats = async { orNull { repo.seriesCategories() }.orEmpty().take(5) }
    val liveCats = async { orNull { repo.liveCategories() }.orEmpty().take(3) }
    // A few more categories than the rows need, so Recently Added has more to choose from.
    val movieLists = movieCats.await().map { c -> async { c to limited { orNull { repo.movies(c.id) }.orEmpty() } } }
    val seriesLists = seriesCats.await().map { c -> async { c to limited { orNull { repo.series(c.id) }.orEmpty() } } }
    val liveLists = liveCats.await().map { c -> async { c to limited { orNull { repo.channels(c.id) }.orEmpty() } } }

    val movieRows = ArrayList<Pair<Category, List<Movie>>>()
    val recentMovies = ArrayList<Movie>()
    for ((c, items) in movieLists.awaitAll()) {
        recentMovies += items
        if (items.isNotEmpty() && movieRows.size < 3) movieRows.add(c to items.sortedByDescending { it.added }.take(24))
    }
    val seriesRows = ArrayList<Pair<Category, List<Series>>>()
    val recentSeries = ArrayList<Series>()
    for ((c, items) in seriesLists.awaitAll()) {
        recentSeries += items
        if (items.isNotEmpty() && seriesRows.size < 2) seriesRows.add(c to items.take(24))
    }
    val recent: List<Any> = (
        recentMovies.filter { it.added > 0 }.distinctBy { it.streamId }.map { it.added to (it as Any) } +
            recentSeries.filter { it.updated > 0 }.distinctBy { it.seriesId }.map { it.updated to (it as Any) }
        ).sortedByDescending { it.first }.take(24).map { it.second }
    val liveRow: Pair<Category, List<Channel>>? = liveLists.awaitAll().firstOrNull { it.second.isNotEmpty() }?.let { (c, items) -> c to items.take(20) }
    val heroes = movieRows.firstOrNull()?.second.orEmpty().filter { it.poster.isNotBlank() }.take(6)
    if (heroes.isEmpty() && movieRows.isEmpty() && seriesRows.isEmpty() && liveRow == null) {
        throw IllegalStateException("Nothing came back from your provider. Check your connection, then try again.")
    }
    HomeFeed(heroes, liveRow, movieRows, seriesRows, recent)
}

/**
 * The films for the big banner at the top of Home, picked for you rather than just the provider's
 * first category: first ones like what you've been watching (Because You Watched), then new
 * additions that are rated well, then (only if there's nothing else) the newest films. Nothing
 * you've already watched, and only ones with artwork. Empty when the banner is turned off.
 */
private fun featuredFor(vm: AppViewModel, feed: HomeFeed): List<Movie> {
    if (FEATURED in vm.homeHidden) return emptyList()
    fun rating(m: Movie) = m.rating.toFloatOrNull() ?: 0f
    fun ok(m: Movie) = m.poster.isNotBlank() && !vm.movieWatched(m)
    val picked = LinkedHashMap<Int, Movie>()
    vm.homeExtras?.because.orEmpty().filterIsInstance<Movie>().filter(::ok).take(3).forEach { picked[it.streamId] = it }
    val newest = feed.recent.filterIsInstance<Movie>().filter(::ok)
    newest.filter { rating(it) >= 6.5f }.sortedByDescending { rating(it) }.take(6).forEach { if (picked.size < 6) picked.putIfAbsent(it.streamId, it) }
    if (picked.size < 3) newest.take(6).forEach { if (picked.size < 6) picked.putIfAbsent(it.streamId, it) }
    if (picked.isEmpty()) feed.heroes.filter(::ok).forEach { if (picked.size < 6) picked.putIfAbsent(it.streamId, it) }
    return picked.values.toList()
}

/** The id Arrange Home uses for the banner at the top. */
const val FEATURED = "featured"

/**
 * Home's rows, loaded by the app in the background (so switching to Live TV doesn't stop it) and
 * kept until something changes (profile, categories, a refresh).
 */
@Composable
private fun rememberHomeFeed(vm: AppViewModel, repo: Repository): androidx.compose.runtime.State<LoadResult<HomeFeed>> {
    LaunchedEffect(repo, vm.reloadToken) { vm.ensureHome() }
    // New Episodes and Because You Watched, worked out alongside.
    LaunchedEffect(repo, vm.reloadToken, vm.vodVersion, vm.lastWatched) { vm.ensureHomeExtras() }
    return androidx.compose.runtime.rememberUpdatedState(vm.homeFeed)
}

/** Moves through the featured titles every few seconds unless [paused]. */
@Composable
private fun rememberHeroIndex(count: Int, paused: Boolean): Pair<Int, (Int) -> Unit> {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(count, paused, index) {
        if (count <= 1 || paused) return@LaunchedEffect
        delay(8_000)
        index = (index + 1) % count
    }
    val safe = if (count == 0) 0 else index.coerceIn(0, count - 1)
    return safe to { i: Int -> index = if (count == 0) 0 else ((i % count) + count) % count }
}

private fun minutesLeft(e: HistoryEntry): String? {
    if (e.durationMs <= 0) return null
    val mins = ((e.durationMs - e.positionMs) / 60_000L).coerceAtLeast(1L)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "$mins min left"
}

// ---------------- Shared cards ----------------

@Composable
fun ContinueCard(entry: HistoryEntry, width: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusKey = "continue:${entry.key}",
            focusedScale = 1.07f,
            onFocusChange = { focused = it },
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            Placeholder(entry.title, Modifier.fillMaxSize())
            NetImage(entry.image.ifBlank { entry.seriesCover }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .glassSurface(CircleShape, Color(0x40000000)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(EmberIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (entry.positionMs > 0) {
                ProgressBar(entry.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp))
            } else {
                // Queued after the last episode finished.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xE6FFFFFF))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("NEXT EPISODE", style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.Black)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            entry.title,
            style = Txt.Label,
            color = if (focused || !LocalIsTv.current) Ember.Text else Ember.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = listOfNotNull(
            entry.subtitle.takeIf { it.isNotBlank() }?.substringBefore("  "),
            minutesLeft(entry),
        ).joinToString("  ·  ")
        if (detail.isNotEmpty()) Text(detail, style = Txt.Caption, color = Ember.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** All the rows below the featured banner, shared by TV and phone. */
private fun LazyListScope.homeRows(
    vm: AppViewModel,
    feed: HomeFeed,
    edge: Dp,
    posterWidth: Dp,
    continueWidth: Dp,
    gap: Dp,
    onArrange: () -> Unit,
) {
    val rowPadding = PaddingValues(horizontal = edge, vertical = 12.dp)
    // Each row by its id, so they can be shown in the profile's chosen order (Arrange Home).
    val rows = HashMap<String, () -> Unit>()
    // One Sport row: live games first (with the channels showing them), then your teams' other fixtures.
    rows["sport"] = {
        val sport = vm.liveSport
        val liveIds = sport.map { it.match.id }.toSet()
        val fixtures = vm.teamMatches.filter { it.id !in liveIds }.take(8)
        // With nothing live, the next games in your leagues too, counting down.
        val shown = liveIds + fixtures.map { it.id }
        val leagueNext = if (sport.isEmpty()) vm.leagueFixtures.filter { it.id !in shown }.take(10) else emptyList()
        if (sport.isNotEmpty() || fixtures.isNotEmpty() || leagueNext.isNotEmpty()) {
            item(key = "row-sport") {
                HomeRow(if (sport.isNotEmpty() || leagueNext.isNotEmpty()) "Sport" else "Your Teams", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(sport, key = { "live-" + it.match.id }) { s ->
                            SportCard(
                                s,
                                continueWidth,
                                "home:sport:${s.match.id}",
                                // Always list the channels showing it, so there's a choice.
                                onClick = { vm.findingMatch = s.match },
                                onLongClick = { vm.pinMatch(s.match) },
                            )
                        }
                        items(fixtures, key = { "team-" + it.id }) { m ->
                            MatchCard(m, continueWidth, "home:match:${m.id}") { vm.findingMatch = m }
                        }
                        items(leagueNext, key = { "league-" + it.id }) { m ->
                            MatchCard(m, continueWidth, "home:league:${m.id}") { vm.findingMatch = m }
                        }
                    }
                }
            }
        }
    }
    rows["continue"] = {
        val history = vm.history.toList()
        if (history.isNotEmpty()) {
            item(key = "row-continue") {
                // Like Apple TV: what you're part-way through, plus the next episode of what you finished.
                HomeRow("Up Next", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(history, key = { it.key }) { e ->
                            ContinueCard(e, continueWidth, onClick = { vm.resume(e) }, onLongClick = { vm.removeHistory(e.key) })
                        }
                    }
                }
            }
        }
    }
    rows["recently-added"] = {
        if (feed.recent.isNotEmpty()) {
            item(key = "row-recently-added") {
                HomeRow("Recently Added", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(feed.recent, key = { if (it is Movie) "m${it.streamId}" else "s${(it as Series).seriesId}" }) { item ->
                            when (item) {
                                is Movie -> PosterCard(
                                    title = item.name,
                                    image = item.poster,
                                    focusKey = "home:new:m${item.streamId}",
                                    modifier = Modifier.width(posterWidth),
                                    subtitle = "Film",
                                    badge = badgeText(item.qualities()),
                                    favourite = vm.favKeys.contains(favouriteKey(ContentType.MOVIE, item.streamId)),
                                    watched = vm.movieWatched(item),
                                    onClick = { vm.push(Screen.MovieDetail(item)) },
                                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(item)) },
                                )
                                is Series -> PosterCard(
                                    title = item.name,
                                    image = item.cover,
                                    focusKey = "home:new:s${item.seriesId}",
                                    modifier = Modifier.width(posterWidth),
                                    subtitle = "Series",
                                    badge = badgeText(item.qualities()),
                                    favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, item.seriesId)),
                                    onClick = { vm.push(Screen.SeriesDetail(item)) },
                                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(item)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    rows["recent-channels"] = {
        val recent = vm.recentChannels.map { it.toChannel() }
        if (recent.isNotEmpty()) {
            item(key = "row-recent") {
                HomeRow("Recently Watched", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        itemsIndexed(recent, key = { _, c -> c.streamId }) { i, c ->
                            ChannelCard(
                                name = c.name,
                                logo = c.icon,
                                repo = vm.repo,
                                streamId = c.streamId,
                                focusKey = "home:recent:${c.streamId}",
                                onClick = { vm.push(Screen.PlayLive(recent, i)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                            )
                        }
                    }
                }
            }
        }
    }
    rows["fav-channels"] = {
        val favChannels = vm.favourites.filter { it.type == ContentType.LIVE }.map { it.toChannel() }
        if (favChannels.isNotEmpty()) {
            item(key = "row-favchannels") {
                HomeRow("Your Channels", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        itemsIndexed(favChannels, key = { _, c -> c.streamId }) { i, c ->
                            ChannelCard(
                                name = c.name,
                                logo = c.icon,
                                repo = vm.repo,
                                streamId = c.streamId,
                                focusKey = "home:fav:${c.streamId}",
                                onClick = { vm.push(Screen.PlayLive(favChannels, i)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                            )
                        }
                    }
                }
            }
        }
    }
    rows["mylist"] = {
        val myList = vm.favourites.filter { it.type != ContentType.LIVE }
        if (myList.isNotEmpty()) {
            item(key = "row-mylist") {
                HomeRow("My List", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(myList, key = { it.key }) { f ->
                            PosterCard(
                                title = f.name,
                                image = f.image,
                                focusKey = "home:mylist:${f.key}",
                                modifier = Modifier.width(posterWidth),
                                favourite = true,
                                onClick = {
                                    if (f.type == ContentType.MOVIE) vm.push(Screen.MovieDetail(f.toMovie()))
                                    else vm.push(Screen.SeriesDetail(f.toSeries()))
                                },
                                onLongClick = { vm.toggleFavourite(f) },
                            )
                        }
                    }
                }
            }
        }
    }
    rows["live"] = {
        val live = feed.liveRow
        if (live != null) {
            item(key = "row-live") {
                HomeRow("Live · ${live.first.name}", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        itemsIndexed(live.second, key = { _, c -> c.streamId }) { i, c ->
                            ChannelCard(
                                name = c.name,
                                logo = c.icon,
                                repo = vm.repo,
                                streamId = c.streamId,
                                focusKey = "home:live:${c.streamId}",
                                onClick = { vm.push(Screen.PlayLive(live.second, i)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                            )
                        }
                    }
                }
            }
        }
    }
    rows["movies"] = {
        for ((cat, movies) in feed.movieRows) {
            item(key = "row-movies-${cat.id}") {
                HomeRow(cat.name, edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(movies, key = { it.streamId }) { m ->
                            PosterCard(
                                title = m.name,
                                image = m.poster,
                                focusKey = "home:m:${cat.id}:${m.streamId}",
                                modifier = Modifier.width(posterWidth),
                                subtitle = ratingLabel(m.rating),
                                badge = badgeText(m.qualities()),
                                favourite = vm.favKeys.contains(favouriteKey(ContentType.MOVIE, m.streamId)),
                                watched = vm.movieWatched(m),
                                onClick = { vm.push(Screen.MovieDetail(m)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(m)) },
                            )
                        }
                        // The rest of this category, in Movies.
                        item(key = "see-all") {
                            SeeAllCard(posterWidth, "home:m:${cat.id}:all") { vm.openCategory(Tabs.MOVIES, cat.id) }
                        }
                    }
                }
            }
        }
    }
    rows["series"] = {
        for ((cat, list) in feed.seriesRows) {
            item(key = "row-series-${cat.id}") {
                HomeRow(cat.name, edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(list, key = { it.seriesId }) { s ->
                            PosterCard(
                                title = s.name,
                                image = s.cover,
                                focusKey = "home:s:${cat.id}:${s.seriesId}",
                                modifier = Modifier.width(posterWidth),
                                badge = badgeText(s.qualities()),
                                favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, s.seriesId)),
                                onClick = { vm.push(Screen.SeriesDetail(s)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(s)) },
                            )
                        }
                        item(key = "see-all") {
                            SeeAllCard(posterWidth, "home:s:${cat.id}:all") { vm.openCategory(Tabs.SERIES, cat.id) }
                        }
                    }
                }
            }
        }
    }
    rows["new-episodes"] = {
        val fresh = vm.homeExtras?.newEpisodes.orEmpty()
        if (fresh.isNotEmpty()) {
            item(key = "row-new-episodes") {
                // Shows you've been watching that have more since you last watched.
                HomeRow("New Episodes", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(fresh, key = { it.seriesId }) { s ->
                            PosterCard(
                                title = s.name,
                                image = s.cover,
                                focusKey = "home:newep:${s.seriesId}",
                                modifier = Modifier.width(posterWidth),
                                subtitle = "New episodes",
                                badge = badgeText(s.qualities()),
                                favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, s.seriesId)),
                                onClick = { vm.push(Screen.SeriesDetail(s)) },
                                // Hold to clear it from this row.
                                onLongClick = { vm.noteSeriesSeen(s.seriesId) },
                            )
                        }
                    }
                }
            }
        }
    }
    rows["because"] = {
        val extras = vm.homeExtras
        val because = extras?.because.orEmpty()
        if (extras?.becauseOf != null && because.isNotEmpty()) {
            item(key = "row-because") {
                HomeRow("Because You Watched ${extras.becauseOf}", edge) {
                    LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(because, key = { if (it is Movie) "m${it.streamId}" else "s${(it as Series).seriesId}" }) { item ->
                            when (item) {
                                is Movie -> PosterCard(
                                    title = item.name,
                                    image = item.poster,
                                    focusKey = "home:because:m${item.streamId}",
                                    modifier = Modifier.width(posterWidth),
                                    subtitle = ratingLabel(item.rating),
                                    badge = badgeText(item.qualities()),
                                    favourite = vm.favKeys.contains(favouriteKey(ContentType.MOVIE, item.streamId)),
                                    watched = vm.movieWatched(item),
                                    onClick = { vm.push(Screen.MovieDetail(item)) },
                                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(item)) },
                                )
                                is Series -> PosterCard(
                                    title = item.name,
                                    image = item.cover,
                                    focusKey = "home:because:s${item.seriesId}",
                                    modifier = Modifier.width(posterWidth),
                                    badge = badgeText(item.qualities()),
                                    favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, item.seriesId)),
                                    onClick = { vm.push(Screen.SeriesDetail(item)) },
                                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(item)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val hidden = vm.homeHidden
    for (id in vm.homeRowOrder()) if (id !in hidden) rows[id]?.invoke()
    item(key = "arrange") {
        Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
            GlassButton("Arrange Home", EmberIcons.ChannelList, onClick = onArrange)
        }
    }
}

/** The last tile of a category row: a frosted poster-sized "See all" that opens the whole category. */
@Composable
private fun SeeAllCard(width: Dp, focusKey: String, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            focusKey = focusKey,
            focusedScale = 1.07f,
            color = Color(0x14FFFFFF),
            focusedColor = Color.White,
            onClick = onClick,
        ) { focused ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (focused) Color(0x14000000) else Color(0x1FFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(EmberIcons.ChevronRight, contentDescription = null, tint = if (focused) Color.Black else Ember.Text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("See all", style = Txt.Label.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Color.Black else Ember.Text)
            }
        }
    }
}

@Composable
private fun HomeRow(title: String, edge: Dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(title, style = Txt.Headline, modifier = Modifier.padding(start = edge, end = edge), maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}

@Composable
private fun BoxScope.HeroDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .size(if (i == index) 18.dp else 6.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i == index) Color.White else Color(0x66FFFFFF))
            )
        }
    }
}

// ---------------- TV ----------------

@Composable
fun HomeSection(vm: AppViewModel) {
    // Live Sport Now keeps itself up to date while Home is on screen.
    androidx.compose.runtime.DisposableEffect(vm.profile?.id) {
        vm.watchLiveSport(true)
        onDispose { vm.watchLiveSport(false) }
    }
    val repo = vm.repo ?: return
    val feedState by rememberHomeFeed(vm, repo)
    var arranging by remember { mutableStateOf(false) }
    if (arranging) ArrangeHomeDialog(vm) { arranging = false }
    // Apple TV style: the top bar tucks away once you're browsing the rows below the banner.
    val homeList = vm.home.list("tv")
    val scrolledDown by remember { androidx.compose.runtime.derivedStateOf { homeList.firstVisibleItemIndex > 0 } }
    LaunchedEffect(scrolledDown) { vm.homeBarHidden = scrolledDown }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.homeBarHidden = false } }
    when (val f = feedState) {
        is LoadResult.Loading -> SkeletonHome()
        is LoadResult.Err -> ErrorView(f.message) { vm.reload() }
        is LoadResult.Ok -> {
            val feed = f.value
            LazyColumn(
                state = homeList,
                contentPadding = PaddingValues(bottom = 48.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val heroes = featuredFor(vm, feed)
                if (heroes.isNotEmpty()) {
                    item(key = "hero") { TvHero(vm, repo, heroes) }
                }
                homeRows(vm, feed, edge = 48.dp, posterWidth = 132.dp, continueWidth = 300.dp, gap = 22.dp) { arranging = true }
            }
        }
    }
}

@Composable
private fun TvHero(vm: AppViewModel, repo: Repository, heroes: List<Movie>) {
    var focused by remember { mutableStateOf(false) }
    val (index, setIndex) = rememberHeroIndex(heroes.size, paused = focused)
    val movie = heroes[index]
    val info = (rememberLoad(movie.streamId) { repo.movieInfo(movie.streamId) }.value as? LoadResult.Ok)?.value
    val resume = vm.moviePosition(movie)

    val isFav = vm.isFavourite(ContentType.MOVIE, movie.streamId)
    // Which button has focus, so ◀ on the first and ▶ on the last can page through the films.
    var onFirst by remember { mutableStateOf(false) }
    var onLast by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().height(360.dp)) {
        Crossfade(targetState = movie, label = "hero") { m ->
            val art = if (m.streamId == movie.streamId) info?.backdrop?.takeIf { it.isNotBlank() } ?: m.poster else m.poster
            Box(Modifier.fillMaxSize()) {
                NetImage(art, Modifier.fillMaxSize())
            }
        }
        // Apple TV style: the picture fills the width, fading softly into the page on the left and below.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Ember.Bg.copy(alpha = 0.92f), 0.35f to Ember.Bg.copy(alpha = 0.55f), 0.65f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ember.Bg.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.62f to Color.Transparent, 1f to Ember.Bg)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 26.dp).width(540.dp)
        ) {
            Text("FEATURED FOR YOU", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp), color = Ember.Dim)
            Spacer(Modifier.height(6.dp))
            Text(app.embertv.data.Versions.cleanTitle(movie.name), style = Txt.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull(
                info?.year?.takeIf { it.isNotBlank() },
                info?.genre?.takeIf { it.isNotBlank() },
                ratingLabel(info?.rating ?: movie.rating),
            ).joinToString("   ·   ")
            if (meta.isNotEmpty()) Text(meta, style = Txt.Label, color = Ember.Dim, maxLines = 1)
            val plot = info?.plot
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(plot, style = Txt.Body, color = Ember.Text.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .onFocusChanged { focused = it.hasFocus }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || heroes.size <= 1) return@onPreviewKeyEvent false
                        when (ev.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (onLast) { setIndex(index + 1); true } else false
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (onFirst) { setIndex(index - 1); true } else false
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.onFocusChanged { onFirst = it.hasFocus }) {
                    TvButton(
                        if (resume > 0) "Resume" else "Play",
                        icon = "▶",
                        focusKey = "home:hero:play",
                        onClick = { vm.playMovie(movie, fromStart = resume <= 0) },
                    )
                }
                TvButton("More info", focusKey = "home:hero:info", onClick = { vm.push(Screen.MovieDetail(movie)) })
                // Add to My List, as a round glass button.
                Box(Modifier.onFocusChanged { onLast = it.hasFocus }) {
                    FocusBox(
                        modifier = Modifier.size(50.dp),
                        focusKey = "home:hero:fav",
                        shape = CircleShape,
                        focusedScale = 1.08f,
                        color = Color(0x26FFFFFF),
                        focusedColor = Color.White,
                        onClick = { vm.toggleFavourite(vm.favouriteOf(movie)) },
                    ) { f ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                if (isFav) EmberIcons.StarFilled else EmberIcons.Star,
                                contentDescription = if (isFav) "In My List" else "Add to My List",
                                tint = if (f) Color.Black else Ember.Text,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        // Where you are among the featured films, in a small frosted pill.
        if (heroes.size > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 40.dp)
                    .glassSurface(RoundedCornerShape(12.dp), Color(0x33000000))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Box(Modifier.size(width = (heroes.size * 12 + 12).dp, height = 6.dp)) {
                    HeroDots(heroes.size, index, Modifier.align(Alignment.CenterStart))
                }
            }
        }
    }
}

// ---------------- Phone ----------------

@Composable
fun PhoneHomeTab(vm: AppViewModel) {
    // Live Sport Now keeps itself up to date while Home is on screen.
    androidx.compose.runtime.DisposableEffect(vm.profile?.id) {
        vm.watchLiveSport(true)
        onDispose { vm.watchLiveSport(false) }
    }
    val repo = vm.repo ?: return
    val feedState by rememberHomeFeed(vm, repo)
    val pad = LocalContentPadding.current
    var arranging by remember { mutableStateOf(false) }
    if (arranging) ArrangeHomeDialog(vm) { arranging = false }
    when (val f = feedState) {
        is LoadResult.Loading -> SkeletonHome()
        is LoadResult.Err -> ErrorView(f.message) { vm.reload() }
        is LoadResult.Ok -> {
            val feed = f.value
            LazyColumn(
                state = vm.home.list("phone"),
                contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val heroes = featuredFor(vm, feed)
                if (heroes.isNotEmpty()) {
                    item(key = "hero") { PhoneHero(vm, repo, heroes) }
                } else {
                    item(key = "top") { Spacer(Modifier.height(pad.calculateTopPadding())) }
                }
                homeRows(vm, feed, edge = 16.dp, posterWidth = 112.dp, continueWidth = 240.dp, gap = 12.dp) { arranging = true }
            }
        }
    }
}

@Composable
private fun PhoneHero(vm: AppViewModel, repo: Repository, heroes: List<Movie>) {
    val (index, setIndex) = rememberHeroIndex(heroes.size, paused = false)
    val latestIndex by rememberUpdatedState(index)
    val latestSetIndex by rememberUpdatedState(setIndex)
    val movie = heroes[index]
    val info = (rememberLoad(movie.streamId) { repo.movieInfo(movie.streamId) }.value as? LoadResult.Ok)?.value
    val resume = vm.moviePosition(movie)
    val isFav = vm.isFavourite(ContentType.MOVIE, movie.streamId)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5.4f)
            .pointerInput(heroes.size) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (travelled < -60f) latestSetIndex(latestIndex + 1) else if (travelled > 60f) latestSetIndex(latestIndex - 1)
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        travelled += dx
                    },
                )
            }
    ) {
        Crossfade(targetState = movie, label = "hero") { m ->
            Box(Modifier.fillMaxSize()) {
                Placeholder(m.name, Modifier.fillMaxSize(), fontSizeSp = 40)
                NetImage(m.poster, Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x80000000), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Ember.Bg)
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(movie.name, style = Txt.LargeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                info?.year?.takeIf { it.isNotBlank() },
                info?.genre?.takeIf { it.isNotBlank() }?.substringBefore(","),
                ratingLabel(info?.rating ?: movie.rating),
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = Txt.Caption, color = Ember.Dim, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (resume > 0) "Resume" else "Play",
                    icon = EmberIcons.Play,
                    primary = true,
                    modifier = Modifier.weight(1f),
                ) { vm.playMovie(movie, fromStart = resume <= 0) }
                PillButton("Info", modifier = Modifier.weight(1f)) { vm.push(Screen.MovieDetail(movie)) }
                IconCircleButton(
                    if (isFav) EmberIcons.StarFilled else EmberIcons.Star,
                    if (isFav) "Remove from favourites" else "Add to favourites",
                    size = 48.dp,
                    background = Color(0x1FFFFFFF),
                    tint = Ember.Text,
                    glass = true,
                ) { vm.toggleFavourite(vm.favouriteOf(movie)) }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)) {
                HeroDots(heroes.size, index, Modifier.align(Alignment.Center))
            }
        }
    }
}

// ---------------- Arrange Home ----------------

/**
 * Choose which rows Home shows and in what order: tick a row to show or hide it, and use the arrows
 * to move it up or down. Saved for the current profile.
 */
@Composable
fun ArrangeHomeDialog(vm: AppViewModel, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    val names = remember { HOME_ROWS.toMap() }
    // "channels" or "mylist" while putting that row's tiles in order.
    var ordering by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = if (isTv) 520.dp else 600.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("Arrange Home", eyebrow = vm.profile?.name, subtitle = "Tick the rows you want, and move them up or down")
            Spacer(Modifier.height(14.dp))
            val order = vm.homeRowOrder()
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The big banner always sits at the top; it can only be turned on or off.
                ArrangeRow(
                    name = "Featured banner (picked for you)",
                    shown = FEATURED !in vm.homeHidden,
                    canUp = false,
                    canDown = false,
                    first = true,
                    id = FEATURED,
                    onToggle = { vm.toggleHomeRow(FEATURED) },
                    onUp = {},
                    onDown = {},
                    arrows = false,
                )
                order.forEachIndexed { i, id ->
                    // Keyed by row, so focus stays on the arrow you pressed as the row moves.
                    androidx.compose.runtime.key(id) {
                        ArrangeRow(
                            name = names[id] ?: id,
                            shown = id !in vm.homeHidden,
                            canUp = i > 0,
                            canDown = i < order.lastIndex,
                            first = false,
                            id = id,
                            onToggle = { vm.toggleHomeRow(id) },
                            onUp = { vm.moveHomeRow(id, -1) },
                            onDown = { vm.moveHomeRow(id, 1) },
                        )
                    }
                }
                // The tiles you choose yourself can be put in your own order too.
                Spacer(Modifier.height(10.dp))
                SheetSection("Tiles")
                SheetOption(
                    "Your Channels",
                    focusKey = "arrange:tiles:channels",
                    subtitle = "Put your favourite channels in order",
                    trailing = { dark -> androidx.compose.material3.Icon(EmberIcons.ChevronRight, contentDescription = null, tint = if (dark) Color.Black else Ember.Dim, modifier = Modifier.size(18.dp)) },
                ) { ordering = "channels" }
                SheetOption(
                    "My List",
                    focusKey = "arrange:tiles:mylist",
                    subtitle = "Put your films and series in order",
                    trailing = { dark -> androidx.compose.material3.Icon(EmberIcons.ChevronRight, contentDescription = null, tint = if (dark) Color.Black else Ember.Dim, modifier = Modifier.size(18.dp)) },
                ) { ordering = "mylist" }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("Reset", EmberIcons.Restart) { vm.resetHomeRows() }
                GlassButton("Done", EmberIcons.Check, onClick = onDone)
            }
        }
    }
    val t = ordering
    if (t != null) TileOrderDialog(vm, channels = t == "channels") { ordering = null }
}

@Composable
private fun ArrangeRow(
    name: String,
    shown: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    first: Boolean,
    id: String,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    arrows: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FocusBox(
            modifier = Modifier.weight(1f).height(52.dp),
            focusKey = "arrange:$id",
            shape = RoundedCornerShape(16.dp),
            focusedScale = 1.03f,
            color = Color(0x14FFFFFF),
            focusedColor = Color.White,
            autoFocus = first,
            onClick = onToggle,
        ) { focused ->
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // Apple-style tick circle: filled white when shown.
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (shown) (if (focused) Color.Black else Ember.Text) else Color.Transparent)
                        .then(if (shown) Modifier else Modifier.border(1.5.dp, if (focused) Color(0xFF88888F) else Ember.Faint, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (shown) androidx.compose.material3.Icon(EmberIcons.Check, contentDescription = "Shown", tint = if (focused) Color.White else Color.Black, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    name,
                    style = Txt.Label,
                    color = if (focused) Color.Black else if (shown) Ember.Text else Ember.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (arrows) {
            Spacer(Modifier.width(8.dp))
            ArrowButton(EmberIcons.ChevronUp, "Move up", "arrange:$id:up", enabled = canUp, onClick = onUp)
            Spacer(Modifier.width(6.dp))
            ArrowButton(EmberIcons.ChevronDown, "Move down", "arrange:$id:down", enabled = canDown, onClick = onDown)
        }
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, focusKey: String, enabled: Boolean, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.size(44.dp),
        focusKey = focusKey,
        shape = CircleShape,
        focusedScale = 1.1f,
        color = Color(0x14FFFFFF),
        focusedColor = Color.White,
        onClick = { if (enabled) onClick() },
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = description,
                tint = when {
                    focused -> Color.Black
                    enabled -> Ember.Text
                    else -> Color(0x33FFFFFF)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Put the tiles of Your Channels or My List in order: arrows to move each one up or down, ✕ to take
 * it off. Saved straight away; Home shows them in this order.
 */
@Composable
private fun TileOrderDialog(vm: AppViewModel, channels: Boolean, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    val tiles = vm.favourites.filter { (it.type == ContentType.LIVE) == channels }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = if (isTv) 520.dp else 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(if (channels) "Your Channels" else "My List", eyebrow = "Arrange Home", subtitle = "Move tiles up or down to set their order on Home")
            Spacer(Modifier.height(14.dp))
            if (tiles.isEmpty()) {
                Text(
                    if (channels) "No favourite channels yet. ${holdPrompt()} a channel to add one."
                    else "Nothing in My List yet. ${holdPrompt()} a film or series to add it.",
                    style = Txt.Body,
                    color = Ember.Dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.forEachIndexed { i, f ->
                    androidx.compose.runtime.key(f.key) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FocusBox(
                                modifier = Modifier.weight(1f).height(56.dp),
                                focusKey = "tiles:${f.key}",
                                shape = RoundedCornerShape(16.dp),
                                focusedScale = 1.03f,
                                color = Color(0x14FFFFFF),
                                focusedColor = Color.White,
                                autoFocus = i == 0,
                                onClick = {},
                            ) { focused ->
                                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${i + 1}",
                                        style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (focused) Color(0xFF55555C) else Ember.Faint,
                                        modifier = Modifier.width(26.dp),
                                    )
                                    if (channels) {
                                        ChannelLogo(f.image, f.name, Modifier.size(width = 50.dp, height = 32.dp))
                                    } else {
                                        Box(Modifier.size(width = 28.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF))) {
                                            NetImage(f.image, Modifier.fillMaxSize())
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(f.name, style = Txt.Label, color = if (focused) Color.Black else Ember.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            ArrowButton(EmberIcons.ChevronUp, "Move up", "tiles:${f.key}:up", enabled = i > 0) {
                                if (channels) vm.moveFavouriteChannel(f.key, -1) else vm.moveMyListItem(f.key, -1)
                            }
                            Spacer(Modifier.width(6.dp))
                            ArrowButton(EmberIcons.ChevronDown, "Move down", "tiles:${f.key}:down", enabled = i < tiles.lastIndex) {
                                if (channels) vm.moveFavouriteChannel(f.key, 1) else vm.moveMyListItem(f.key, 1)
                            }
                            Spacer(Modifier.width(6.dp))
                            ArrowButton(EmberIcons.Close, "Remove", "tiles:${f.key}:remove", enabled = true) { vm.removeFavourite(f.key) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            GlassButton("Done", EmberIcons.Check, onClick = onDone)
        }
    }
}
