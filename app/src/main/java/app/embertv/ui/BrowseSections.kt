package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.DEFAULT_USER_AGENT
import app.embertv.data.Movie
import app.embertv.data.Repository
import app.embertv.data.Series
import app.embertv.data.favouriteKey
import app.embertv.data.toChannel
import app.embertv.data.toMovie
import app.embertv.data.toSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------- Favourites ----------------

@Composable
fun FavouritesSection(vm: AppViewModel) {
    val channels = vm.favourites.filter { it.type == ContentType.LIVE }.map { it.toChannel() }
    val movies = vm.favourites.filter { it.type == ContentType.MOVIE }.map { it.toMovie() }
    val series = vm.favourites.filter { it.type == ContentType.SERIES }.map { it.toSeries() }

    if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) {
        EmptyView("Nothing here yet.\nHold OK on any channel, movie or series to add it to your favourites.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        if (channels.isNotEmpty()) {
            item(key = "fav-channels") {
                ChannelShelf(vm, "Channels", channels, keyPrefix = "fav")
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "fav-movies") {
                MovieShelf(vm, "Movies", movies, keyPrefix = "fav")
            }
        }
        if (series.isNotEmpty()) {
            item(key = "fav-series") {
                SeriesShelf(vm, "Series", series, keyPrefix = "fav")
            }
        }
    }
}

@Composable
private fun ChannelShelf(vm: AppViewModel, title: String, channels: List<Channel>, keyPrefix: String) {
    Column {
        SectionHeader(title, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { index, c ->
                ChannelCard(
                    name = c.name,
                    logo = c.icon,
                    focusKey = "$keyPrefix:ch:${c.streamId}",
                    onClick = { vm.push(Screen.PlayLive(channels, index)) },
                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(c)) },
                )
            }
        }
    }
}

@Composable
private fun MovieShelf(vm: AppViewModel, title: String, movies: List<Movie>, keyPrefix: String) {
    Column {
        SectionHeader(title, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(movies, key = { it.streamId }) { m ->
                PosterCard(
                    title = m.name,
                    image = m.poster,
                    focusKey = "$keyPrefix:mv:${m.streamId}",
                    modifier = Modifier.width(140.dp),
                    favourite = vm.favKeys.contains(favouriteKey(ContentType.MOVIE, m.streamId)),
                    onClick = { vm.push(Screen.MovieDetail(m)) },
                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(m)) },
                )
            }
        }
    }
}

@Composable
private fun SeriesShelf(vm: AppViewModel, title: String, series: List<Series>, keyPrefix: String) {
    Column {
        SectionHeader(title, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(series, key = { it.seriesId }) { s ->
                PosterCard(
                    title = s.name,
                    image = s.cover,
                    focusKey = "$keyPrefix:sr:${s.seriesId}",
                    modifier = Modifier.width(140.dp),
                    favourite = vm.favKeys.contains(favouriteKey(ContentType.SERIES, s.seriesId)),
                    onClick = { vm.push(Screen.SeriesDetail(s)) },
                    onLongClick = { vm.toggleFavourite(vm.favouriteOf(s)) },
                )
            }
        }
    }
}

// ---------------- Search ----------------

private class Catalogue(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>)

private class SearchResults(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

private suspend fun loadCatalogue(repo: Repository): Catalogue {
    suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    return Catalogue(
        channels = safely { repo.allChannels() },
        movies = safely { repo.allMovies() },
        series = safely { repo.allSeries() },
    )
}

@Composable
fun SearchSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val query = vm.searchQuery.trim()
    // The full catalogue can be large, so only fetch it once someone actually searches.
    val wanted = query.length >= 2
    val catalogue by rememberLoad(repo, vm.reloadToken, wanted) {
        if (wanted) loadCatalogue(repo) else null
    }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    val cat = (catalogue as? LoadResult.Ok)?.value

    LaunchedEffect(query, cat) {
        if (query.length < 2 || cat == null) {
            results = null
            return@LaunchedEffect
        }
        delay(250)
        results = withContext(Dispatchers.Default) {
            SearchResults(
                channels = cat.channels.filter { it.name.contains(query, ignoreCase = true) }.take(60),
                movies = cat.movies.filter { it.name.contains(query, ignoreCase = true) }.take(60),
                series = cat.series.filter { it.name.contains(query, ignoreCase = true) }.take(60),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 48.dp, end = 48.dp, top = 10.dp).width(620.dp)) {
            TvTextField(
                label = "",
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it },
                placeholder = "Search channels, movies and series",
                imeAction = ImeAction.Search,
            )
        }
        val r = results
        when {
            query.length < 2 -> EmptyView("Type at least two letters to search everything your provider offers.")
            catalogue is LoadResult.Loading || (cat != null && r == null) ->
                LoadingView(text = "Searching your catalogue…")
            r != null && !r.isEmpty -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                if (r.channels.isNotEmpty()) item(key = "s-ch") { ChannelShelf(vm, "Channels", r.channels, "search") }
                if (r.movies.isNotEmpty()) item(key = "s-mv") { MovieShelf(vm, "Movies", r.movies, "search") }
                if (r.series.isNotEmpty()) item(key = "s-sr") { SeriesShelf(vm, "Series", r.series, "search") }
            }
            else -> EmptyView("Nothing matches \"$query\".")
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsSection(vm: AppViewModel) {
    var confirmSignOut by remember { mutableStateOf(false) }
    val account = vm.account
    val info = vm.accountInfo

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Ember.Panel)
                .padding(26.dp)
        ) {
            Text("Account", style = Txt.Headline)
            Spacer(Modifier.height(16.dp))
            InfoLine("Server", account?.server?.removePrefix("http://")?.removePrefix("https://") ?: "–")
            InfoLine("Username", account?.username ?: "–")
            InfoLine("Status", info?.status ?: "Checking…")
            InfoLine(
                "Expires",
                when {
                    info == null -> "–"
                    info.expiry == null -> "Never"
                    else -> formatDate(info.expiry)
                },
            )
            InfoLine(
                "Connections",
                if (info == null) "–" else "${info.activeConnections.ifEmpty { "0" }} of ${info.maxConnections.ifEmpty { "?" }} in use",
            )
            Spacer(Modifier.height(18.dp))
            Text("Ember TV ${app.embertv.BuildConfig.VERSION_NAME}", style = Txt.Caption, color = Ember.Faint)
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow(
                "Stream format",
                if (vm.streamFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)",
                "Switch if channels buffer or won't start",
                focusKey = "set:format",
            ) { vm.toggleStreamFormat() }
            SettingRow(
                "Channel up / down",
                if (vm.invertZap) "Up goes to the previous channel" else "Up goes to the next channel",
                null,
                focusKey = "set:zap",
            ) { vm.toggleInvertZap() }
            SettingRow(
                "Player identity",
                if (vm.userAgent == DEFAULT_USER_AGENT) "Ember TV" else "VLC compatible",
                "Some providers only accept certain players",
                focusKey = "set:ua",
            ) { vm.toggleUserAgent() }
            SettingRow("Refresh channels and guide", "", "Reloads everything from your provider", focusKey = "set:refresh") {
                vm.reload()
                vm.refreshAccountInfo()
                vm.notify("Refreshing from your provider…")
            }
            SettingRow("Sign out", "", "Removes your login from this TV", focusKey = "set:signout") {
                confirmSignOut = true
            }
        }
    }

    if (confirmSignOut) {
        Dialog(onDismissRequest = { confirmSignOut = false }) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ember.Panel)
                    .padding(32.dp)
            ) {
                Text("Sign out of Ember TV?", style = Txt.Title)
                Spacer(Modifier.height(10.dp))
                Text("Your favourites stay on this TV. You'll need your login details to sign back in.", style = Txt.Body, color = Ember.Dim)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Cancel", autoFocus = true, onClick = { confirmSignOut = false })
                    TvButton("Sign out", onClick = {
                        confirmSignOut = false
                        vm.signOut()
                    })
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = Txt.Label, color = Ember.Dim, modifier = Modifier.width(112.dp))
        Text(value, style = Txt.Label, color = Ember.Text, maxLines = 2)
    }
}

@Composable
private fun SettingRow(title: String, value: String, hint: String?, focusKey: String, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(if (hint != null) 70.dp else 58.dp),
        focusKey = focusKey,
        focusedScale = 1.03f,
        color = Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Txt.Label, color = if (focused) Color.Black else Ember.Text)
                if (hint != null) {
                    Text(hint, style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Faint)
                }
            }
            if (value.isNotEmpty()) {
                Text(value, style = Txt.Label, color = if (focused) Color(0xFF333338) else Ember.Dim)
            }
        }
    }
}
