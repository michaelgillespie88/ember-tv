package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.ContentType
import app.embertv.data.Episode
import app.embertv.data.FAVOURITES
import app.embertv.data.Repository
import app.embertv.data.Series
import app.embertv.data.SeriesInfo
import app.embertv.data.favouriteKey
import app.embertv.data.toSeries

@Composable
fun SeriesSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val section = vm.series
    val cats by rememberLoad(repo, vm.reloadToken) { repo.seriesCategories() }
    when (val c = cats) {
        is LoadResult.Loading -> LoadingView(text = "Loading series…")
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) {
                listOf(Category(FAVOURITES, "★  Favourites"), Category(ALL, "All series")) + c.value
            }
            val selectedId = section.categoryId ?: c.value.firstOrNull()?.id ?: ALL
            val select = rememberCategorySelector(section)
            Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 40.dp, top = 6.dp)) {
                CategoryRail(
                    categories = categories,
                    selectedId = selectedId,
                    state = section.rail,
                    keyPrefix = "series",
                    onFocusCategory = { select(it.id, false) },
                    onSelectCategory = { select(it.id, true) },
                )
                Spacer(Modifier.width(24.dp))
                SeriesGrid(vm, repo, selectedId, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SeriesGrid(vm: AppViewModel, repo: Repository, categoryId: String, modifier: Modifier) {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.SERIES } else null
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toSeries() } ?: repo.series(categoryId)
    }
    when (val r = load) {
        is LoadResult.Loading -> LoadingView(modifier)
        is LoadResult.Err -> ErrorView(r.message, modifier) { vm.reload() }
        is LoadResult.Ok -> {
            val list = r.value
            if (list.isEmpty()) {
                EmptyView(
                    if (categoryId == FAVOURITES) "Hold OK on any series to add it to your favourites."
                    else "No series in this category.",
                    modifier,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    state = vm.series.grid(categoryId),
                    modifier = modifier.fillMaxHeight(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 48.dp, start = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(list, key = { it.seriesId }) { s ->
                        PosterCard(
                            title = s.name,
                            image = s.cover,
                            focusKey = "series:$categoryId:${s.seriesId}",
                            subtitle = listOfNotNull(s.year.takeIf { it.isNotBlank() }, ratingLabel(s.rating))
                                .joinToString("  ")
                                .ifEmpty { null },
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

/** The episode to offer first: the one last watched, otherwise the first of the first season. */
private fun startingEpisode(info: SeriesInfo, lastEpisodeId: Int?): Episode? {
    if (lastEpisodeId != null) {
        for (eps in info.episodes.values) {
            val e = eps.firstOrNull { it.id == lastEpisodeId }
            if (e != null) return e
        }
    }
    return info.seasons.firstOrNull()?.let { info.episodes[it]?.firstOrNull() }
}

@Composable
fun SeriesDetailScreen(vm: AppViewModel, series: Series) {
    val repo = vm.repo ?: return
    val load by rememberLoad(repo, series.seriesId, vm.reloadToken) { repo.seriesInfo(series.seriesId) }
    val info = (load as? LoadResult.Ok)?.value
    val lastEpisodeId = remember(series.seriesId, vm.stack.size) { vm.prefs.lastEpisode(series.seriesId) }
    val isFav = vm.isFavourite(ContentType.SERIES, series.seriesId)
    var chosenSeason by remember { mutableStateOf<Int?>(null) }
    var focusedEpisode by remember { mutableStateOf<Episode?>(null) }

    val start = info?.let { startingEpisode(it, lastEpisodeId) }
    val season = chosenSeason ?: start?.season ?: info?.seasons?.firstOrNull()
    val episodes = if (info != null && season != null) info.episodes[season].orEmpty() else emptyList()
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.cover?.takeIf { it.isNotBlank() } ?: series.cover

    Box(Modifier.fillMaxSize().background(Ember.Bg)) {
        Backdrop(backdrop)
        Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 32.dp, bottom = 16.dp)) {
            Text(series.name, style = Txt.Hero, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 64.dp))
            Spacer(Modifier.height(8.dp))
            val meta = listOfNotNull(
                (info?.year ?: series.year).takeIf { it.isNotBlank() },
                info?.let { "${it.seasons.size} " + (if (it.seasons.size == 1) "season" else "seasons") },
                ratingLabel(info?.rating ?: series.rating),
                (info?.genre ?: series.genre).takeIf { it.isNotBlank() },
            ).joinToString("   ·   ")
            if (meta.isNotEmpty()) Text(meta, style = Txt.Label, color = Ember.Dim)
            Spacer(Modifier.height(10.dp))
            val plot = focusedEpisode?.plot?.takeIf { it.isNotBlank() } ?: info?.plot ?: series.plot
            Text(
                if (load is LoadResult.Loading) "Loading episodes…" else plot,
                style = Txt.Body,
                color = Ember.Text.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(620.dp).height(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (start != null && info != null) {
                    val resumed = lastEpisodeId == start.id
                    TvButton(
                        (if (resumed) "Resume" else "Play") + "  S${start.season} E${start.number}",
                        icon = "▶",
                        focusKey = "detail:primary",
                        autoFocus = true,
                        onClick = {
                            val eps = info.episodes[start.season].orEmpty()
                            vm.playEpisodes(series, eps, eps.indexOfFirst { it.id == start.id }, fromStart = !resumed)
                        },
                    )
                }
                TvButton(
                    if (isFav) "In favourites" else "Favourite",
                    icon = if (isFav) "★" else "☆",
                    focusKey = "detail:fav",
                    autoFocus = start == null,
                    onClick = { vm.toggleFavourite(vm.favouriteOf(series)) },
                )
            }
            if (load is LoadResult.Err) {
                Spacer(Modifier.height(16.dp))
                Text((load as LoadResult.Err).message, style = Txt.Body, color = Color(0xFFFF8A80))
            }
            if (info != null && info.seasons.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(end = 64.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(info.seasons, key = { it }) { s ->
                        SeasonChip(
                            label = if (s == 0) "Specials" else "Season $s",
                            selected = s == season,
                            focusKey = "season:$s",
                            onFocus = {
                                if (chosenSeason != s) {
                                    chosenSeason = s
                                    focusedEpisode = null
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (season != null) {
                    key(season) {
                        EpisodeRow(
                            episodes = episodes,
                            startIndex = episodes.indexOfFirst { it.id == lastEpisodeId }.coerceAtLeast(0),
                            positionOf = { vm.prefs.position("ep:${it.id}") },
                            onFocus = { focusedEpisode = it },
                            onPlay = { index -> vm.playEpisodes(series, episodes, index, fromStart = false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, focusKey: String, onFocus: () -> Unit) {
    FocusBox(
        modifier = Modifier.height(40.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(20.dp),
        focusedScale = 1.05f,
        color = if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF),
        focusedColor = Color.White,
        onFocusChange = { if (it) onFocus() },
        onClick = onFocus,
    ) { focused ->
        Box(Modifier.fillMaxHeight().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(label, style = Txt.Label, color = if (focused) Color.Black else if (selected) Ember.Text else Ember.Dim)
        }
    }
}

@Composable
private fun EpisodeRow(
    episodes: List<Episode>,
    startIndex: Int,
    positionOf: (Episode) -> Long,
    onFocus: (Episode) -> Unit,
    onPlay: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    LazyRow(
        state = state,
        contentPadding = PaddingValues(end = 64.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(episodes, key = { _, e -> e.id }) { index, e ->
            EpisodeCard(
                episode = e,
                watchedMs = positionOf(e),
                onFocus = { onFocus(e) },
                onClick = { onPlay(index) },
            )
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, watchedMs: Long, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(230.dp)) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusKey = "ep:${episode.id}",
            focusedScale = 1.07f,
            onFocusChange = {
                focused = it
                if (it) onFocus()
            },
            onClick = onClick,
        ) {
            Placeholder("E ${episode.number}", Modifier.fillMaxSize())
            NetImage(episode.image, Modifier.fillMaxSize())
            if (watchedMs > 0) {
                val total = durationSeconds(episode.duration)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
                    if (total != null && total > 0) {
                        ProgressBar(watchedMs / 1000f / total, Modifier.fillMaxWidth())
                    } else {
                        Text("Continue", style = Txt.Caption, color = Ember.Accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "E${episode.number}  ${episode.title}",
            style = Txt.Label,
            color = if (focused) Ember.Text else Ember.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (episode.duration.isNotBlank()) {
            Text(prettyDuration(episode.duration), style = Txt.Caption, color = Ember.Faint)
        }
    }
}
