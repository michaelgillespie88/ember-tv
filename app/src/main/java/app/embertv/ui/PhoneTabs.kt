package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.FAVOURITES
import app.embertv.data.Programme
import app.embertv.data.Repository
import app.embertv.data.catchupAvailable
import app.embertv.data.favouriteKey
import app.embertv.data.toChannel
import app.embertv.data.toMovie
import app.embertv.data.toSeries

private fun withVirtualCategories(cats: List<Category>, allLabel: String): List<Category> =
    listOf(Category(FAVOURITES, "★ Favourites"), Category(ALL, allLabel)) + cats

/** Loads a section's categories, then shows chips above [content] for the chosen one. */
@Composable
private fun CategorySection(
    vm: AppViewModel,
    section: SectionState,
    allLabel: String,
    loadingText: String,
    load: suspend (Repository) -> List<Category>,
    content: @Composable (repo: Repository, categoryId: String) -> Unit,
) {
    val repo = vm.repo ?: return
    val cats by rememberLoad(repo, vm.reloadToken, allLabel) { load(repo) }
    when (val c = cats) {
        is LoadResult.Loading -> LoadingView(text = loadingText)
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) { withVirtualCategories(c.value, allLabel) }
            val selectedId = section.categoryId ?: c.value.firstOrNull()?.id ?: ALL
            // The chips live in the frosted header, which belongs to PhoneHome.
            val holder = LocalHeaderChips.current
            val spec = ChipsSpec(categories, selectedId, section.rail) { section.categoryId = it.id }
            SideEffect { holder?.value = spec }
            DisposableEffect(holder) { onDispose { holder?.value = null } }
            Box(Modifier.fillMaxSize()) { content(repo, selectedId) }
        }
    }
}

@Composable
private fun rememberChannels(vm: AppViewModel, repo: Repository, categoryId: String): LoadResult<List<Channel>> {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.LIVE } else null
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toChannel() } ?: repo.channels(categoryId)
    }
    return load
}

// ---------------- Live ----------------

@Composable
fun PhoneLiveTab(vm: AppViewModel) {
    CategorySection(vm, vm.live, "All channels", "Loading channels…", { it.liveCategories() }) { repo, categoryId ->
        when (val r = rememberChannels(vm, repo, categoryId)) {
            is LoadResult.Loading -> LoadingView()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                val channels = r.value
                if (channels.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any channel to add it to your favourites."
                        else "No channels in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    LazyColumn(
                        state = vm.live.list(categoryId),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                            ChannelRow(
                                repo = repo,
                                channel = ch,
                                focusKey = "live:$categoryId:${ch.streamId}",
                                isFavourite = vm.favKeys.contains(favouriteKey(ContentType.LIVE, ch.streamId)),
                                onFocused = {},
                                onClick = { vm.push(Screen.PlayLive(channels, index)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(ch)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Guide ----------------

@Composable
fun PhoneGuideTab(vm: AppViewModel) {
    var details by remember { mutableStateOf<Pair<GuideFocus, List<Channel>>?>(null) }
    // Days from today: negative for catch-up, positive for what's coming.
    var day by remember { mutableIntStateOf(0) }
    val dayState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 4)
    CategorySection(vm, vm.guide, "All channels", "Loading guide…", { it.liveCategories() }) { repo, categoryId ->
        when (val r = rememberChannels(vm, repo, categoryId)) {
            is LoadResult.Loading -> LoadingView()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                val channels = r.value
                if (channels.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any channel to add it to your favourites."
                        else "No channels in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    val now = LocalNow.current
                    LazyColumn(
                        state = vm.guide.list(categoryId),
                        contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "days") {
                            LazyRow(
                                state = dayState,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items((-7..7).toList(), key = { it }) { d ->
                                    DayChip(formatDay(now + d * 86_400L, now), d == day, "guide:day:$d", autoFocus = false) { day = d }
                                }
                            }
                        }
                        itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                            PhoneGuideRow(
                                repo = repo,
                                channel = ch,
                                day = day,
                                onWatch = { vm.push(Screen.PlayLive(channels, index)) },
                                onDetails = { p -> details = GuideFocus(ch, p) to channels },
                            )
                        }
                    }
                }
            }
        }
    }
    val d = details
    if (d != null) {
        val (focus, channels) = d
        ProgrammeDialog(vm, focus, channels) { details = null }
    }
}

@Composable
private fun PhoneGuideRow(repo: Repository, channel: Channel, day: Int, onWatch: () -> Unit, onDetails: (Programme) -> Unit) {
    val now = LocalNow.current
    // Today shows what's on now and next (quick to load); other days need the full guide.
    val programmes = if (day == 0) rememberProgrammes(repo, channel.streamId) else rememberFullProgrammes(repo, channel.streamId)
    val dayStart = startOfDay(now) + day * 86_400L
    val upcoming = if (day == 0) {
        programmes.orEmpty().filter { it.end > now }.take(10)
    } else {
        programmes.orEmpty().filter { it.end > dayStart && it.start < dayStart + 86_400L }
    }
    Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .padding(start = 12.dp)
                .width(84.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onWatch)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChannelLogo(channel.icon, channel.name, Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.height(4.dp))
            Text(channel.name, style = Txt.Caption, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        if (upcoming.isEmpty()) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .weight(1f)
                    .fillMaxHeight()
                    .glassSurface(RoundedCornerShape(12.dp), Color(0x12FFFFFF))
                    .clickable(onClick = onWatch)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (programmes == null) "Loading…" else "No guide information",
                    style = Txt.Label,
                    color = Ember.Faint,
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(upcoming, key = { it.start }) { p ->
                    val live = p.isLive(now)
                    val past = p.end <= now
                    val catchup = past && catchupAvailable(channel, p, now)
                    Column(
                        Modifier
                            .width(176.dp)
                            .fillMaxHeight()
                            .glassSurface(RoundedCornerShape(12.dp), if (live) Color(0x29FFFFFF) else Color(0x12FFFFFF))
                            .clickable { if (live) onWatch() else onDetails(p) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (catchup) {
                                androidx.compose.material3.Icon(EmberIcons.Catchup, "On catch-up", tint = Ember.Accent, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                p.title,
                                style = Txt.Label,
                                color = if (past && !catchup) Ember.Dim else Ember.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (live) "NOW" else formatClock(p.start),
                                style = Txt.Caption,
                                color = if (live) Ember.Accent else Ember.Faint,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("– ${formatClock(p.end)}", style = Txt.Caption, color = Ember.Faint)
                        }
                        if (live) {
                            Spacer(Modifier.height(6.dp))
                            ProgressBar(p.progress(now), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Movies & series ----------------

@Composable
fun PhoneMoviesTab(vm: AppViewModel) {
    CategorySection(vm, vm.movies, "All movies", "Loading movies…", { it.movieCategories() }) { repo, categoryId ->
        val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.MOVIE } else null
        val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
            favSnapshot?.map { it.toMovie() } ?: repo.movies(categoryId)
        }
        when (val r = load) {
            is LoadResult.Loading -> LoadingView()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                if (r.value.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any movie to add it to your favourites."
                        else "No movies in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = vm.movies.grid(categoryId),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 10.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(r.value, key = { it.streamId }) { m ->
                            PosterCard(
                                title = m.name,
                                image = m.poster,
                                focusKey = "movie:$categoryId:${m.streamId}",
                                subtitle = ratingLabel(m.rating),
                                badge = badgeText(m.qualities()),
                                watched = vm.movieWatched(m),
                                favourite = vm.favKeys.contains(favouriteKey(ContentType.MOVIE, m.streamId)),
                                onClick = { vm.push(Screen.MovieDetail(m)) },
                                onLongClick = { vm.toggleFavourite(vm.favouriteOf(m)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhoneSeriesTab(vm: AppViewModel) {
    CategorySection(vm, vm.series, "All series", "Loading series…", { it.seriesCategories() }) { repo, categoryId ->
        val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.SERIES } else null
        val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
            favSnapshot?.map { it.toSeries() } ?: repo.series(categoryId)
        }
        when (val r = load) {
            is LoadResult.Loading -> LoadingView()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                if (r.value.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any series to add it to your favourites."
                        else "No series in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = vm.series.grid(categoryId),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 10.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(r.value, key = { it.seriesId }) { s ->
                            PosterCard(
                                title = s.name,
                                image = s.cover,
                                focusKey = "series:$categoryId:${s.seriesId}",
                                subtitle = listOfNotNull(s.year.takeIf { it.isNotBlank() }, ratingLabel(s.rating))
                                    .joinToString("  ")
                                    .ifEmpty { null },
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
}

