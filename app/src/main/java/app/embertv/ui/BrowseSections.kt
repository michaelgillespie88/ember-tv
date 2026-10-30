package app.embertv.ui

import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
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
        EmptyView("Nothing here yet.\n${holdPrompt()} any channel, movie or series to add it to your favourites.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = LocalContentPadding.current.calculateTopPadding() + 12.dp, bottom = LocalContentPadding.current.calculateBottomPadding() + 48.dp),
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
        SectionHeader(title, Modifier.padding(start = edgePadding()))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edgePadding(), vertical = 10.dp),
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

/** Programmes found in the guide: on now first, then coming up, then on catch-up. */
@Composable
private fun ProgrammeShelf(
    vm: AppViewModel,
    hits: List<app.embertv.data.ProgrammeHit>,
    progress: Pair<Int, Int>?,
    now: Long,
    onDetails: (app.embertv.data.ProgrammeHit) -> Unit,
) {
    val isTv = LocalIsTv.current
    Column {
        Row(Modifier.padding(start = edgePadding(), end = edgePadding()), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("On TV")
            if (progress != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "Searching the guide… ${progress.first} of ${progress.second} channels",
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
            }
        }
        if (hits.isEmpty()) {
            Text(
                if (progress == null) "Nothing on TV this week matches." else " ",
                style = Txt.Body,
                color = Ember.Faint,
                modifier = Modifier.padding(start = edgePadding(), top = 10.dp),
            )
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = edgePadding(), vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 10.dp),
        ) {
            items(hits, key = { "${it.channel.streamId}:${it.programme.start}" }) { h ->
                val p = h.programme
                val live = p.isLive(now)
                val catchup = p.end <= now && app.embertv.data.catchupAvailable(h.channel, p, now)
                FocusBox(
                    modifier = Modifier.width(if (isTv) 300.dp else 260.dp).height(if (isTv) 96.dp else 92.dp),
                    focusKey = "search:tv:${h.channel.streamId}:${p.start}",
                    shape = RoundedCornerShape(16.dp),
                    focusedScale = 1.05f,
                    color = Color(0x17FFFFFF),
                    focusedColor = Color.White,
                    onClick = { if (live) vm.playChannel(h.channel) else onDetails(h) },
                    onLongClick = { onDetails(h) },
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(h.channel.icon, h.channel.name, Modifier.size(width = 52.dp, height = 34.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.title,
                                style = Txt.Label.copy(fontWeight = FontWeight.SemiBold),
                                color = if (focused) Color.Black else Ember.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    live -> LiveTag()
                                    catchup -> Icon(EmberIcons.Catchup, "On catch-up", tint = if (focused) Color.Black else Ember.Accent, modifier = Modifier.size(14.dp))
                                    else -> {}
                                }
                                if (live || catchup) Spacer(Modifier.width(6.dp))
                                Text(
                                    if (live) "${(p.end - now) / 60} min left" else "${formatDay(p.start, now)} ${formatClock(p.start)}",
                                    style = Txt.Caption,
                                    color = if (focused) Color(0xFF444448) else Ember.Dim,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                h.channel.name,
                                style = Txt.Caption,
                                color = if (focused) Color(0xFF555559) else Ember.Faint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieShelf(vm: AppViewModel, title: String, movies: List<Movie>, keyPrefix: String) {
    Column {
        SectionHeader(title, Modifier.padding(start = edgePadding()))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edgePadding(), vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(movies, key = { it.streamId }) { m ->
                PosterCard(
                    title = m.name,
                    image = m.poster,
                    focusKey = "$keyPrefix:mv:${m.streamId}",
                    modifier = Modifier.width(140.dp),
                    badge = badgeText(m.qualities()),
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
        SectionHeader(title, Modifier.padding(start = edgePadding()))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edgePadding(), vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(series, key = { it.seriesId }) { s ->
                PosterCard(
                    title = s.name,
                    image = s.cover,
                    focusKey = "$keyPrefix:sr:${s.seriesId}",
                    modifier = Modifier.width(140.dp),
                    badge = badgeText(s.qualities()),
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
    var editing by remember { mutableStateOf(false) }
    val cat = (catalogue as? LoadResult.Ok)?.value

    // What's on TV: programme titles in the guide, found a channel at a time.
    var onTv by remember { mutableStateOf<List<app.embertv.data.ProgrammeHit>>(emptyList()) }
    var onTvProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var programmeDetails by remember { mutableStateOf<app.embertv.data.ProgrammeHit?>(null) }
    val now = LocalNow.current
    LaunchedEffect(query) {
        onTv = emptyList()
        onTvProgress = null
        if (query.length < 3) return@LaunchedEffect
        delay(600)
        val first = (vm.favourites.filter { it.type == ContentType.LIVE }.map { it.id } + vm.recentChannels.map { it.id }).distinct()
        app.embertv.data.ProgrammeSearch.search(repo, query, now, first) { checked, total, hits ->
            onTv = hits
            onTvProgress = checked to total
        }
        onTvProgress = null
    }

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
        Box(Modifier.padding(start = edgePadding(), end = edgePadding(), top = LocalContentPadding.current.calculateTopPadding() + 10.dp).fillMaxWidth().widthIn(max = 620.dp)) {
            EntryRow(
                label = "",
                value = vm.searchQuery,
                placeholder = "Search channels, films, series and what's on TV",
                focusKey = "search:box",
                onClick = { editing = true },
            )
        }
        val r = results
        when {
            query.length < 2 -> EmptyView("Press OK on the search box and type at least two letters to search everything your provider offers, including what's on TV this week.")
            catalogue is LoadResult.Loading || (cat != null && r == null) ->
                LoadingView(text = "Searching your catalogue…")
            (r != null && !r.isEmpty) || onTv.isNotEmpty() || onTvProgress != null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = LocalContentPadding.current.calculateBottomPadding() + 48.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                if (r != null && r.channels.isNotEmpty()) item(key = "s-ch") { ChannelShelf(vm, "Channels", r.channels, "search") }
                if (onTv.isNotEmpty() || onTvProgress != null) item(key = "s-tv") {
                    ProgrammeShelf(vm, onTv, onTvProgress, now) { programmeDetails = it }
                }
                if (r != null && r.movies.isNotEmpty()) item(key = "s-mv") { MovieShelf(vm, "Movies", r.movies, "search") }
                if (r != null && r.series.isNotEmpty()) item(key = "s-sr") { SeriesShelf(vm, "Series", r.series, "search") }
            }
            else -> EmptyView("Nothing matches \"$query\".")
        }
    }

    programmeDetails?.let { h ->
        ProgrammeDialog(vm, GuideFocus(h.channel, h.programme), listOf(h.channel)) { programmeDetails = null }
    }

    if (editing) {
        TextEntryDialog(
            title = "Search",
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            placeholder = "Channel, movie or series name",
            imeAction = ImeAction.Search,
            onDone = { editing = false },
        )
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsSection(vm: AppViewModel) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var managingCategories by remember { mutableStateOf(false) }
    var managingChannels by remember { mutableStateOf(false) }
    var pickingTeams by remember { mutableStateOf(false) }
    var choosingPlayer by remember { mutableStateOf(false) }
    var editingTmdb by remember { mutableStateOf(false) }
    var tmdbDraft by remember { mutableStateOf(vm.tmdbKey) }
    val kids = vm.profile?.kids == true
    val account = vm.account
    val info = vm.accountInfo
    val isTv = LocalIsTv.current
    val device = if (isTv) "TV" else "phone"

    val accountCard: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Ember.Panel)
                .padding(if (isTv) 26.dp else 20.dp)
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
            Text("Streammania ${app.embertv.BuildConfig.VERSION_NAME}", style = Txt.Caption, color = Ember.Faint)
        }
    }

    val settingsList: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- Profile ----
            SettingsHeader("Profile", first = true)
            SettingRow(
                "Profile",
                vm.profile?.name ?: "",
                "Switch, add or edit profiles",
                focusKey = "set:profile",
            ) { vm.switchProfile() }
            SettingRow(
                "My teams",
                if (vm.followedTeams.isEmpty()) "None" else vm.followedTeams.joinToString(", ") { it.shortName },
                "Goal alerts and their next match on Home",
                focusKey = "set:teams",
            ) { pickingTeams = true }
            if (!kids) SettingRow(
                "Live sport on Home",
                if (vm.sportOnHome) "On" else "Off",
                "Football on now across your channels, with the score",
                focusKey = "set:sport",
            ) { vm.toggleSportOnHome() }
            val p = vm.profile
            if (!kids && p != null) {
                val hidden = vm.categoryRules(p).keys.size
                SettingRow(
                    "Categories",
                    if (hidden == 0) "All shown" else "$hidden hidden",
                    "Hide categories you never watch",
                    focusKey = "set:categories",
                ) { managingCategories = true }
            }
            if (!kids) {
                val favChannels = vm.favourites.count { it.type == app.embertv.data.ContentType.LIVE }
                val hiddenCount = vm.hiddenChannels.size
                SettingRow(
                    "Channels",
                    listOfNotNull(
                        if (favChannels > 0) "$favChannels favourites" else null,
                        if (hiddenCount > 0) "$hiddenCount hidden" else null,
                    ).joinToString(" · ").ifEmpty { "All shown" },
                    "Put favourite channels in order and hide ones you never watch",
                    focusKey = "set:channels",
                ) { managingChannels = true }
            }

            // ---- Watching ----
            SettingsHeader("Watching")
            SettingRow(
                "Pause & rewind live TV",
                if (vm.timeshiftOn) "On" else "Off",
                "Uses your provider's catch-up, so it works on channels that have it (marked with the catch-up symbol)",
                focusKey = "set:timeshift",
            ) { vm.toggleTimeshift() }
            SettingRow(
                "When the app opens",
                vm.startOnLabel(),
                "Go straight to the last channel you watched or the TV guide, like a TV box",
                focusKey = "set:starton",
            ) { vm.cycleStartOn() }
            SettingRow(
                "Video player",
                vm.externalPlayerName(),
                "Use VLC or another app if something won't play",
                focusKey = "set:player",
            ) { choosingPlayer = true }
            SettingRow(
                "Quality to play",
                when (vm.qualityChoice) {
                    "best" -> "Best available"
                    "FHD" -> "Full HD"
                    "HD" -> "HD"
                    else -> "Ask each time"
                },
                "For films and shows that come in HD, FHD and 4K",
                focusKey = "set:quality",
            ) { vm.cycleQualityChoice() }
            SettingRow(
                "Combine versions",
                if (vm.mergeVersions) "On" else "Off",
                "Show each film or show once instead of once per quality",
                focusKey = "set:merge",
            ) { vm.toggleMergeVersions() }
            SettingRow(
                "Subtitles",
                if (vm.subtitleMode == "off") "Off" else "English",
                "Switch subtitles on automatically when available",
                focusKey = "set:subs",
            ) { vm.cycleSubtitles() }
            SettingRow(
                "Preferred audio",
                if (vm.audioLanguage.isEmpty()) "Stream default" else "English",
                "Pick English audio when a stream has several",
                focusKey = "set:audio",
            ) { vm.cycleAudioLanguage() }
            val sleepLeft = vm.sleepMinutesLeft()
            SettingRow(
                "Sleep timer",
                if (sleepLeft == null) "Off" else "Stops in $sleepLeft min",
                "Stop playback after a while",
                focusKey = "set:sleep",
            ) { vm.cycleSleepTimer() }
            if (isTv) {
                SettingRow(
                    "Channel up / down",
                    if (vm.invertZap) "Up goes to the previous channel" else "Up goes to the next channel",
                    null,
                    focusKey = "set:zap",
                ) { vm.toggleInvertZap() }
            } else {
                SettingRow(
                    "Channel swipe",
                    if (vm.invertZap) "Swipe down for the next channel" else "Swipe up for the next channel",
                    null,
                    focusKey = "set:zap",
                ) { vm.toggleInvertZap() }
            }

            // ---- TV ----
            if (isTv) {
                SettingsHeader("TV")
                SettingRow(
                    "Guide and channel list",
                    if (vm.compactLists) "Compact" else "Comfortable",
                    if (vm.compactLists) "About 10 channels in the guide and 8 in the channel list"
                    else "About 7 channels in the guide, with times on each programme",
                    focusKey = "set:compact",
                ) { vm.toggleCompactLists() }
                SettingRow(
                    "Live preview",
                    if (vm.livePreview) "On" else "Off",
                    "A small muted picture of the highlighted channel. Uses one provider connection",
                    focusKey = "set:preview",
                ) { vm.toggleLivePreview() }
                SettingRow(
                    "Match frame rate",
                    if (vm.matchFrameRate) "On" else "Off",
                    "Films and series only: switches to 24Hz and back. The screen may blink as it switches",
                    focusKey = "set:afr",
                ) { vm.toggleMatchFrameRate() }
                SettingRow(
                    "Screensaver",
                    if (vm.screensaverMinutes == 0) "Off" else "After ${vm.screensaverMinutes} min",
                    "Movie and series artwork when the remote is left alone",
                    focusKey = "set:screensaver",
                ) { vm.cycleScreensaver() }
                if (!kids) {
                    val phones = vm.pairedPhoneCount
                    SettingRow(
                        "Phones that can control this TV",
                        if (phones == 0) "None yet" else "$phones paired · select to unpair all",
                        "On your phone, tap the TV button on Home. Address if it's needed: ${vm.tvLinkAddress ?: "starting…"}",
                        focusKey = "set:phones",
                    ) { vm.forgetPairedPhones() }
                }
            }

            // ---- Connection (grown-ups only) ----
            if (!kids) {
                SettingsHeader("Connection")
                SettingRow(
                    "Stream format",
                    if (vm.streamFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)",
                    "Switch if channels buffer or won't start",
                    focusKey = "set:format",
                ) { vm.toggleStreamFormat() }
                SettingRow(
                    "Player identity",
                    if (vm.userAgent == DEFAULT_USER_AGENT) "Streammania" else "VLC compatible",
                    "Some providers only accept certain players",
                    focusKey = "set:ua",
                ) { vm.toggleUserAgent() }
            }

            // ---- App ----
            SettingsHeader("App")
            if (!kids) SettingRow(
                "Film details (TMDB)",
                if (vm.tmdbKey.isBlank()) "Off" else "Connected",
                "Cast photos, trailers and suggestions. Paste a free key from themoviedb.org",
                focusKey = "set:tmdb",
            ) { editingTmdb = true }
            SettingRow(
                "Check for updates",
                "Version ${app.embertv.BuildConfig.VERSION_NAME}",
                "Updates install straight from the app",
                focusKey = "set:update",
            ) { vm.checkForUpdates(manual = true) }
            SettingRow("Refresh channels and guide", "", "Reloads everything from your provider", focusKey = "set:refresh") {
                vm.reload()
                vm.refreshAccountInfo()
                vm.notify("Refreshing from your provider…")
            }
            // Kids profiles can't sign the whole device out.
            if (!kids) SettingRow("Sign out", "", "Removes your login from this $device", focusKey = "set:signout") {
                confirmSignOut = true
            }
        }
    }

    if (isTv) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
            accountCard(Modifier.width(360.dp))
            Spacer(Modifier.width(36.dp))
            settingsList(Modifier.width(520.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp))
        }
    } else {
        val pad = LocalContentPadding.current
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 16.dp, bottom = pad.calculateBottomPadding() + 16.dp)
        ) {
            accountCard(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            settingsList(Modifier.fillMaxWidth())
        }
    }

    if (pickingTeams) TeamPicker(vm) { pickingTeams = false }
    if (editingTmdb) {
        TextEntryDialog(
            title = "TMDB API key or Read Access Token (themoviedb.org → Settings → API). Leave empty to turn off.",
            value = tmdbDraft,
            onValueChange = { tmdbDraft = it.trim() },
            placeholder = "Paste your key",
            onDone = {
                editingTmdb = false
                if (tmdbDraft != vm.tmdbKey) vm.saveTmdbKey(tmdbDraft)
            },
        )
    }
    if (choosingPlayer) {
        PlayerPickerDialog(
            vm = vm,
            title = "Video player",
            subtitle = "Which app plays channels, movies and episodes. If something won't play in Streammania, try VLC.",
            includeBuiltIn = true,
            onDismiss = { choosingPlayer = false },
        ) { pkg ->
            vm.chooseExternalPlayer(pkg)
            choosingPlayer = false
        }
    }

    val managed = vm.profile
    if (managingChannels) {
        ChannelManager(vm) { managingChannels = false }
    }
    if (managingCategories && managed != null) {
        CategoryManager(vm, managed) { managingCategories = false }
    }

    if (confirmSignOut) {
        Dialog(onDismissRequest = { confirmSignOut = false }) {
            DialogBackdropBlur()
            Column(
                Modifier
                    .width(if (isTv) 520.dp else 440.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(dialogPanelColor())
                    .padding(if (isTv) 8.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SheetTitle(
                    "Sign out of Streammania?",
                    subtitle = "Your favourites stay on this $device. You'll need your login details to sign back in.",
                )
                Spacer(Modifier.height(24.dp))
                Column(Modifier.width(if (isTv) 340.dp else 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetOption("Cancel", focusKey = "signout:cancel", autoFocus = isTv) { confirmSignOut = false }
                    SheetOption("Sign out", focusKey = "signout:yes", primary = true) {
                        confirmSignOut = false
                        vm.signOut()
                    }
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
private fun SettingsHeader(text: String, first: Boolean = false) {
    Text(
        text.uppercase(),
        style = Txt.Caption.copy(letterSpacing = 1.2.sp),
        color = Ember.Faint,
        modifier = Modifier.padding(start = 6.dp, top = if (first) 0.dp else 16.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, hint: String?, focusKey: String, onClick: () -> Unit) {
    val isTv = LocalIsTv.current
    FocusBox(
        modifier = if (isTv) {
            Modifier.fillMaxWidth().height(if (hint != null) 70.dp else 58.dp)
        } else {
            Modifier.fillMaxWidth().heightIn(min = 58.dp)
        },
        focusKey = focusKey,
        focusedScale = 1.03f,
        color = Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        if (isTv) {
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
        } else {
            // Phones are narrow, so the current value goes under the title instead of beside it.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, style = Txt.Label, color = Ember.Text)
                if (value.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(value, style = Txt.Label.copy(fontSize = Txt.Caption.fontSize), color = Ember.Accent)
                }
                if (hint != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, style = Txt.Caption, color = Ember.Faint)
                }
            }
        }
    }
}
