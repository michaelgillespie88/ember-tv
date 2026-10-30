package app.embertv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.embertv.data.CastMember
import app.embertv.data.ContentType
import app.embertv.data.Movie
import app.embertv.data.Series
import app.embertv.data.favouriteKey

/** "Cast & Crew": round photos with names, like Apple TV. */
@Composable
fun CastRow(cast: List<CastMember>, edge: Dp) {
    if (cast.isEmpty()) return
    val isTv = LocalIsTv.current
    val size = if (isTv) 96.dp else 76.dp
    Column(Modifier.fillMaxWidth()) {
        Text("Cast & Crew", style = Txt.Headline, modifier = Modifier.padding(start = edge))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 14.dp),
        ) {
            items(cast, key = { it.name + it.role }) { p ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusBox(
                        modifier = Modifier.size(size),
                        focusKey = "cast:${p.name}",
                        shape = CircleShape,
                        focusedScale = 1.1f,
                        color = Ember.Card,
                        focusedColor = Ember.Card,
                        onFocusChange = { focused = it },
                        onClick = {},
                    ) { _ ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(initials(p.name), style = Txt.Headline, color = Ember.Dim)
                        }
                        NetImage(p.photo, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.name,
                        style = Txt.Caption,
                        color = if (focused) Ember.Text else Ember.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (p.role.isNotBlank()) {
                        Text(p.role, style = Txt.Caption, color = Ember.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** "More Like This" for a film. */
@Composable
fun MoreMoviesRow(vm: AppViewModel, movies: List<Movie>, edge: Dp) {
    if (movies.isEmpty()) return
    val isTv = LocalIsTv.current
    Column(Modifier.fillMaxWidth()) {
        Text("More Like This", style = Txt.Headline, modifier = Modifier.padding(start = edge))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
        ) {
            items(movies, key = { it.streamId }) { m ->
                PosterCard(
                    title = m.name,
                    image = m.poster,
                    focusKey = "more:m:${m.streamId}",
                    modifier = Modifier.width(if (isTv) 120.dp else 104.dp),
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

/** "More Like This" for a show. */
@Composable
fun MoreSeriesRow(vm: AppViewModel, series: List<Series>, edge: Dp) {
    if (series.isEmpty()) return
    val isTv = LocalIsTv.current
    Column(Modifier.fillMaxWidth()) {
        Text("More Like This", style = Txt.Headline, modifier = Modifier.padding(start = edge))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
        ) {
            items(series, key = { it.seriesId }) { s ->
                PosterCard(
                    title = s.name,
                    image = s.cover,
                    focusKey = "more:s:${s.seriesId}",
                    modifier = Modifier.width(if (isTv) 120.dp else 104.dp),
                    badge = badgeText(s.qualities()),
                    favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, s.seriesId)),
                    onClick = { vm.push(Screen.SeriesDetail(s)) },
                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(s)) },
                )
            }
        }
    }
}
