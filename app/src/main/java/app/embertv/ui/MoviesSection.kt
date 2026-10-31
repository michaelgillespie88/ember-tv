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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.ContentType
import app.embertv.data.FAVOURITES
import app.embertv.data.RECENT
import app.embertv.data.Movie
import app.embertv.data.Repository
import app.embertv.data.favouriteKey
import app.embertv.data.toMovie

@Composable
fun MoviesSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val section = vm.movies
    val cats by rememberQuietLoad(vm.vodVersion, repo, vm.reloadToken) { repo.movieCategories() }
    when (val c = cats) {
        is LoadResult.Loading -> SkeletonPage(posters = true)
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) {
                listOf(Category(FAVOURITES, "Favourites"), Category(RECENT, "Recently added"), Category(ALL, "All movies")) + c.value
            }
            val selectedId = section.categoryId ?: c.value.firstOrNull()?.id ?: ALL
            val select = rememberCategorySelector(section)
            Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 40.dp, top = 6.dp)) {
                CategoryRail(
                    categories = categories,
                    selectedId = selectedId,
                    state = section.rail,
                    keyPrefix = "movies",
                    onFocusCategory = { select(it.id, false) },
                    onSelectCategory = { select(it.id, true) },
                )
                Spacer(Modifier.width(24.dp))
                MovieGrid(vm, repo, selectedId, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MovieGrid(vm: AppViewModel, repo: Repository, categoryId: String, modifier: Modifier) {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.MOVIE } else null
    val load by rememberQuietLoad(vm.vodVersion, repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toMovie() } ?: repo.movies(categoryId)
    }
    when (val r = load) {
        is LoadResult.Loading -> SkeletonPosters(modifier)
        is LoadResult.Err -> ErrorView(r.message, modifier) { vm.reload() }
        is LoadResult.Ok -> {
            val movies = r.value
            if (movies.isEmpty()) {
                EmptyView(
                    if (categoryId == FAVOURITES) "${holdPrompt()} any movie to add it to your favourites."
                    else "No movies in this category.",
                    modifier,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    state = vm.movies.grid(categoryId),
                    modifier = modifier.fillMaxHeight(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 48.dp, start = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(movies, key = { it.streamId }) { m ->
                        PosterCard(
                            title = m.name,
                            image = m.poster,
                            focusKey = "movie:$categoryId:${m.streamId}",
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
}

/** Full-bleed backdrop with the scrims that keep text readable over any artwork. */
@Composable
fun Backdrop(url: String) {
    Box(Modifier.fillMaxSize()) {
        NetImage(url, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Ember.Bg,
                    0.45f to Ember.Bg.copy(alpha = 0.88f),
                    1f to Ember.Bg.copy(alpha = 0.15f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Ember.Bg)
            )
        )
    }
}

@Composable
fun MovieDetailScreen(vm: AppViewModel, movie: Movie) {
    val repo = vm.repo ?: return
    val load by rememberLoad(repo, movie.streamId) { repo.movieInfo(movie.streamId) }
    val info = (load as? LoadResult.Ok)?.value
    val key = "movie:${movie.streamId}"
    val resume = remember(key, vm.stack.size, vm.versionsOf(movie).size) { vm.moviePosition(movie) }
    LaunchedEffect(movie.streamId) { vm.loadVersions(movie) }
    val isFav = vm.isFavourite(ContentType.MOVIE, movie.streamId)
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.poster?.takeIf { it.isNotBlank() } ?: movie.poster
    val poster = info?.poster?.takeIf { it.isNotBlank() } ?: movie.poster
    // Cast photos, trailer and suggestions (TMDB when set up), then more like it from the library.
    val extras by produceState<app.embertv.data.Extras?>(null, movie.streamId, info, vm.tmdbKey) { value = vm.movieExtras(movie, info) }
    val more by produceState<List<Movie>>(emptyList(), movie.streamId, extras) { value = vm.moreLikeThis(movie, extras) }
    val trailer = info?.trailer?.takeIf { it.isNotBlank() } ?: extras?.trailer?.takeIf { it.isNotBlank() }

    Box(Modifier.fillMaxSize().background(Ember.Bg)) {
        Backdrop(backdrop)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hero") {
        Row(
            Modifier.fillParentMaxHeight().fillMaxWidth().padding(horizontal = 64.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(movie.name, style = Txt.Hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val meta = listOfNotNull(
                    if (vm.movieWatched(movie)) "Watched" else null,
                    info?.year?.takeIf { it.isNotBlank() },
                    info?.duration?.takeIf { it.isNotBlank() }?.let { prettyDuration(it) },
                    ratingLabel(info?.rating ?: movie.rating),
                    info?.genre?.takeIf { it.isNotBlank() },
                ).joinToString("   ·   ")
                if (meta.isNotEmpty()) Text(meta, style = Txt.Label, color = Ember.Dim)
                val movieQualities = movie.copy(versions = vm.versionsOf(movie)).qualities()
                if (movieQualities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QualityChips(movieQualities)
                }
                Spacer(Modifier.height(18.dp))
                when {
                    load is LoadResult.Loading -> SkeletonLines(Modifier.width(520.dp))
                    info != null && info.plot.isNotBlank() -> Text(
                        info.plot,
                        style = Txt.Body,
                        color = Ember.Text.copy(alpha = 0.9f),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(560.dp),
                    )
                }
                if (info != null && (info.cast.isNotBlank() || info.director.isNotBlank())) {
                    Spacer(Modifier.height(14.dp))
                    if (info.director.isNotBlank()) {
                        Text("Director  ${info.director}", style = Txt.Caption, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (info.cast.isNotBlank()) {
                        Text(
                            "Starring  ${info.cast}",
                            style = Txt.Caption,
                            color = Ember.Dim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(560.dp),
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (resume > 0) {
                        TvButton(
                            "Resume from ${formatMs(resume)}",
                            icon = "▶",
                            focusKey = "detail:primary",
                            autoFocus = true,
                            onClick = { vm.playMovie(movie, fromStart = false) },
                        )
                        TvButton(
                            "Start over",
                            icon = "↺",
                            focusKey = "detail:start",
                            onClick = { vm.playMovie(movie, fromStart = true) },
                        )
                    } else {
                        TvButton(
                            "Play",
                            icon = "▶",
                            focusKey = "detail:primary",
                            autoFocus = true,
                            onClick = { vm.playMovie(movie, fromStart = true) },
                        )
                    }
                    if (trailer != null) {
                        TvButton("Trailer", icon = "▷", focusKey = "detail:trailer", onClick = { vm.playTrailer(trailer) })
                    }
                    TvButton(
                        if (isFav) "In favourites" else "Favourite",
                        icon = if (isFav) "★" else "☆",
                        focusKey = "detail:fav",
                        onClick = { vm.toggleFavourite(vm.favouriteOf(movie)) },
                    )
                }
                if (extras?.cast?.isNotEmpty() == true || more.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    RemoteHints(listOf(Hint("▼", "Cast and more like this")))
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(
                Modifier
                    .width(230.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Placeholder(movie.name, Modifier.fillMaxSize(), fontSizeSp = 34)
                NetImage(poster, Modifier.fillMaxSize())
            }
        }
        }
        item(key = "cast") { CastRow(extras?.cast.orEmpty(), 64.dp) }
        item(key = "more") { MoreMoviesRow(vm, more, 64.dp) }
        item(key = "end") { Spacer(Modifier.height(48.dp)) }
        }
    }
}
