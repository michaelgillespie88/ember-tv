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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
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
                .pressable { vm.pop() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(EmberIcons.Back, contentDescription = "Back", tint = Ember.Text, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Apple TV app style top of a detail page: tall artwork filling the screen's width and fading into
 * the page, with the title and details centred over the bottom of it. Sideways, wide artwork instead.
 */
@Composable
private fun PhoneHeroImage(poster: String, wide: String, title: String, meta: List<String?>, extra: @Composable () -> Unit = {}) {
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val art = if (landscape) wide.ifBlank { poster } else poster.ifBlank { wide }
    Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 2.2f else 4f / 5.2f)) {
        Placeholder(title, Modifier.fillMaxSize(), fontSizeSp = 40)
        NetImage(art, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x80000000), 0.2f to Color.Transparent, 0.5f to Color.Transparent, 0.82f to Ember.Bg.copy(alpha = 0.9f), 1f to Ember.Bg)
            )
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                app.embertv.data.Versions.cleanTitle(title),
                style = Txt.LargeTitle.copy(fontSize = (30f * Txt.scale).sp, lineHeight = (34f * Txt.scale).sp, letterSpacing = (-0.5).sp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val text = meta.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(text, style = Txt.Label.copy(fontSize = 14.sp), color = Ember.Text.copy(alpha = 0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
            }
            extra()
        }
    }
}

/** Apple's action row: a wide white Play, then round glass buttons for everything else. */
@Composable
private fun ActionRow(play: String, onPlay: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(play, Modifier.widthIn(min = 150.dp, max = 220.dp), icon = EmberIcons.Play, primary = true, onClick = onPlay)
        content()
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconCircleButton(icon, label, size = 48.dp, background = Color(0x26FFFFFF), glass = true, onClick = onClick)
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
    val extras by produceState<app.embertv.data.Extras?>(null, movie.streamId, info, vm.tmdbKey) { value = vm.movieExtras(movie, info) }
    val more by produceState<List<Movie>>(emptyList(), movie.streamId, extras) { value = vm.moreLikeThis(movie, extras) }
    val trailer = info?.trailer?.takeIf { it.isNotBlank() } ?: extras?.trailer?.takeIf { it.isNotBlank() }

    PhoneDetailFrame(vm) { frame ->
    LazyColumn(
        frame,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        val movieQualities = movie.copy(versions = vm.versionsOf(movie)).qualities()
        item {
            PhoneHeroImage(
                movie.poster.ifBlank { info?.poster.orEmpty() },
                backdrop,
                movie.name,
                listOf(
                    "Film",
                    info?.genre?.substringBefore(",")?.substringBefore("/")?.trim(),
                    info?.year,
                    info?.duration?.takeIf { it.isNotBlank() }?.let { prettyDuration(it) },
                    ratingLabel(info?.rating ?: movie.rating),
                    if (vm.movieWatched(movie)) "Watched" else null,
                ),
            ) {
                if (movieQualities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QualityChips(movieQualities, plan = vm.qualityPlan)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(18.dp))
                ActionRow(if (resume > 0) "Resume" else "Play", onPlay = { vm.playMovie(movie, fromStart = resume <= 0) }) {
                    if (resume > 0) RoundAction(EmberIcons.Restart, "Start over") { vm.playMovie(movie, fromStart = true) }
                    if (trailer != null) RoundAction(EmberIcons.Movies, "Trailer") { vm.playTrailer(trailer) }
                    // Pick the quality yourself, whatever Settings says.
                    if (movieQualities.size > 1) RoundAction(EmberIcons.Sliders, "Choose quality") { vm.chooseMovieQuality(movie, fromStart = resume <= 0) }
                    RoundAction(if (isFav) EmberIcons.Check else EmberIcons.Plus, if (isFav) "In My List" else "Add to My List") { vm.toggleFavourite(vm.favouriteOf(movie)) }
                }
                if (resume > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("${formatMs(resume)} watched", style = Txt.Caption, color = Ember.Dim)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(20.dp))
                when {
                    load is LoadResult.Loading -> SkeletonLines(Modifier.fillMaxWidth())
                    info != null && info.plot.isNotBlank() -> Text(info.plot, style = Txt.Body, color = Ember.Text.copy(alpha = 0.9f))
                }
                if (info != null && info.director.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Director  ${info.director}", style = Txt.Caption, color = Ember.Dim)
                }
                if (info != null && info.cast.isNotBlank() && extras?.cast.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Starring  ${info.cast}", style = Txt.Caption, color = Ember.Dim)
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        item(key = "cast") { CastRow(extras?.cast.orEmpty(), 16.dp) }
        item(key = "more") { MoreMoviesRow(vm, more, 16.dp) }
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
    val extras by produceState<app.embertv.data.Extras?>(null, series.seriesId, info, vm.tmdbKey) { value = vm.seriesExtras(series, info) }
    val more by produceState<List<Series>>(emptyList(), series.seriesId, extras) { value = vm.moreLikeThis(series, extras) }
    val trailer = info?.trailer?.takeIf { it.isNotBlank() } ?: extras?.trailer?.takeIf { it.isNotBlank() }

    val start = info?.let { startingEpisode(it, lastEpisodeId) }
    val season = chosenSeason ?: start?.season ?: info?.seasons?.firstOrNull()
    val episodes = if (info != null && season != null) info.episodes[season].orEmpty() else emptyList()
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.cover?.takeIf { it.isNotBlank() } ?: series.cover

    PhoneDetailFrame(vm) { frame ->
    LazyColumn(
        frame,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        val seriesQualities = series.copy(versions = vm.versionsOf(series)).qualities()
        item {
            PhoneHeroImage(
                series.cover.ifBlank { info?.cover.orEmpty() },
                backdrop,
                series.name,
                listOf(
                    "Series",
                    (info?.genre ?: series.genre).substringBefore(",").substringBefore("/").trim(),
                    info?.year ?: series.year,
                    info?.let { "${it.seasons.size} " + (if (it.seasons.size == 1) "season" else "seasons") },
                    ratingLabel(info?.rating ?: series.rating),
                ),
            ) {
                if (seriesQualities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QualityChips(seriesQualities)
                }
            }
        }
        item {
            Column {
                Spacer(Modifier.height(18.dp))
                val resumed = start != null && lastEpisodeId == start.id
                ActionRow(
                    if (start == null) "Play" else (if (resumed) "Resume" else "Play") + " S${start.season} E${start.number}",
                    onPlay = {
                        if (start != null && info != null) {
                            val eps = info.episodes[start.season].orEmpty()
                            vm.playEpisodes(series, eps, eps.indexOfFirst { it.id == start.id }, fromStart = !resumed)
                        }
                    },
                ) {
                    if (trailer != null) RoundAction(EmberIcons.Movies, "Trailer") { vm.playTrailer(trailer) }
                    RoundAction(if (isFav) EmberIcons.Check else EmberIcons.Plus, if (isFav) "In My List" else "Add to My List") { vm.toggleFavourite(vm.favouriteOf(series)) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(18.dp))
                val plot = info?.plot?.takeIf { it.isNotBlank() } ?: series.plot
                when {
                    load is LoadResult.Loading -> SkeletonRows(Modifier.height(260.dp), rows = 4, underHeader = false)
                    load is LoadResult.Err -> Text((load as LoadResult.Err).message, style = Txt.Body, color = Color(0xFFFF8A80))
                    plot.isNotBlank() -> Text(plot, style = Txt.Body, color = Ember.Text.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (info != null && info.seasons.size > 1) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
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
                                .pressable { chosenSeason = s }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (s == 0) "Specials" else "Season $s",
                                style = Txt.Label,
                                color = if (selected) Color.Black else Ember.Text.copy(alpha = 0.85f),
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
                watched = vm.isWatched("ep:${e.id}"),
                watchedMs = vm.prefs.position("ep:${e.id}"),
                onClick = { vm.playEpisodes(series, episodes, index, fromStart = false) },
            )
        }
        item(key = "cast") { CastRow(extras?.cast.orEmpty(), 16.dp) }
        item(key = "more") { MoreSeriesRow(vm, more, 16.dp) }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
    }
    }
}

@Composable
private fun PhoneEpisodeRow(episode: Episode, watched: Boolean, watchedMs: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
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
            if (watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(5.dp))
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
                    .glassSurface(RoundedCornerShape(15.dp), Color(0x59000000)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(EmberIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${episode.number}. ${episode.title}", style = Txt.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
