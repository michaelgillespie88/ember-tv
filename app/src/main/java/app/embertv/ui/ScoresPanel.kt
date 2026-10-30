package app.embertv.ui

import androidx.compose.foundation.background
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
private val ALL_SET = LEAGUES.take(8) + LEAGUES.filter { it.code in setOf("esp.1", "ita.1", "ger.1", "fra.1") }

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
    var league by remember { mutableStateOf(ALL_LEAGUES) }
    var matches by remember { mutableStateOf<List<Match>?>(null) }
    var updated by remember { mutableLongStateOf(0L) }

    LaunchedEffect(league) {
        matches = null
        while (true) {
            val list = if (league.code == "all") ScoresClient.today(ALL_SET) else ScoresClient.today(listOf(league))
            matches = list
            updated = System.currentTimeMillis()
            delay(30_000)
        }
    }

    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .width(if (isTv) 440.dp else 380.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xCC0B0B0E), Color(0xF20B0B0E))))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(top = if (isTv) 28.dp else 14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(EmberIcons.Football, contentDescription = null, tint = Ember.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Live Scores", style = Txt.Headline)
                Text(
                    if (updated == 0L) "Loading…" else "Updated ${formatClock(updated / 1000)}",
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
            }
            if (!isTv) IconCircleButton(EmberIcons.Close, "Close scores", size = 40.dp, onClick = onClose)
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
                    color = if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF),
                    focusedColor = Color.White,
                    autoFocus = isTv && selected,
                    onClick = { league = l },
                ) { focused ->
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(
                            l.name,
                            style = Txt.Label.copy(fontSize = 13.sp),
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
            list == null -> LoadingView(Modifier.weight(1f))
            list.isEmpty() -> EmptyView("No ${if (league.code == "all") "" else league.name + " "}matches today.", Modifier.weight(1f))
            else -> {
                val pinned = list.filter { involves(it, programmeTitle) }
                // Group by competition (ones with live games first), live matches first within each.
                val liveLeagues = list.filter { it.state == MatchState.LIVE }.map { it.league.code }.toSet()
                val rest = (list - pinned.toSet()).sortedWith(
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
                        items(pinned, key = { "p" + it.id }) { MatchRow(it, highlight = true) }
                    }
                    var lastLeague = ""
                    for (m in rest) {
                        if (league.code == "all" && m.league.code != lastLeague) {
                            lastLeague = m.league.code
                            item(key = "h-${m.league.code}-${m.id}") { ScoresHeader(m.league.name) }
                        }
                        item(key = m.id + m.league.code) { MatchRow(m, highlight = false) }
                    }
                }
            }
        }
        if (isTv) {
            Text(
                "Press Back to close",
                style = Txt.Caption,
                color = Ember.Faint,
                modifier = Modifier.padding(start = 20.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ScoresHeader(text: String) {
    Text(text.uppercase(), style = Txt.Caption, color = Ember.Faint, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp))
}

@Composable
private fun MatchRow(m: Match, highlight: Boolean) {
    val live = m.state == MatchState.LIVE
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        focusKey = "scores:match:${m.id}",
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        color = if (highlight) Ember.Accent.copy(alpha = 0.16f) else Color(0x10FFFFFF),
        focusedColor = Color(0x33FFFFFF),
        onClick = {},
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TeamLine(m.home.shortName, m.home.logo, m.home.score, m.state, winner = (m.home.score.toIntOrNull() ?: 0) > (m.away.score.toIntOrNull() ?: 0))
                TeamLine(m.away.shortName, m.away.logo, m.away.score, m.state, winner = (m.away.score.toIntOrNull() ?: 0) > (m.home.score.toIntOrNull() ?: 0))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) {
                if (live) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Ember.Live))
                        Spacer(Modifier.height(3.dp))
                        Text(m.status, style = Txt.Caption, color = Ember.Live, maxLines = 1, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(m.status, style = Txt.Caption, color = Ember.Faint, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun TeamLine(name: String, logo: String, score: String, state: MatchState, winner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            NetImage(logo, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = Txt.Label.copy(fontSize = 14.sp, fontWeight = if (winner && state == MatchState.FINISHED) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state != MatchState.UPCOMING) {
            Text(score, style = Txt.Headline.copy(fontSize = 16.sp), color = Ember.Text)
        }
    }
}
