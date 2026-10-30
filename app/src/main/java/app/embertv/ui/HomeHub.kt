package app.embertv.ui

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
internal suspend fun loadHomeFeed(repo: Repository): HomeFeed {
    val movieRows = ArrayList<Pair<Category, List<Movie>>>()
    val recentMovies = ArrayList<Movie>()
    // A few more categories than the rows need, so Recently Added has more to choose from.
    for (c in orNull { repo.movieCategories() }.orEmpty().take(8)) {
        val items = orNull { repo.movies(c.id) }.orEmpty()
        recentMovies += items
        if (items.isNotEmpty() && movieRows.size < 3) movieRows.add(c to items.sortedByDescending { it.added }.take(24))
    }
    val seriesRows = ArrayList<Pair<Category, List<Series>>>()
    val recentSeries = ArrayList<Series>()
    for (c in orNull { repo.seriesCategories() }.orEmpty().take(5)) {
        val items = orNull { repo.series(c.id) }.orEmpty()
        recentSeries += items
        if (items.isNotEmpty() && seriesRows.size < 2) seriesRows.add(c to items.take(24))
    }
    val recent: List<Any> = (
        recentMovies.filter { it.added > 0 }.distinctBy { it.streamId }.map { it.added to (it as Any) } +
            recentSeries.filter { it.updated > 0 }.distinctBy { it.seriesId }.map { it.updated to (it as Any) }
        ).sortedByDescending { it.first }.take(24).map { it.second }
    var liveRow: Pair<Category, List<Channel>>? = null
    for (c in orNull { repo.liveCategories() }.orEmpty().take(3)) {
        val items = orNull { repo.channels(c.id) }.orEmpty()
        if (items.isNotEmpty()) {
            liveRow = c to items.take(20)
            break
        }
    }
    val heroes = movieRows.firstOrNull()?.second.orEmpty().filter { it.poster.isNotBlank() }.take(6)
    if (heroes.isEmpty() && movieRows.isEmpty() && seriesRows.isEmpty() && liveRow == null) {
        throw IllegalStateException("Nothing came back from your provider. Check your connection, then try again.")
    }
    return HomeFeed(heroes, liveRow, movieRows, seriesRows, recent)
}

@Composable
private fun rememberHomeFeed(vm: AppViewModel, repo: Repository) =
    rememberLoad(repo, vm.reloadToken) { loadHomeFeed(repo) }

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
) {
    val rowPadding = PaddingValues(horizontal = edge, vertical = 12.dp)
    val matches = vm.teamMatches.take(8)
    if (matches.isNotEmpty()) {
        item(key = "row-teams") {
            HomeRow("Your Teams", edge) {
                LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    items(matches, key = { it.id }) { m ->
                        MatchCard(m, continueWidth, "home:match:${m.id}") { vm.findingMatch = m }
                    }
                }
            }
        }
    }
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
    val recent = vm.recentChannels.map { it.toChannel() }
    if (recent.isNotEmpty()) {
        item(key = "row-recent") {
            HomeRow("Recently Watched", edge) {
                LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    itemsIndexed(recent, key = { _, c -> c.streamId }) { i, c ->
                        ChannelCard(
                            name = c.name,
                            logo = c.icon,
                            focusKey = "home:recent:${c.streamId}",
                            onClick = { vm.push(Screen.PlayLive(recent, i)) },
                            onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                        )
                    }
                }
            }
        }
    }
    val favChannels = vm.favourites.filter { it.type == ContentType.LIVE }.map { it.toChannel() }
    if (favChannels.isNotEmpty()) {
        item(key = "row-favchannels") {
            HomeRow("Your Channels", edge) {
                LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    itemsIndexed(favChannels, key = { _, c -> c.streamId }) { i, c ->
                        ChannelCard(
                            name = c.name,
                            logo = c.icon,
                            focusKey = "home:fav:${c.streamId}",
                            onClick = { vm.push(Screen.PlayLive(favChannels, i)) },
                            onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                        )
                    }
                }
            }
        }
    }
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
    val live = feed.liveRow
    if (live != null) {
        item(key = "row-live") {
            HomeRow("Live · ${live.first.name}", edge) {
                LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    itemsIndexed(live.second, key = { _, c -> c.streamId }) { i, c ->
                        ChannelCard(
                            name = c.name,
                            logo = c.icon,
                            focusKey = "home:live:${c.streamId}",
                            onClick = { vm.push(Screen.PlayLive(live.second, i)) },
                            onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                        )
                    }
                }
            }
        }
    }
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
                }
            }
        }
    }
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
                }
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
    val repo = vm.repo ?: return
    val feedState by rememberHomeFeed(vm, repo)
    when (val f = feedState) {
        is LoadResult.Loading -> LoadingView(text = "Getting things ready…")
        is LoadResult.Err -> ErrorView(f.message) { vm.reload() }
        is LoadResult.Ok -> {
            val feed = f.value
            LazyColumn(
                state = vm.home.list("tv"),
                contentPadding = PaddingValues(bottom = 48.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (feed.heroes.isNotEmpty()) {
                    item(key = "hero") { TvHero(vm, repo, feed.heroes) }
                }
                homeRows(vm, feed, edge = 48.dp, posterWidth = 132.dp, continueWidth = 300.dp, gap = 22.dp)
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

    Box(Modifier.fillMaxWidth().height(300.dp)) {
        Crossfade(targetState = movie, label = "hero") { m ->
            val art = if (m.streamId == movie.streamId) info?.backdrop?.takeIf { it.isNotBlank() } ?: m.poster else m.poster
            Box(Modifier.fillMaxSize()) {
                NetImage(art, Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Ember.Bg, 0.42f to Ember.Bg.copy(alpha = 0.75f), 1f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Ember.Bg)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 22.dp).width(560.dp)
        ) {
            Text("FEATURED", style = Txt.Caption, color = Ember.Accent)
            Spacer(Modifier.height(6.dp))
            Text(movie.name, style = Txt.Hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.onFocusChanged { focused = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvButton(
                    if (resume > 0) "Resume" else "Play",
                    icon = "▶",
                    focusKey = "home:hero:play",
                    onClick = { vm.playMovie(movie, fromStart = resume <= 0) },
                )
                TvButton("More info", focusKey = "home:hero:info", onClick = { vm.push(Screen.MovieDetail(movie)) })
                if (heroes.size > 1) {
                    TvButton("Next", icon = "›", focusKey = "home:hero:next", onClick = { setIndex(index + 1) })
                }
            }
        }
        HeroDots(heroes.size, index, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 30.dp))
    }
}

// ---------------- Phone ----------------

@Composable
fun PhoneHomeTab(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val feedState by rememberHomeFeed(vm, repo)
    val pad = LocalContentPadding.current
    when (val f = feedState) {
        is LoadResult.Loading -> LoadingView(text = "Getting things ready…")
        is LoadResult.Err -> ErrorView(f.message) { vm.reload() }
        is LoadResult.Ok -> {
            val feed = f.value
            LazyColumn(
                state = vm.home.list("phone"),
                contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (feed.heroes.isNotEmpty()) {
                    item(key = "hero") { PhoneHero(vm, repo, feed.heroes) }
                } else {
                    item(key = "top") { Spacer(Modifier.height(pad.calculateTopPadding())) }
                }
                homeRows(vm, feed, edge = 16.dp, posterWidth = 112.dp, continueWidth = 240.dp, gap = 12.dp)
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
                    tint = if (isFav) Ember.Accent else Ember.Text,
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
