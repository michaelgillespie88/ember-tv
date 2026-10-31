package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.LEAGUES
import app.embertv.data.League
import app.embertv.data.Match
import app.embertv.data.MatchState
import app.embertv.data.ScoresClient
import kotlinx.coroutines.delay

/** "All" plus each competition. All shows the UK and big European leagues together. */
private val ALL_LEAGUES = League("all", "All")
private val ALL_SET = app.embertv.data.POPULAR_LEAGUES

private fun involves(m: Match, title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    val t = title.lowercase()
    return listOf(m.home.shortName, m.away.shortName, m.home.name, m.away.name)
        .any { it.length >= 4 && t.contains(it.lowercase()) }
}

/**
 * Live scores over the right-hand side of the player. Refreshes every 30 seconds while open.
 * Matches mentioned in [programmeTitle] (what's on the current channel) are pinned to the top.
 */
@Composable
fun BoxScope.ScoresPanel(programmeTitle: String?, onClose: () -> Unit) {
    val isTv = LocalIsTv.current
    val vm = LocalAppVm.current
    val followed = vm.followedTeams.map { it.id }.toSet()
    var league by remember { mutableStateOf(ALL_LEAGUES) }
    var matches by remember { mutableStateOf<List<Match>?>(null) }
    var updated by remember { mutableLongStateOf(0L) }

    LaunchedEffect(league) {
        matches = null
        while (true) {
            val list = try {
                if (league.code == "all") ScoresClient.today(ALL_SET) else ScoresClient.today(listOf(league))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                matches ?: emptyList()
            }
            matches = list
            updated = System.currentTimeMillis()
            delay(30_000)
        }
    }

    // The same floating frosted panel as subtitles and options, Apple TV style.
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .fillMaxHeight()
            .padding(if (isTv) 28.dp else 12.dp)
            .width(if (isTv) 420.dp else 360.dp)
            .glassSurface(RoundedCornerShape(26.dp), Color(0xE0141418))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(top = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).glassSurface(CircleShape, Color(0x1AFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(EmberIcons.Football, contentDescription = null, tint = Ember.Text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Live Scores", style = Txt.Headline.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (updated == 0L) "Getting today's matches…" else "Updated ${formatClock(updated / 1000)}",
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
            }
            if (!isTv) IconCircleButton(EmberIcons.Close, "Close scores", size = 38.dp, background = Color(0x1AFFFFFF), glass = true, onClick = onClose)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(listOf(ALL_LEAGUES) + LEAGUES, key = { it.code }) { l ->
                val selected = l.code == league.code
                FocusBox(
                    modifier = Modifier.height(34.dp),
                    focusKey = "scores:league:${l.code}",
                    shape = RoundedCornerShape(17.dp),
                    focusedScale = 1.06f,
                    color = if (selected) Color(0x38FFFFFF) else Color(0x14FFFFFF),
                    focusedColor = Color.White,
                    autoFocus = isTv && selected,
                    onClick = { league = l },
                ) { focused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(
                            l.name,
                            style = Txt.Label.copy(fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = when {
                                focused -> Color.Black
                                selected -> Ember.Text
                                else -> Ember.Dim
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        val list = matches
        when {
            list == null -> ScoresSkeleton(Modifier.weight(1f))
            list.isEmpty() -> EmptyView("No ${if (league.code == "all") "" else league.name + " "}matches today.", Modifier.weight(1f))
            else -> {
                val pinned = list.filter { involves(it, programmeTitle) }
                val mine = list.filter { it !in pinned && (it.home.id in followed || it.away.id in followed) }
                // Group by competition (ones with live games first), live matches first within each.
                val liveLeagues = list.filter { it.state == MatchState.LIVE }.map { it.league.code }.toSet()
                val rest = (list - pinned.toSet() - mine.toSet()).sortedWith(
                    compareBy<Match>(
                        { if (it.league.code in liveLeagues) 0 else 1 },
                        { LEAGUES.indexOfFirst { l -> l.code == it.league.code } },
                        { when (it.state) { MatchState.LIVE -> 0; MatchState.UPCOMING -> 1; MatchState.FINISHED -> 2 } },
                        { it.kickoff },
                    )
                )
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-h") { ScoresHeader("On this channel") }
                        items(pinned, key = { "p" + it.id }) { MatchRow(it, highlight = true) { vm.findingMatch = it } }
                    }
                    if (mine.isNotEmpty()) {
                        item(key = "mine-h") { ScoresHeader("Your teams") }
                        items(mine, key = { "m" + it.id }) { MatchRow(it, highlight = true) { vm.findingMatch = it } }
                    }
                    var lastLeague = ""
                    for (m in rest) {
                        if (league.code == "all" && m.league.code != lastLeague) {
                            lastLeague = m.league.code
                            item(key = "h-${m.league.code}-${m.id}") { ScoresHeader(m.league.name) }
                        }
                        item(key = m.id + m.league.code) { MatchRow(m, highlight = false) { vm.findingMatch = m } }
                    }
                }
            }
        }
        if (isTv) {
            RemoteHints(
                listOf(Hint("OK", "Find the channel"), Hint("BACK", "Close")),
                Modifier.padding(start = 20.dp, top = 6.dp, bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun ScoresHeader(text: String) {
    Text(
        text.uppercase(),
        style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
        color = Ember.Dim,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}

/** Faint match-shaped placeholders while today's scores load. */
@Composable
private fun ScoresSkeleton(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(6) {
            Box(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x12FFFFFF)))
        }
    }
}

@Composable
private fun MatchRow(m: Match, highlight: Boolean, onClick: () -> Unit) {
    val live = m.state == MatchState.LIVE
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(66.dp),
        focusKey = "scores:match:${m.id}",
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.03f,
        color = if (highlight) Color(0x26FFFFFF) else Color(0x12FFFFFF),
        // Apple style: the selected match turns white, like every other list.
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TeamLine(m.home.shortName, m.home.logo, m.home.score, m.state, winner = (m.home.score.toIntOrNull() ?: 0) > (m.away.score.toIntOrNull() ?: 0), dark = focused)
                TeamLine(m.away.shortName, m.away.logo, m.away.score, m.state, winner = (m.away.score.toIntOrNull() ?: 0) > (m.home.score.toIntOrNull() ?: 0), dark = focused)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
                if (live) {
                    // A small red LIVE tag with the minute underneath.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Ember.Live)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("LIVE", style = Txt.Caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Color.White)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(m.status, style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Color.Black else Ember.Text, maxLines = 1, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(m.status, style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Faint, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun TeamLine(name: String, logo: String, score: String, state: MatchState, winner: Boolean, dark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            NetImage(logo, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = Txt.Label.copy(fontSize = 14.sp, fontWeight = if (winner && state == MatchState.FINISHED) FontWeight.SemiBold else FontWeight.Medium),
            color = if (dark) Color.Black else Ember.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state != MatchState.UPCOMING) {
            Text(score, style = Txt.Headline.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = if (dark) Color.Black else Ember.Text)
        }
    }
}
