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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.embertv.data.Movie
import app.embertv.data.Repository
import app.embertv.data.favouriteKey
import app.embertv.data.toMovie

@Composable
fun MoviesSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val section = vm.movies
    val cats by rememberLoad(repo, vm.reloadToken) { repo.movieCategories() }
    when (val c = cats) {
        is LoadResult.Loading -> LoadingView(text = "Loading movies…")
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) {
                listOf(Category(FAVOURITES, "★  Favourites"), Category(ALL, "All movies")) + c.value
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
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toMovie() } ?: repo.movies(categoryId)
    }
    when (val r = load) {
        is LoadResult.Loading -> LoadingView(modifier)
        is LoadResult.Err -> ErrorView(r.message, modifier) { vm.reload() }
        is LoadResult.Ok -> {
            val movies = r.value
            if (movies.isEmpty()) {
                EmptyView(
                    if (categoryId == FAVOURITES) "Hold OK on any movie to add it to your favourites."
                    else "No movies in this category.",
                    modifier,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 138.dp),
                    state = vm.movies.grid(categoryId),
                    modifier = modifier.fillMaxHeight(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 48.dp, start = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(movies, key = { it.streamId }) { m ->
                        PosterCard(
                            title = m.name,
                            image = m.poster,
                            focusKey = "movie:$categoryId:${m.streamId}",
                            subtitle = ratingLabel(m.rating),
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
    val resume = remember(key, vm.stack.size) { vm.prefs.position(key) }
    val isFav = vm.isFavourite(ContentType.MOVIE, movie.streamId)
    val backdrop = info?.backdrop?.takeIf { it.isNotBlank() } ?: info?.poster?.takeIf { it.isNotBlank() } ?: movie.poster
    val poster = info?.poster?.takeIf { it.isNotBlank() } ?: movie.poster

    Box(Modifier.fillMaxSize().background(Ember.Bg)) {
        Backdrop(backdrop)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(movie.name, style = Txt.Hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                val meta = listOfNotNull(
                    info?.year?.takeIf { it.isNotBlank() },
                    info?.duration?.takeIf { it.isNotBlank() }?.let { prettyDuration(it) },
                    ratingLabel(info?.rating ?: movie.rating),
                    info?.genre?.takeIf { it.isNotBlank() },
                ).joinToString("   ·   ")
                if (meta.isNotEmpty()) Text(meta, style = Txt.Label, color = Ember.Dim)
                Spacer(Modifier.height(18.dp))
                when {
                    load is LoadResult.Loading -> Text("Loading details…", style = Txt.Body, color = Ember.Faint)
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
                    TvButton(
                        if (isFav) "In favourites" else "Favourite",
                        icon = if (isFav) "★" else "☆",
                        focusKey = "detail:fav",
                        onClick = { vm.toggleFavourite(vm.favouriteOf(movie)) },
                    )
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
}
