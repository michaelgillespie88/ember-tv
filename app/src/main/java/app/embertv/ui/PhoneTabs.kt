package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import app.embertv.data.RECENT
import app.embertv.data.Programme
import app.embertv.data.Repository
import app.embertv.data.catchupAvailable
import app.embertv.data.favouriteKey
import app.embertv.data.toChannel
import app.embertv.data.toMovie
import app.embertv.data.toSeries

private fun withVirtualCategories(cats: List<Category>, allLabel: String, recent: Boolean): List<Category> =
    listOf(Category(FAVOURITES, "Favourites")) +
        (if (recent) listOf(Category(RECENT, "Recently added")) else emptyList()) +
        listOf(Category(ALL, allLabel)) + cats

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
    // Films and series categories follow background updates; channels don't need to.
    val quiet = if (allLabel == "All channels") 0 else vm.vodVersion
    val cats by rememberQuietLoad(quiet, repo, vm.reloadToken, allLabel) { load(repo) }
    when (val c = cats) {
        is LoadResult.Loading -> SkeletonPhonePage(posters = allLabel == "All movies" || allLabel == "All series")
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) { withVirtualCategories(c.value, allLabel, recent = allLabel != "All channels") }
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
        favSnapshot?.let { favs -> repo.resolveAll(favs.map { it.toChannel() }) } ?: repo.channels(categoryId)
    }
    return load
}

// ---------------- Live ----------------

@Composable
fun PhoneLiveTab(vm: AppViewModel) {
    CategorySection(vm, vm.live, "All channels", "Loading channels…", { it.liveCategories() }) { repo, categoryId ->
        when (val r = rememberChannels(vm, repo, categoryId)) {
            is LoadResult.Loading -> SkeletonRows()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                val channels = remember(r.value, vm.qualityChoice, vm.channelsOnce) { channelsOnce(r.value, vm.qualityChoice, vm.channelsOnce) }
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

private val P_CHANNEL = 74.dp
private val P_ROW = 54.dp
private val P_PER_MINUTE = 3.2.dp
private const val P_DAY_MINUTES = 24 * 60

/**
 * The phone guide, matching the TV's: the chosen programme's details across the top, then a real
 * timeline grid. Every row scrolls together through the whole day, with the channel column pinned.
 * Tap a programme to see it at the top, tap it again to watch (or catch up, or set a reminder).
 */
@Composable
fun PhoneGuideTab(vm: AppViewModel) {
    var details by remember { mutableStateOf<Pair<GuideFocus, List<Channel>>?>(null) }
    // Days from today: negative for catch-up, positive for what's coming.
    var day by remember { mutableIntStateOf(0) }
    var pickingDay by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GuideFocus?>(null) }
    val scroll = rememberScrollState(
        with(LocalDensity.current) { (P_PER_MINUTE * (((LocalNow.current - startOfDay(LocalNow.current)) / 60f) - 30f).coerceAtLeast(0f)).roundToPx() }
    )
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val now = LocalNow.current

    // Where "now" (or this time of day, on other days) sits along the timeline, less half an hour.
    fun targetPx(): Int = with(density) { (P_PER_MINUTE * (((now - startOfDay(now)) / 60f) - 30f).coerceAtLeast(0f)).roundToPx() }
    LaunchedEffect(day) { scroll.scrollTo(targetPx()) }
    val awayFromNow by remember(now, day) {
        derivedStateOf { day != 0 || kotlin.math.abs(scroll.value - targetPx()) > with(density) { 160.dp.roundToPx() } }
    }

    CategorySection(vm, vm.guide, "All channels", "Loading guide…", { it.liveCategories() }) { repo, categoryId ->
        when (val r = rememberChannels(vm, repo, categoryId)) {
            is LoadResult.Loading -> SkeletonRows()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                val channels = remember(r.value, vm.qualityChoice, vm.channelsOnce) { channelsOnce(r.value, vm.qualityChoice, vm.channelsOnce) }
                if (channels.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any channel to add it to your favourites."
                        else "No channels in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    val dayStart = startOfDay(now) + day * 86_400L
                    fun watch(ch: Channel) = vm.push(Screen.PlayLive(channels, channels.indexOf(ch).coerceAtLeast(0)))
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding() + 8.dp)) {
                            PhoneGuideInfo(
                                focus = selected,
                                now = now,
                                onWatch = { selected?.let { watch(it.channel) } },
                                onMore = { selected?.let { f -> details = f to channels } },
                            )
                            Spacer(Modifier.height(8.dp))
                            PhoneTimeHeader(dayStart, now, day, scroll, onDay = { pickingDay = true })
                            LazyColumn(
                                state = vm.guide.list(categoryId),
                                contentPadding = PaddingValues(top = 2.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(channels, key = { it.streamId }) { ch ->
                                    PhoneGuideRow(
                                        repo = repo,
                                        channel = ch,
                                        day = day,
                                        dayStart = dayStart,
                                        now = now,
                                        scroll = scroll,
                                        selected = selected,
                                        onWatch = { watch(ch) },
                                        onTap = { p ->
                                            val f = GuideFocus(ch, p)
                                            if (selected == f) {
                                                // Second tap: on now plays it; anything else opens catch-up / reminder.
                                                if (p.isLive(now)) watch(ch) else details = f to channels
                                            } else {
                                                selected = f
                                            }
                                        },
                                        onHold = { p -> details = GuideFocus(ch, p) to channels },
                                    )
                                }
                            }
                        }
                        // Back to now, once you've scrolled away from it.
                        if (awayFromNow) {
                            PillButton(
                                "Now",
                                primary = true,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = pad.calculateBottomPadding() + 12.dp),
                            ) {
                                day = 0
                                scope.launch { scroll.animateScrollTo(targetPx()) }
                            }
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
    if (pickingDay) {
        DayPicker(now, startOfDay(now) + day * 86_400L, onDismiss = { pickingDay = false }) { d2 ->
            pickingDay = false
            day = d2
        }
    }
}

/** The chosen programme, Apple TV style, with Watch and More. */
@Composable
private fun PhoneGuideInfo(focus: GuideFocus?, now: Long, onWatch: () -> Unit, onMore: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .glassSurface(RoundedCornerShape(20.dp), Color(0x14FFFFFF))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (focus?.programme == null) {
            Text("TV GUIDE", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp), color = Ember.Dim)
            Spacer(Modifier.height(4.dp))
            Text("What's on", style = Txt.Headline.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a programme to see what it's about, and tap it again to watch. Swipe sideways to move through the day.",
                style = Txt.Caption.copy(letterSpacing = 0.sp, fontSize = 13.sp),
                color = Ember.Dim,
                maxLines = 3,
            )
            return
        }
        val p = focus.programme
        val ch = focus.channel
        val live = p.isLive(now)
        Text(
            (if (ch.num > 0) "${ch.num}  ·  " else "") + ch.name.uppercase(),
            style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
            color = Ember.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(p.title, style = Txt.Headline.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val timing = if (live) {
                "${formatClock(p.start)} – ${formatClock(p.end)}  ·  ${(p.end - now) / 60} min left"
            } else {
                "${formatDay(p.start, now)}  ${formatClock(p.start)} – ${formatClock(p.end)}"
            }
            Text(timing, style = Txt.Caption.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = Ember.Dim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (live) {
                Spacer(Modifier.width(10.dp))
                ProgressBar(p.progress(now), Modifier.width(70.dp))
            }
        }
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(p.description, style = Txt.Caption.copy(fontSize = 13.sp, letterSpacing = 0.sp, lineHeight = 17.sp), color = Ember.Faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) {
                SmallPill("Watch", primary = true, onClick = onWatch)
            }
            SmallPill(
                when {
                    live -> "More"
                    p.end <= now -> "Catch up"
                    else -> "Remind me"
                },
                primary = !live,
                onClick = onMore,
            )
        }
    }
}

@Composable
private fun SmallPill(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .height(32.dp)
            .then(if (primary) Modifier.clip(shape).background(Color.White) else Modifier.glassSurface(shape))
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Txt.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (primary) Color.Black else Ember.Text)
    }
}

/** The day button and the times along the top, scrolling with the grid. */
@Composable
private fun PhoneTimeHeader(dayStart: Long, now: Long, day: Int, scroll: ScrollState, onDay: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(P_CHANNEL).padding(start = 8.dp)) {
            Row(
                Modifier
                    .height(26.dp)
                    .glassSurface(RoundedCornerShape(13.dp))
                    .pressable(onClick = onDay)
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (day) {
                        0 -> "Today"
                        -1 -> "Yday"
                        1 -> "Tmrw"
                        else -> formatDay(dayStart + 43_200L, now).take(6)
                    },
                    style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    color = Ember.Text,
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                androidx.compose.material3.Icon(EmberIcons.ChevronDown, contentDescription = null, tint = Ember.Dim, modifier = Modifier.size(11.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(P_PER_MINUTE * P_DAY_MINUTES.toFloat()).fillMaxHeight()) {
                val nowX = P_PER_MINUTE * ((now - dayStart) / 60f)
                val showNow = day == 0
                for (slot in 0 until P_DAY_MINUTES / 30) {
                    val x = P_PER_MINUTE * (slot * 30).toFloat()
                    if (showNow && x > nowX - 46.dp && x < nowX + 24.dp) continue
                    Row(Modifier.offset(x = x).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(1.dp).height(10.dp).background(Color(0x33FFFFFF)))
                        Spacer(Modifier.width(5.dp))
                        Text(formatClock(dayStart + slot * 1800L), style = Txt.Caption.copy(letterSpacing = 0.sp), color = Ember.Dim)
                    }
                }
                if (showNow) {
                    Box(
                        Modifier
                            .offset(x = nowX - 20.dp, y = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ember.Live)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(formatClock(now), style = Txt.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp), color = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneGuideRow(
    repo: Repository,
    channel: Channel,
    day: Int,
    dayStart: Long,
    now: Long,
    scroll: ScrollState,
    selected: GuideFocus?,
    onWatch: () -> Unit,
    onTap: (Programme) -> Unit,
    onHold: (Programme) -> Unit,
) {
    // The whole day's guide; today shows now and next straight away while it loads.
    val full = rememberFullProgrammes(repo, channel.streamId)
    val quick = if (day == 0) rememberProgrammes(repo, channel.streamId) else null
    val programmes = full ?: quick
    val dayEnd = dayStart + 86_400L
    val list = programmes.orEmpty().filter { it.end > dayStart && it.start < dayEnd }
    Row(Modifier.fillMaxWidth().height(P_ROW)) {
        // Pinned channel: tap to watch.
        Column(
            Modifier
                .padding(start = 8.dp, end = 4.dp)
                .width(P_CHANNEL - 12.dp)
                .fillMaxHeight()
                .glassSurface(RoundedCornerShape(10.dp), Color(0x0FFFFFFF))
                .pressable(onClick = onWatch),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChannelLogo(channel.icon, channel.name, Modifier.size(width = 44.dp, height = 26.dp))
            if (channel.num > 0) {
                Text(channel.num.toString(), style = Txt.Caption.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = Ember.Faint, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(P_PER_MINUTE * P_DAY_MINUTES.toFloat()).fillMaxHeight()) {
                if (list.isEmpty()) {
                    val x = if (day == 0) P_PER_MINUTE * ((now - dayStart) / 60f - 30f).coerceAtLeast(0f) else 0.dp
                    Box(
                        Modifier
                            .offset(x = x)
                            .width(280.dp)
                            .fillMaxHeight()
                            .glassSurface(RoundedCornerShape(10.dp), Color(0x0AFFFFFF))
                            .pressable(onClick = onWatch)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(if (programmes == null) "Loading…" else "No guide information", style = Txt.Caption.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = Ember.Faint)
                    }
                }
                for (p in list) {
                    key(p.start) {
                        val start = maxOf(p.start, dayStart)
                        val end = minOf(p.end, dayEnd)
                        val live = p.isLive(now)
                        val past = p.end <= now
                        val catchup = past && catchupAvailable(channel, p, now)
                        val isSelected = selected?.channel?.streamId == channel.streamId && selected.programme?.start == p.start
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            Modifier
                                .offset(x = P_PER_MINUTE * ((start - dayStart) / 60f))
                                .width((P_PER_MINUTE * ((end - start) / 60f) - 3.dp).coerceAtLeast(4.dp))
                                .fillMaxHeight()
                                .then(
                                    if (isSelected) Modifier.clip(shape).background(Color.White)
                                    else Modifier.glassSurface(
                                        shape,
                                        when {
                                            live -> Color(0x2EFFFFFF)
                                            past -> Color(0x0AFFFFFF)
                                            else -> Color(0x17FFFFFF)
                                        },
                                    )
                                )
                                .combinedClickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                    onLongClick = { onHold(p) },
                                    onClick = { onTap(p) },
                                )
                                .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (catchup) {
                                        androidx.compose.material3.Icon(
                                            EmberIcons.Catchup,
                                            "On catch-up",
                                            tint = if (isSelected) Color.Black else Ember.Text,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        p.title,
                                        style = Txt.Label.copy(fontSize = 13.sp, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium),
                                        color = when {
                                            isSelected -> Color.Black
                                            past && !catchup -> Ember.Faint
                                            live -> Ember.Text
                                            else -> Ember.Dim
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "${formatClock(p.start)} – ${formatClock(p.end)}",
                                    style = Txt.Caption.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                                    color = if (isSelected) Color(0xFF555559) else Ember.Faint,
                                    maxLines = 1,
                                )
                            }
                            if (live && !isSelected) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(bottom = 1.dp)
                                        .fillMaxWidth(p.progress(now))
                                        .height(2.dp)
                                        .background(Ember.Text.copy(alpha = 0.85f))
                                )
                            }
                        }
                    }
                }
                if (day == 0) {
                    Box(Modifier.offset(x = P_PER_MINUTE * ((now - dayStart) / 60f)).width(1.5.dp).fillMaxHeight().background(Ember.Live.copy(alpha = 0.7f)))
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
        val load by rememberQuietLoad(vm.vodVersion, repo, categoryId, vm.reloadToken, favSnapshot) {
            favSnapshot?.map { it.toMovie() } ?: repo.movies(categoryId)
        }
        when (val r = load) {
            is LoadResult.Loading -> SkeletonPosters()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                if (r.value.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any movie to add it to your favourites."
                        else "No movies in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    val sortedMovies = remember(r.value, vm.movieSort) { sortMovies(r.value, vm.movieSort) }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = vm.movies.grid(categoryId),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 10.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "sort", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            SortChip(sortName(vm.movieSort), "movie:$categoryId:sort") { vm.cycleMovieSort() }
                        }
                        items(sortedMovies, key = { it.streamId }) { m ->
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
        val load by rememberQuietLoad(vm.vodVersion, repo, categoryId, vm.reloadToken, favSnapshot) {
            favSnapshot?.map { it.toSeries() } ?: repo.series(categoryId)
        }
        when (val r = load) {
            is LoadResult.Loading -> SkeletonPosters()
            is LoadResult.Err -> ErrorView(r.message) { vm.reload() }
            is LoadResult.Ok -> {
                if (r.value.isEmpty()) {
                    EmptyView(
                        if (categoryId == FAVOURITES) "${holdPrompt()} any series to add it to your favourites."
                        else "No series in this category."
                    )
                } else {
                    val pad = LocalContentPadding.current
                    val sortedSeries = remember(r.value, vm.seriesSort) { sortSeries(r.value, vm.seriesSort) }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = vm.series.grid(categoryId),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 10.dp, bottom = pad.calculateBottomPadding() + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "sort", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            SortChip(sortName(vm.seriesSort), "series:$categoryId:sort") { vm.cycleSeriesSort() }
                        }
                        items(sortedSeries, key = { it.seriesId }) { s ->
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

