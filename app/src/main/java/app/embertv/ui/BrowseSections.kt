package app.embertv.ui

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.border
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
private fun ChannelShelf(vm: AppViewModel, title: String, found: List<Channel>, keyPrefix: String) {
    // Each channel once in the results, not once per quality.
    val channels = remember(found, vm.qualityChoice, vm.channelsOnce) { channelsOnce(found, vm.qualityChoice, vm.channelsOnce) }
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
                                    catchup -> Icon(EmberIcons.Catchup, "On catch-up", tint = if (focused) Color.Black else Ember.Text, modifier = Modifier.size(14.dp))
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

@Composable
fun SearchSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val query = vm.searchQuery.trim()
    var editing by remember { mutableStateOf(false) }

    // Download the full lists as soon as Search opens (channels first: the smallest), so results are
    // quick by the time anything's typed. Each kind is ready (true) independently.
    var ready by remember(repo, vm.reloadToken) { mutableStateOf(emptySet<ContentType>()) }
    LaunchedEffect(repo, vm.reloadToken) {
        for (kind in listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)) {
            launch {
                try {
                    repo.prepareSearch(kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Searching will try again (and say if it can't).
                }
                ready = ready + kind
            }
        }
    }

    // Results per kind, shown as each arrives. null = still working on it.
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var movies by remember { mutableStateOf<List<Movie>?>(null) }
    var series by remember { mutableStateOf<List<Series>?>(null) }
    LaunchedEffect(query, vm.reloadToken) {
        channels = null
        movies = null
        series = null
        if (query.length < 2) return@LaunchedEffect
        delay(250)
        suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        coroutineScope {
            launch { channels = safely { repo.searchChannels(query) } }
            launch { movies = safely { repo.searchMovies(query) } }
            launch { series = safely { repo.searchSeries(query) } }
        }
    }

    // What's on TV: programme titles in the guide, found a channel at a time. Starts once films and
    // series are in, so it doesn't hold them up.
    var onTv by remember { mutableStateOf<List<app.embertv.data.ProgrammeHit>>(emptyList()) }
    var onTvProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var programmeDetails by remember { mutableStateOf<app.embertv.data.ProgrammeHit?>(null) }
    val now = LocalNow.current
    val listsReady = movies != null && series != null
    LaunchedEffect(query, listsReady) {
        onTv = emptyList()
        onTvProgress = null
        if (query.length < 3 || !listsReady) return@LaunchedEffect
        delay(400)
        val first = (vm.favourites.filter { it.type == ContentType.LIVE }.map { it.id } + vm.recentChannels.map { it.id }).distinct()
        app.embertv.data.ProgrammeSearch.search(repo, query, now, first) { checked, total, hits ->
            onTv = hits
            onTvProgress = checked to total
        }
        onTvProgress = null
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
        val ch = channels
        val mv = movies
        val sr = series
        val stillLooking = ch == null || mv == null || sr == null
        val anything = !ch.isNullOrEmpty() || !mv.isNullOrEmpty() || !sr.isNullOrEmpty() || onTv.isNotEmpty() || onTvProgress != null
        when {
            query.length < 2 -> EmptyView(
                if (ready.size < 3) "Getting your channels, films and series ready to search… You can start typing."
                else "Press OK on the search box and type at least two letters to search everything your provider offers, including what's on TV this week."
            )
            !anything && stillLooking -> LoadingView(
                text = when {
                    ContentType.MOVIE !in ready || ContentType.SERIES !in ready -> "Loading your films and series to search (only the first time)…"
                    else -> "Searching…"
                }
            )
            anything -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = LocalContentPadding.current.calculateBottomPadding() + 48.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                if (!ch.isNullOrEmpty()) item(key = "s-ch") { ChannelShelf(vm, "Channels", ch, "search") }
                if (onTv.isNotEmpty() || onTvProgress != null) item(key = "s-tv") {
                    ProgrammeShelf(vm, onTv, onTvProgress, now) { programmeDetails = it }
                }
                if (!mv.isNullOrEmpty()) item(key = "s-mv") { MovieShelf(vm, "Movies", mv, "search") }
                if (!sr.isNullOrEmpty()) item(key = "s-sr") { SeriesShelf(vm, "Series", sr, "search") }
                if (stillLooking) item(key = "s-more") {
                    Text(
                        "Still searching " + listOfNotNull(if (mv == null) "films" else null, if (sr == null) "series" else null, if (ch == null) "channels" else null).joinToString(" and ") + "…",
                        style = Txt.Caption,
                        color = Ember.Faint,
                        modifier = Modifier.padding(start = edgePadding()),
                    )
                }
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
    var pickingLeagues by remember { mutableStateOf(false) }
    var arrangingHome by remember { mutableStateOf(false) }
    var pickingBackdrop by remember { mutableStateOf(false) }
    var choosingAudio by remember { mutableStateOf(false) }
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
                .glassSurface(RoundedCornerShape(20.dp), Ember.Panel)
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
            val p = vm.profile

            // ---- Look & Home ----
            SettingsGroup("Look & Home", first = true) {
                if (!kids) {
                    val shownRows = HOME_ROWS.count { it.first !in vm.homeHidden }
                    SettingRow(
                        "Arrange Home",
                        if (shownRows == HOME_ROWS.size) "All rows" else "$shownRows of ${HOME_ROWS.size} rows",
                        "Which rows Home shows, and their order",
                        focusKey = "set:arrange",
                    ) { arrangingHome = true }
                }
                SettingRow("Background", Ember.backdrop.name, "The colour behind everything", focusKey = "set:backdrop") { pickingBackdrop = true }
                SettingRow("Text size", vm.textSizeLabel, "Bigger is easier to read from across the room", focusKey = "set:textsize") { vm.cycleTextSize() }
                if (!isTv) {
                    SettingRow(
                        "Screen direction",
                        vm.screenDirectionLabel,
                        "Sideways shows more of the TV guide and the rows on Home",
                        focusKey = "set:direction",
                    ) { vm.cycleScreenDirection() }
                }
                if (isTv) {
                    SettingRow(
                        "Remote hints",
                        if (HintsSetting.show) "On" else "Off",
                        "The little OK, arrow and Back guides on screen",
                        focusKey = "set:hints",
                    ) { vm.toggleHints() }
                    SettingRow(
                        "Screensaver",
                        if (vm.screensaverMinutes == 0) "Off" else "After ${vm.screensaverMinutes} min",
                        "Film and series artwork when the remote is left alone",
                        focusKey = "set:screensaver",
                    ) { vm.cycleScreensaver() }
                }
            }

            // ---- Sport ----
            SettingsGroup("Sport") {
                SettingRow(
                    "My teams",
                    if (vm.followedTeams.isEmpty()) "None" else vm.followedTeams.joinToString(", ") { it.shortName },
                    "Goal alerts, and their games on Home",
                    focusKey = "set:teams",
                ) { pickingTeams = true }
                if (!kids) {
                    SettingRow(
                        "Live sport on Home",
                        if (vm.sportOnHome) "On" else "Off",
                        "Football on now across your channels, with the score",
                        focusKey = "set:sport",
                    ) { vm.toggleSportOnHome() }
                    if (vm.sportOnHome) {
                        val names = app.embertv.data.LEAGUES.filter { it.code in vm.sportLeagues }.map { it.name }
                        SettingRow(
                            "My leagues",
                            when {
                                names.isEmpty() -> "None chosen"
                                names.size <= 2 -> names.joinToString(", ")
                                else -> "${names.take(2).joinToString(", ")} + ${names.size - 2}"
                            },
                            "Shown first" + if (vm.sportOthers) ", then other leagues" else " (others hidden)",
                            focusKey = "set:leagues",
                        ) { pickingLeagues = true }
                    }
                }
            }

            // ---- Channels & guide ----
            SettingsGroup("Channels & guide") {
                SettingRow(
                    "Show each channel once",
                    if (vm.channelsOnce) "On" else "Off",
                    if (vm.channelsOnce) "FHD, HD and SD copies show as one channel" else "Every copy your provider lists is shown",
                    focusKey = "set:channelsonce",
                ) { vm.toggleChannelsOnce() }
                if (!kids) {
                    val favChannels = vm.favourites.count { it.type == app.embertv.data.ContentType.LIVE }
                    val hiddenCount = vm.hiddenChannels.size
                    SettingRow(
                        "Channels",
                        listOfNotNull(
                            if (favChannels > 0) "$favChannels favourites" else null,
                            if (hiddenCount > 0) "$hiddenCount hidden" else null,
                        ).joinToString(" · ").ifEmpty { "All shown" },
                        "Order your favourites, hide ones you never watch",
                        focusKey = "set:channels",
                    ) { managingChannels = true }
                }
                if (!kids && p != null) {
                    val hidden = vm.categoryRules(p).keys.size
                    SettingRow(
                        "Categories",
                        if (hidden == 0) "All shown" else "$hidden hidden",
                        "Hide categories you never watch",
                        focusKey = "set:categories",
                    ) { managingCategories = true }
                }
                if (isTv) {
                    SettingRow(
                        "Guide and channel list",
                        if (vm.compactLists) "Compact" else "Comfortable",
                        if (vm.compactLists) "More channels on screen" else "Bigger rows, with times on each programme",
                        focusKey = "set:compact",
                    ) { vm.toggleCompactLists() }
                    SettingRow(
                        "Live preview",
                        if (vm.livePreview) "On" else "Off",
                        "A small muted picture of the highlighted channel",
                        focusKey = "set:preview",
                    ) { vm.toggleLivePreview() }
                }
                SettingRow(
                    "Pause & rewind live TV",
                    if (vm.timeshiftOn) "On" else "Off",
                    "Uses your provider's catch-up, on channels that have it",
                    focusKey = "set:timeshift",
                ) { vm.toggleTimeshift() }
                SettingRow(
                    if (isTv) "Channel up / down" else "Channel swipe",
                    if (isTv) (if (vm.invertZap) "Up goes to the previous channel" else "Up goes to the next channel")
                    else (if (vm.invertZap) "Swipe down for the next channel" else "Swipe up for the next channel"),
                    null,
                    focusKey = "set:zap",
                ) { vm.toggleInvertZap() }
            }

            // ---- Playing ----
            SettingsGroup("Playing") {
                if (!isTv) {
                    SettingRow(
                        "Play on TV",
                        vm.castTarget?.tv?.name?.let { "On $it" } ?: "Choose a TV",
                        "Send what you pick to Streammania on your TV",
                        focusKey = "set:cast",
                    ) { vm.showCastSheet = true }
                }
                SettingRow(
                    "Quality to play",
                    when (vm.qualityChoice) {
                        "best" -> "Best available"
                        "FHD" -> "Full HD"
                        "HD" -> "HD"
                        else -> "Ask each time"
                    },
                    "For films, shows and channels that come in more than one",
                    focusKey = "set:quality",
                ) { vm.cycleQualityChoice() }
                SettingRow(
                    "Play next episode automatically",
                    if (vm.autoplayNext) "On" else "Off",
                    if (vm.autoplayNext) "Up Next counts down, then plays the next one" else "Stops at the end of each episode",
                    focusKey = "set:autoplay",
                ) { vm.toggleAutoplayNext() }
                SettingRow(
                    "Picture zoom",
                    vm.pictureModeLabel,
                    if (isTv) "Fills the screen on channels with black bars" else "Fills the screen on channels with black bars. Pinch to change it too",
                    focusKey = "set:picture",
                ) { vm.cyclePicture(announce = false) }
                SettingRow(
                    "Audio & subtitles",
                    listOf(
                        if (vm.audioLanguage.isEmpty()) "Default audio" else "English audio",
                        if (vm.subtitleMode == "off") "subtitles off" else "English subtitles",
                    ).joinToString(" · "),
                    "What to pick when a stream has a choice",
                    focusKey = "set:audiosubs",
                ) { choosingAudio = true }
                if (isTv) {
                    SettingRow(
                        "Match frame rate",
                        if (vm.matchFrameRate) "On" else "Off",
                        "Films and series switch to 24Hz. The screen may blink",
                        focusKey = "set:afr",
                    ) { vm.toggleMatchFrameRate() }
                }
                SettingRow(
                    "When the app opens",
                    vm.startOnLabel(),
                    "Go straight to your last channel or the TV guide",
                    focusKey = "set:starton",
                ) { vm.cycleStartOn() }
            }

            // ---- If something won't play (grown-ups only) ----
            if (!kids) {
                SettingsGroup("If something won't play") {
                    SettingRow("Video player", vm.externalPlayerName(), "Play in VLC or another app instead", focusKey = "set:player") { choosingPlayer = true }
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
            }

            // ---- This TV / phone & app ----
            SettingsGroup(if (isTv) "This TV & app" else "This phone & app") {
                if (isTv && !kids) {
                    val phones = vm.pairedPhoneCount
                    SettingRow(
                        "Phones that can control this TV",
                        if (phones == 0) "None yet" else "$phones paired · select to unpair",
                        "On your phone, tap the TV button on Home",
                        focusKey = "set:phones",
                    ) { vm.forgetPairedPhones() }
                }
                if (!kids) SettingRow(
                    "Film details (TMDB)",
                    if (vm.tmdbKey.isBlank()) "Off" else "Connected",
                    "Cast photos, trailers and suggestions",
                    focusKey = "set:tmdb",
                ) { editingTmdb = true }
                SettingRow(
                    "Check for updates",
                    "Version ${app.embertv.BuildConfig.VERSION_NAME}",
                    "Updates install straight from the app",
                    focusKey = "set:update",
                ) { vm.checkForUpdates(manual = true) }
                SettingRow("Refresh everything now", "", "Films and series also update by themselves", focusKey = "set:refresh") {
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
    if (choosingAudio) {
        AudioSubtitlesSheet(vm) { choosingAudio = false }
    }
    if (pickingLeagues) {
        LeaguePicker(vm) { pickingLeagues = false }
    }
    if (pickingBackdrop) {
        BackdropPicker(vm) { pickingBackdrop = false }
    }
    if (arrangingHome) {
        ArrangeHomeDialog(vm) { arrangingHome = false }
    }
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
        style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        color = Ember.Dim,
        modifier = Modifier.padding(start = 10.dp, top = if (first) 0.dp else 18.dp, bottom = 2.dp),
    )
}

/** Apple-style grouped settings: a heading, then its rows together on one frosted panel. */
@Composable
private fun SettingsGroup(title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    SettingsHeader(title, first)
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(22.dp), Color(0x0FFFFFFF))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
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
        // Rows sit on their group's frosted panel, so each is only faintly tinted.
        color = Color(0x08FFFFFF),
        focusedColor = Color.White,
        glassEdge = false,
        shape = RoundedCornerShape(16.dp),
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
                    Text(value, style = Txt.Label.copy(fontSize = Txt.Caption.fontSize), color = Ember.Dim)
                }
                if (hint != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, style = Txt.Caption, color = Ember.Faint)
                }
            }
        }
    }
}

/** Settings → My leagues: tick the competitions Live Sport Now shows first. */
@Composable
private fun LeaguePicker(vm: AppViewModel, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 560.dp else 440.dp)
                .heightIn(max = if (isTv) 520.dp else 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("My leagues", eyebrow = "Live Sport Now", subtitle = "Ticked competitions come first on Home")
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.width(if (isTv) 400.dp else 400.dp).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                item(key = "english") {
                    SheetOption(
                        "Just English football",
                        focusKey = "leagues:english",
                        subtitle = "Premier League, the EFL and the cups",
                        autoFocus = isTv,
                    ) {
                        vm.chooseSportLeagues(app.embertv.data.DEFAULT_SPORT_LEAGUES)
                    }
                }
                items(app.embertv.data.LEAGUES, key = { it.code }) { l ->
                    val on = l.code in vm.sportLeagues
                    SheetOption(
                        l.name,
                        focusKey = "leagues:${l.code}",
                        trailing = { dark -> if (on) CheckMark(dark) },
                    ) { vm.setSportLeague(l.code, !on) }
                }
                item(key = "others") {
                    SheetOption(
                        "Other leagues too",
                        focusKey = "leagues:others",
                        subtitle = if (vm.sportOthers) "Shown after yours" else "Hidden: only your leagues",
                        trailing = { dark ->
                            Text(if (vm.sportOthers) "On" else "Off", style = Txt.Label, color = if (dark) Color.Black else Ember.Dim)
                        },
                    ) { vm.toggleSportOthers() }
                }
                item(key = "done") {
                    SheetOption("Done", focusKey = "leagues:done", primary = true, onClick = onDone)
                }
            }
        }
    }
}

/** Settings → Audio & subtitles: two choices in one Apple-style sheet. */
@Composable
private fun AudioSubtitlesSheet(vm: AppViewModel, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("Audio & subtitles", eyebrow = "Watching", subtitle = "Used when a channel or video offers a choice. Changes apply from the next one you play.")
            Spacer(Modifier.height(20.dp))
            Column(Modifier.width(if (isTv) 380.dp else 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetOption(
                    "Audio",
                    focusKey = "audiosubs:audio",
                    subtitle = if (vm.audioLanguage.isEmpty()) "The stream's own choice" else "English when there's a choice",
                    autoFocus = isTv,
                    trailing = { dark -> Text(if (vm.audioLanguage.isEmpty()) "Default" else "English", style = Txt.Label, color = if (dark) Color.Black else Ember.Dim) },
                ) { vm.cycleAudioLanguage() }
                SheetOption(
                    "Subtitles",
                    focusKey = "audiosubs:subs",
                    subtitle = if (vm.subtitleMode == "off") "Off unless you turn them on" else "On automatically when there are English ones",
                    trailing = { dark -> Text(if (vm.subtitleMode == "off") "Off" else "English", style = Txt.Label, color = if (dark) Color.Black else Ember.Dim) },
                ) { vm.cycleSubtitles() }
                SheetOption("Done", focusKey = "audiosubs:done", primary = true, onClick = onDone)
            }
        }
    }
}

/** Settings → Background: a swatch for each look. Choosing one changes the app straight away. */
@Composable
private fun BackdropPicker(vm: AppViewModel, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .heightIn(max = if (isTv) 520.dp else 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("Background", eyebrow = vm.profile?.name, subtitle = "Pick a colour. You'll see it change behind this")
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (b in BACKDROPS) {
                    val chosen = Ember.backdrop.id == b.id
                    SheetOption(
                        b.name,
                        focusKey = "backdrop:${b.id}",
                        autoFocus = chosen,
                        leading = { _ -> BackdropSwatch(b) },
                        trailing = { dark -> if (chosen) CheckMark(dark) },
                    ) { vm.chooseBackdrop(b) }
                }
            }
            Spacer(Modifier.height(16.dp))
            GlassButton("Done", EmberIcons.Check, onClick = onDone)
        }
    }
}

/** A little round preview of a background: its glow fading into its base colour. */
@Composable
private fun BackdropSwatch(b: AppBackground) {
    Box(
        Modifier
            .size(34.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(b.swatch, b.bg)))
            .border(0.8.dp, Color(0x40FFFFFF), androidx.compose.foundation.shape.CircleShape)
    )
}
