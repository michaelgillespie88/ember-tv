package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.embertv.data.ContentType
import app.embertv.data.Episode
import app.embertv.data.Movie
import app.embertv.data.Series

/** Detail page frame: content scrolls under a floating frosted back button. */
@Composable
private fun PhoneDetailFrame(vm: AppViewModel, content: @Composable (Modifier) -> Unit) {
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().background(Ember.Bg)) {
        content(Modifier.fillMaxSize().backdropSource(backdrop).background(Ember.Bg))
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(10.dp)
                .size(44.dp)
                .frosted(backdrop, CircleShape, tint = Color(0x66101014))
                .clickable { vm.pop() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(EmberIcons.Back, contentDescription = "Back", tint = Ember.Text, modifier = Modifier.size(22.dp))
        }
    }
}

/** Wide artwork at the top of a detail page, fading into the background. */
@Composable
private fun PhoneHeroImage(url: String, fallbackName: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
        Placeholder(fallbackName, Modifier.fillMaxSize(), fontSizeSp = 40)
        NetImage(url, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99000000), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Ember.Bg)
            )
        )
    }
}

@Composable
private fun MetaLine(parts: List<String?>) {
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotEmpty()) Text(text, style = Txt.Label.copy(fontSize = Txt.Caption.fontSize), color = Ember.Dim)
}

@Composable
fun PhoneMovieDetail(vm: AppViewModel, movie: Movie) {
    val repo = vm.repo ?: return
    val load by rememberLoad(repo, movie.streamId) { repo.movieInfo(movie.streamId) }
    val info = (load as? LoadResult.Ok)?.value
    val resume = remember(movie.streamId, vm.stack.size, vm.versionsOf(movie).size) { vm.moviePosition(movie) }
    LaunchedEffect(movie.streamId) { vm.loadVersions(movie) }
    val isFav = vm.isFavourite(ContentType.MOVIE, movie.streamId)
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.poster?.takeIf { it.isNotBlank() } ?: movie.poster

    PhoneDetailFrame(vm) { frame ->
    LazyColumn(
        frame,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { PhoneHeroImage(backdrop, movie.name) }
        item {
            Column(Modifier.padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
                Text(movie.name, style = Txt.LargeTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                MetaLine(
                    listOf(
                        info?.year,
                        info?.duration?.takeIf { it.isNotBlank() }?.let { prettyDuration(it) },
                        ratingLabel(info?.rating ?: movie.rating),
                        info?.genre,
                    )
                )
                val movieQualities = movie.copy(versions = vm.versionsOf(movie)).qualities()
                if (movieQualities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QualityChips(movieQualities)
                }
                Spacer(Modifier.height(18.dp))
                if (resume > 0) {
                    PillButton("Resume from ${formatMs(resume)}", icon = EmberIcons.Play, primary = true, modifier = Modifier.fillMaxWidth()) {
                        vm.playMovie(movie, fromStart = false)
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton("Start over", icon = EmberIcons.Restart, modifier = Modifier.fillMaxWidth()) {
                        vm.playMovie(movie, fromStart = true)
                    }
                } else {
                    PillButton("Play", icon = EmberIcons.Play, primary = true, modifier = Modifier.fillMaxWidth()) {
                        vm.playMovie(movie, fromStart = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PillButton(
                    if (isFav) "In favourites" else "Add to favourites",
                    icon = if (isFav) EmberIcons.StarFilled else EmberIcons.Star,
                    modifier = Modifier.fillMaxWidth(),
                ) { vm.toggleFavourite(vm.favouriteOf(movie)) }
                Spacer(Modifier.height(20.dp))
                when {
                    load is LoadResult.Loading -> Text("Loading details…", style = Txt.Body, color = Ember.Faint)
                    info != null && info.plot.isNotBlank() -> Text(info.plot, style = Txt.Body, color = Ember.Text.copy(alpha = 0.9f))
                }
                if (info != null && info.director.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Director  ${info.director}", style = Txt.Caption, color = Ember.Dim)
                }
                if (info != null && info.cast.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Starring  ${info.cast}", style = Txt.Caption, color = Ember.Dim)
                }
            }
        }
    }
    }
}

@Composable
fun PhoneSeriesDetail(vm: AppViewModel, series: Series) {
    val repo = vm.repo ?: return
    val load by rememberLoad(repo, series.seriesId, vm.reloadToken) { repo.seriesInfo(series.seriesId) }
    val info = (load as? LoadResult.Ok)?.value
    val lastEpisodeId = remember(series.seriesId, vm.stack.size) { vm.prefs.lastEpisode(series.seriesId) }
    val isFav = vm.isFavourite(ContentType.SERIES, series.seriesId)
    var chosenSeason by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(series.seriesId) { vm.loadVersions(series) }

    val start = info?.let { startingEpisode(it, lastEpisodeId) }
    val season = chosenSeason ?: start?.season ?: info?.seasons?.firstOrNull()
    val episodes = if (info != null && season != null) info.episodes[season].orEmpty() else emptyList()
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.cover?.takeIf { it.isNotBlank() } ?: series.cover

    PhoneDetailFrame(vm) { frame ->
    LazyColumn(
        frame,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { PhoneHeroImage(backdrop, series.name) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(series.name, style = Txt.LargeTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                MetaLine(
                    listOf(
                        info?.year ?: series.year,
                        info?.let { "${it.seasons.size} " + (if (it.seasons.size == 1) "season" else "seasons") },
                        ratingLabel(info?.rating ?: series.rating),
                        info?.genre ?: series.genre,
                    )
                )
                val seriesQualities = series.copy(versions = vm.versionsOf(series)).qualities()
                if (seriesQualities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QualityChips(seriesQualities)
                }
                Spacer(Modifier.height(18.dp))
                if (start != null && info != null) {
                    val resumed = lastEpisodeId == start.id
                    PillButton(
                        (if (resumed) "Resume" else "Play") + "  S${start.season} E${start.number}",
                        icon = EmberIcons.Play,
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val eps = info.episodes[start.season].orEmpty()
                        vm.playEpisodes(series, eps, eps.indexOfFirst { it.id == start.id }, fromStart = !resumed)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                PillButton(
                    if (isFav) "In favourites" else "Add to favourites",
                    icon = if (isFav) EmberIcons.StarFilled else EmberIcons.Star,
                    modifier = Modifier.fillMaxWidth(),
                ) { vm.toggleFavourite(vm.favouriteOf(series)) }
                Spacer(Modifier.height(18.dp))
                val plot = info?.plot?.takeIf { it.isNotBlank() } ?: series.plot
                when {
                    load is LoadResult.Loading -> Text("Loading episodes…", style = Txt.Body, color = Ember.Faint)
                    load is LoadResult.Err -> Text((load as LoadResult.Err).message, style = Txt.Body, color = Color(0xFFFF8A80))
                    plot.isNotBlank() -> Text(plot, style = Txt.Body, color = Ember.Text.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (info != null && info.seasons.size > 1) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(info.seasons, key = { it }) { s ->
                        val selected = s == season
                        Box(
                            Modifier
                                .height(36.dp)
                                .then(
                                    if (selected) Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White)
                                    else Modifier.glassSurface(RoundedCornerShape(18.dp), Color(0x14FFFFFF))
                                )
                                .clickable { chosenSeason = s }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (s == 0) "Specials" else "Season $s",
                                style = Txt.Label,
                                color = if (selected) Color.Black else Ember.Dim,
                            )
                        }
                    }
                }
            }
        } else {
            item { Spacer(Modifier.height(16.dp)) }
        }
        itemsIndexed(episodes, key = { _, e -> e.id }) { index, e ->
            PhoneEpisodeRow(
                episode = e,
                watchedMs = vm.prefs.position("ep:${e.id}"),
                onClick = { vm.playEpisodes(series, episodes, index, fromStart = false) },
            )
        }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
    }
    }
}

@Composable
private fun PhoneEpisodeRow(episode: Episode, watchedMs: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Placeholder("E ${episode.number}", Modifier.fillMaxSize(), fontSizeSp = 16)
            NetImage(episode.image, Modifier.fillMaxSize())
            if (watchedMs > 0) {
                val total = durationSeconds(episode.duration)
                if (total != null && total > 0) {
                    ProgressBar(
                        watchedMs / 1000f / total,
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(EmberIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${episode.number}. ${episode.title}", style = Txt.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (episode.duration.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(prettyDuration(episode.duration), style = Txt.Caption, color = Ember.Faint)
            }
            if (episode.plot.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(episode.plot, style = Txt.Caption, color = Ember.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
