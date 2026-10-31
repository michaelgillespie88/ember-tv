package app.embertv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.embertv.data.Match
import app.embertv.data.MatchFinder
import app.embertv.data.MatchHit
import app.embertv.data.MatchState
import app.embertv.data.ScoresClient
import app.embertv.data.TEAM_LEAGUES
import app.embertv.data.TeamScore
import kotlinx.coroutines.delay

// ---------------- Choosing teams ----------------

/** Full-screen picker: a league, then tap clubs to follow or unfollow them. */
@Composable
fun TeamPicker(vm: AppViewModel, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    var league by remember { mutableStateOf(TEAM_LEAGUES.first()) }
    val load by rememberLoad(league.code) { ScoresClient.teams(league) }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1E1410), Ember.Bg), radius = 1600f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 28.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTv) {
                        IconCircleButton(EmberIcons.Back, "Done", glass = true, onClick = onDone)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("My teams", style = if (isTv) Txt.Title else Txt.Headline)
                        Text(
                            if (vm.followedTeams.isEmpty()) "Follow clubs for goal alerts and their next match on Home."
                            else "Following " + vm.followedTeams.joinToString(", ") { it.shortName },
                            style = Txt.Caption,
                            color = if (vm.followedTeams.isEmpty()) Ember.Dim else Ember.Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isTv) {
                        Spacer(Modifier.width(20.dp))
                        TvButton("Done", icon = "✓", onClick = onDone)
                    }
                }
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(TEAM_LEAGUES, key = { it.code }) { l ->
                        DayChip(l.name, l.code == league.code, "teams:league:${l.code}", autoFocus = isTv && l == TEAM_LEAGUES.first()) { league = l }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (val r = load) {
                    is LoadResult.Loading -> LoadingView(Modifier.weight(1f))
                    is LoadResult.Err -> ErrorView(r.message, Modifier.weight(1f)) { league = league.copy() }
                    is LoadResult.Ok -> if (r.value.isEmpty()) {
                        EmptyView("Couldn't load the clubs. Check your connection and try again.", Modifier.weight(1f))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (isTv) 4 else 2),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(r.value, key = { it.id }) { t ->
                                val on = vm.isFollowing(t)
                                FocusBox(
                                    modifier = Modifier.fillMaxWidth().height(60.dp),
                                    focusKey = "teams:${t.id}",
                                    shape = RoundedCornerShape(12.dp),
                                    focusedScale = 1.04f,
                                    color = if (on) Ember.Accent.copy(alpha = 0.18f) else Ember.Card,
                                    focusedColor = Color.White,
                                    onClick = { vm.toggleFollow(t) },
                                ) { focused ->
                                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                            NetImage(t.logo, Modifier.fillMaxSize(), ContentScale.Fit)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            t.name,
                                            style = Txt.Label,
                                            color = if (focused) Color.Black else Ember.Text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            if (on) EmberIcons.StarFilled else EmberIcons.Star,
                                            contentDescription = if (on) "Following" else "Follow",
                                            tint = if (on) Ember.Accent else if (focused) Color(0xFF55555C) else Ember.Faint,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Home card ----------------

/** "Arsenal 2–1 Chelsea · 67'" or "Sat 1 Nov · 15:00". OK opens "Where's it on?". */
@Composable
fun MatchCard(match: Match, width: Dp, focusKey: String, onClick: () -> Unit) {
    val live = match.state == MatchState.LIVE
    FocusBox(
        modifier = Modifier.width(width).height(124.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.05f,
        color = if (live) Color(0x2EFF5A50) else Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        val text = if (focused) Color.Black else Ember.Text
        val dim = if (focused) Color(0xFF55555C) else Ember.Faint
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(match.league.name, style = Txt.Caption, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when (match.state) {
                    MatchState.LIVE -> {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Ember.Live))
                        Spacer(Modifier.width(5.dp))
                        Text(match.status, style = Txt.Caption, color = if (focused) Color.Black else Ember.Live)
                    }
                    MatchState.FINISHED -> Text(match.status, style = Txt.Caption, color = dim)
                    MatchState.UPCOMING -> Text(
                        "${formatDay(match.kickoff / 1000)} · ${formatClock(match.kickoff / 1000)}",
                        style = Txt.Caption,
                        color = if (focused) Color.Black else Ember.Accent,
                    )
                }
            }
            CardTeam(match.home, match.state, text)
            CardTeam(match.away, match.state, text)
        }
    }
}

@Composable
private fun CardTeam(t: TeamScore, state: MatchState, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { NetImage(t.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
        Spacer(Modifier.width(10.dp))
        Text(t.shortName, style = Txt.Label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (state != MatchState.UPCOMING) Text(t.score, style = Txt.Headline.copy(fontSize = 18.sp), color = color)
    }
}

// ---------------- Goal alerts ----------------

/** A banner at the top for kick-offs, goals and full time, over whatever's on. Doesn't take focus. */
@Composable
fun BoxScope.TeamAlertBanner(vm: AppViewModel) {
    val alert = vm.teamAlert
    val shown = remember { arrayOfNulls<TeamAlert>(1) }
    if (alert != null) shown[0] = alert
    LaunchedEffect(alert) {
        if (alert != null) {
            delay(9_000)
            vm.dismissTeamAlert(alert)
        }
    }
    val isTv = LocalIsTv.current
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically { -it * 2 },
        exit = fadeOut(),
        modifier = Modifier
            .align(if (isTv) Alignment.TopEnd else Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = if (isTv) 28.dp else 10.dp, end = if (isTv) 40.dp else 0.dp),
    ) {
        val a = shown[0] ?: return@AnimatedVisibility
        val m = a.match
        val title = when (a.kind) {
            TeamAlert.Kind.GOAL -> if (a.good == false) "Goal against" else "GOAL!"
            TeamAlert.Kind.KICK_OFF -> "Kick-off"
            TeamAlert.Kind.FULL_TIME -> "Full time"
        }
        Row(
            Modifier
                .width(if (isTv) 420.dp else 340.dp)
                .glassSurface(RoundedCornerShape(18.dp), Color(0xCC14141A))
                .border(1.dp, if (a.kind == TeamAlert.Kind.GOAL && a.good != false) Ember.Accent else Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(EmberIcons.Football, contentDescription = null, tint = Ember.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Txt.Headline.copy(fontWeight = FontWeight.Bold), color = if (a.kind == TeamAlert.Kind.GOAL && a.good != false) Ember.Accent else Ember.Text)
                    Spacer(Modifier.width(8.dp))
                    if (m.state == MatchState.LIVE) Text(m.status, style = Txt.Caption, color = Ember.Live)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${m.home.shortName} ${m.home.score}–${m.away.score} ${m.away.shortName}",
                    style = Txt.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------- Where's it on? ----------------

/** Searches your channels for [match] and lists where it's showing. */
@Composable
fun MatchFinderDialog(vm: AppViewModel, match: Match) {
    val repo = vm.repo ?: return
    val isTv = LocalIsTv.current
    val now = LocalNow.current
    // Start with what Live Sport Now already found, then refresh.
    var hits by remember(match.id) {
        mutableStateOf(vm.liveSport.firstOrNull { it.match.id == match.id }?.hits.orEmpty())
    }
    var checked by remember(match.id) { mutableIntStateOf(0) }
    var total by remember(match.id) { mutableIntStateOf(0) }
    var done by remember(match.id) { mutableStateOf(false) }
    LaunchedEffect(match.id) {
        hits = MatchFinder.find(repo, match, now) { c, t, h ->
            checked = c
            total = t
            hits = h
        }
        done = true
    }
    val close = { vm.findingMatch = null }
    val status = when (match.state) {
        MatchState.LIVE -> "Live now · ${match.home.score}–${match.away.score} · ${match.status} · ${match.league.name}"
        MatchState.UPCOMING -> "${formatDay(match.kickoff / 1000, now)} ${formatClock(match.kickoff / 1000)} · ${match.league.name}"
        MatchState.FINISHED -> "Finished ${match.home.score}–${match.away.score} · ${match.league.name}"
    }

    Dialog(onDismissRequest = close) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 600.dp else 500.dp)
                .heightIn(max = if (isTv) 520.dp else 660.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp)) { NetImage(match.home.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
                Spacer(Modifier.width(22.dp))
                Box(Modifier.size(40.dp)) { NetImage(match.away.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
            }
            Spacer(Modifier.height(12.dp))
            SheetTitle("${match.home.shortName} v ${match.away.shortName}", eyebrow = "Where's it on?", subtitle = status)
            Spacer(Modifier.height(18.dp))
            if (!done) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Ember.Dim, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (total == 0) "Searching your channels…" else "Checking the guide on $checked of $total sports channels…",
                        style = Txt.Caption,
                        color = Ember.Dim,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (done && hits.isEmpty()) {
                Text(
                    "Couldn't find it in your channels or guide. Try your provider's sports or events categories " +
                        "nearer kick-off, as event channels are often added on the day.",
                    style = Txt.Body,
                    color = Ember.Dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                itemsIndexed(hits.take(40), key = { _, h -> h.channel.streamId }) { i, h ->
                    val p = h.programme
                    SheetOption(
                        title = h.channel.name,
                        subtitle = p?.let { "${formatClock(it.start)}  ${it.title}" },
                        focusKey = "find:${h.channel.streamId}",
                        autoFocus = isTv && i == 0,
                        primary = i == 0,
                        leading = { ChannelLogo(h.channel.icon, h.channel.name, Modifier.size(width = 54.dp, height = 36.dp)) },
                        trailing = if (h.score >= 8) { dark ->
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (dark) Color(0x1F000000) else Ember.Accent.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("Best match", style = Txt.Caption, color = if (dark) Color.Black else Ember.Accent)
                            }
                        } else null,
                    ) { vm.playFound(hits.take(40), i) }
                }
                item(key = "pin") {
                    Spacer(Modifier.height(6.dp))
                    val pinned = vm.pinnedMatch?.id == match.id
                    SheetOption(
                        if (pinned) "Remove score from the corner" else "Pin score to the corner",
                        focusKey = "find:pin",
                        leading = { dark -> Icon(EmberIcons.Football, contentDescription = null, tint = if (dark) Color.Black else Ember.Accent, modifier = Modifier.size(20.dp)) },
                    ) {
                        if (pinned) vm.unpinMatch() else vm.pinMatch(match)
                        close()
                    }
                }
                item(key = "close") {
                    SheetOption("Close", focusKey = "find:close", autoFocus = isTv && done && hits.isEmpty(), onClick = close)
                }
            }
        }
    }
}

// ---------------- Live Sport Now ----------------

/** A match on now, with where it's showing. OK plays it; holding OK pins its score to the corner. */
@Composable
fun SportCard(item: AppViewModel.LiveSport, width: Dp, focusKey: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val m = item.match
    val live = m.state == MatchState.LIVE
    FocusBox(
        modifier = Modifier.width(width).height(150.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.06f,
        color = if (live) Color(0x24FF5A50) else Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
        onLongClick = onLongClick,
    ) { focused ->
        val text = if (focused) Color.Black else Ember.Text
        val dim = if (focused) Color(0xFF55555C) else Ember.Faint
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.league.name, style = Txt.Caption, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (live) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Ember.Live))
                    Spacer(Modifier.width(5.dp))
                    Text(m.status, style = Txt.Caption, color = if (focused) Color.Black else Ember.Live)
                } else {
                    Text("Kick-off ${formatClock(m.kickoff / 1000)}", style = Txt.Caption, color = if (focused) Color.Black else Ember.Accent)
                }
            }
            CardTeam(m.home, m.state, text)
            CardTeam(m.away, m.state, text)
            val channel = item.hits.firstOrNull()?.channel
            val others = item.hits.size - 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (channel != null) EmberIcons.Play else EmberIcons.Search, contentDescription = null, tint = if (focused) Color.Black else Ember.Accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        channel == null -> "Find the channel"
                        others > 0 -> "On ${channel.name} + $others more"
                        else -> "On ${channel.name}"
                    },
                    style = Txt.Caption,
                    color = if (focused) Color.Black else Ember.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Watch them together": the live matches side by side in multi-view. */
@Composable
fun SportTogetherCard(count: Int, width: Dp, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.width(width).height(150.dp),
        focusKey = "home:sport:together",
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.06f,
        color = Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(EmberIcons.MultiView, contentDescription = null, tint = if (focused) Color.Black else Ember.Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text("Watch $count together", style = Txt.Headline, color = if (focused) Color.Black else Ember.Text, textAlign = TextAlign.Center)
            Text("Multi-view", style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Faint)
        }
    }
}

// ---------------- Score bug ----------------

/** A pinned match's score in the top corner, over whatever's on. It glows when the score changes. */
@Composable
fun BoxScope.ScoreBug(vm: AppViewModel) {
    val m = vm.pinnedMatch ?: return
    val isTv = LocalIsTv.current
    var flashing by remember { mutableStateOf(false) }
    val changedAt = vm.pinnedChangedAt
    LaunchedEffect(changedAt) {
        if (changedAt > 0 && System.currentTimeMillis() - changedAt < 10_000) {
            flashing = true
            delay(8_000)
            flashing = false
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            // Over a video it sits top left like a broadcaster's score; in the menus it moves to the
            // bottom right on TVs so it never covers the logo and tabs.
            .then(
                if (isTv && !vm.playerOpen) {
                    Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 26.dp)
                } else {
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = if (isTv) 40.dp else 12.dp, top = if (isTv) 26.dp else 8.dp)
                }
            )
            .glassSurface(shape, if (flashing) Ember.Accent.copy(alpha = 0.55f) else Color(0xB3101014))
            .border(1.dp, if (flashing) Ember.Accent else Color(0x33FFFFFF), shape)
            .then(if (!isTv) Modifier.clickable { vm.unpinMatch() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp)) { NetImage(m.home.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
        Spacer(Modifier.width(7.dp))
        Text(m.home.shortName.take(3).uppercase(), style = Txt.Label.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(10.dp))
        Text(
            if (m.state == MatchState.UPCOMING) "v" else "${m.home.score}–${m.away.score}",
            style = Txt.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.width(10.dp))
        Text(m.away.shortName.take(3).uppercase(), style = Txt.Label.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(7.dp))
        Box(Modifier.size(20.dp)) { NetImage(m.away.logo, Modifier.fillMaxSize(), ContentScale.Fit) }
        Spacer(Modifier.width(10.dp))
        Text(
            when (m.state) {
                MatchState.LIVE -> m.status
                MatchState.FINISHED -> "FT"
                MatchState.UPCOMING -> formatClock(m.kickoff / 1000)
            },
            style = Txt.Caption,
            color = if (m.state == MatchState.LIVE) Ember.Live else Ember.Dim,
        )
    }
}
