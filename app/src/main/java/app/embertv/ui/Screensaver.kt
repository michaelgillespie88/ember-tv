package app.embertv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.Repository
import app.embertv.data.toMovie
import app.embertv.data.toSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One picture in the slideshow. [backdrop] is wide artwork; [poster] is used when there isn't any. */
private data class Artwork(val id: Int, val title: String, val kind: String, val backdrop: String, val poster: String, val line: String)

/** What a slide shows: wide artwork and a details line ("2026 · Horror · ★ 6.3"). */
private class SlideInfo(val backdrop: String, val line: String)

/** A film or show for the slideshow; [target] (a Movie or Series) is what OK opens. */
private class Pick(val title: String, val kind: String, val poster: String, val target: Any, val details: suspend (Repository) -> SlideInfo)

private fun detailsLine(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

private fun moviePick(m: app.embertv.data.Movie) = Pick(app.embertv.data.Versions.cleanTitle(m.name), "Movie", m.poster, m) { r ->
    val info = r.movieInfo(m.streamId)
    SlideInfo(info.backdrop, detailsLine(info.year, info.genre.substringBefore(",").substringBefore("/").trim(), ratingLabel(info.rating.ifBlank { m.rating })))
}

private fun seriesPick(s: app.embertv.data.Series) = Pick(app.embertv.data.Versions.cleanTitle(s.name), "Series", s.cover, s) { r ->
    val info = r.seriesInfo(s.seriesId)
    SlideInfo(info.backdrop, detailsLine(info.year.ifBlank { s.year }, info.genre.ifBlank { s.genre }.substringBefore(",").substringBefore("/").trim(), ratingLabel(info.rating.ifBlank { s.rating })))
}

private const val SLIDE_MS = 12_000L

/** How long the screensaver keeps the TV awake before letting it go to sleep as normal. */
private const val KEEP_AWAKE_MS = 30 * 60_000L

/**
 * A slow slideshow of artwork from your provider's movies and series, with the time.
 * Shown on TVs after a few minutes without button presses; any button returns to the app.
 */
@Composable
fun Screensaver(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(view) {
        delay(KEEP_AWAKE_MS)
        view.keepScreenOn = false
    }

    val picks by produceState<List<Pick>>(emptyList(), repo) {
        val feed = try {
            // Reuse Home's rows when they're already loaded.
            (vm.homeFeed as? LoadResult.Ok)?.value ?: loadHomeFeed(repo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (feed != null) {
            // Picked for you, like Home's banner: things like what you've watched and My List first,
            // then well-rated new arrivals, then the rest. Nothing you've already watched.
            fun fresh(m: app.embertv.data.Movie) = m.poster.isNotBlank() && !vm.movieWatched(m)
            val because = vm.homeExtras?.because.orEmpty().mapNotNull {
                when (it) {
                    is app.embertv.data.Movie -> if (fresh(it)) moviePick(it) else null
                    is app.embertv.data.Series -> if (it.cover.isNotBlank()) seriesPick(it) else null
                    else -> null
                }
            }
            val myList = vm.favourites.filter { it.type != app.embertv.data.ContentType.LIVE && it.image.isNotBlank() }.map {
                if (it.type == app.embertv.data.ContentType.MOVIE) moviePick(it.toMovie()) else seriesPick(it.toSeries())
            }
            val newGood = feed.recent.mapNotNull {
                when (it) {
                    is app.embertv.data.Movie -> if (fresh(it) && (it.rating.toFloatOrNull() ?: 0f) >= 6.5f) moviePick(it) else null
                    else -> null
                }
            }
            val rest = feed.movieRows.flatMap { it.second }.filter(::fresh).map(::moviePick) +
                feed.seriesRows.flatMap { it.second }.filter { it.cover.isNotBlank() }.map(::seriesPick)
            val personal = (because + myList).shuffled()
            value = (personal + newGood.shuffled() + rest.shuffled())
                .distinctBy { it.kind + it.title }
                .take(40)
        }
    }

    var art by remember { mutableStateOf<Artwork?>(null) }
    LaunchedEffect(picks) {
        if (picks.isEmpty()) return@LaunchedEffect
        var i = 0
        while (true) {
            val p = picks[i % picks.size]
            val info = try {
                p.details(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SlideInfo("", "")
            }
            art = Artwork(i, p.title, p.kind, info.backdrop, p.poster, info.line)
            // OK opens what's showing.
            vm.screensaverTarget = p.target
            delay(SLIDE_MS)
            i++
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = art, animationSpec = tween(1600), label = "screensaver") { a ->
            if (a != null) Slide(a)
        }
        // Gentle shade so the clock and title stay readable on bright artwork.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xB3000000))
            )
        )
        Column(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp), horizontalAlignment = Alignment.End) {
            Clock(Modifier)
            // Your team's game, at a glance.
            val line = teamLine(vm, LocalNow.current)
            if (line != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.glassSurface(RoundedCornerShape(12.dp), Color(0x33000000)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(line, style = Txt.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.White, maxLines = 1)
                }
            }
        }
        val a = art
        if (a != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp).width(620.dp)) {
                Text(a.kind.uppercase(), style = Txt.Caption.copy(letterSpacing = 1.5.sp), color = Ember.Dim)
                Spacer(Modifier.height(4.dp))
                Text(a.title, style = Txt.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (a.line.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(a.line, style = Txt.Label, color = Ember.Dim, maxLines = 1)
                }
                Spacer(Modifier.height(12.dp))
                RemoteHints(listOf(Hint("OK", "Open"), Hint("BACK", "Back to the app")))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 48.dp)) { Wordmark() }
    }
}

@Composable
private fun Slide(a: Artwork) {
    // Ken Burns: each picture slowly drifts and zooms while it's on screen.
    val zoom = remember(a.id) { Animatable(1f) }
    LaunchedEffect(a.id) { zoom.animateTo(1.12f, tween((SLIDE_MS + 2_000).toInt(), easing = LinearEasing)) }
    val drift = if (a.id % 2 == 0) 1f else -1f
    if (a.backdrop.isNotBlank()) {
        NetImage(
            a.backdrop,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.value
                scaleY = zoom.value
                translationX = drift * (zoom.value - 1f) * size.width * 0.25f
            },
            ContentScale.Crop,
        )
    } else {
        // Only a poster: blur-free fallback of a dimmed fill with the poster beside it.
        Box(Modifier.fillMaxSize()) {
            NetImage(
                a.poster,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.value * 1.3f
                    scaleY = zoom.value * 1.3f
                    alpha = 0.35f
                },
                ContentScale.Crop,
            )
            NetImage(
                a.poster,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 120.dp)
                    .size(width = 220.dp, height = 330.dp)
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                    }
                    .clip(RoundedCornerShape(16.dp)),
                ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val time = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val date = remember { SimpleDateFormat("EEEE d MMMM", Locale.UK) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time.format(Date(now)), style = Txt.Hero.copy(fontSize = 64.sp, lineHeight = 68.sp), color = Color.White)
        Text(date.format(Date(now)), style = Txt.Label, color = Ember.Dim)
    }
}

/** "Celtic 1–0 Falkirk · 67'" while your team plays, otherwise "Falkirk v Celtic · In 2h 50m". */
private fun teamLine(vm: AppViewModel, nowSec: Long): String? {
    val ms = vm.teamMatches
    ms.firstOrNull { it.state == app.embertv.data.MatchState.LIVE }?.let {
        return "${it.home.shortName} ${it.home.score}–${it.away.score} ${it.away.shortName} · ${it.status}"
    }
    val next = ms.filter { it.state == app.embertv.data.MatchState.UPCOMING && it.kickoff > nowSec * 1000 }.minByOrNull { it.kickoff } ?: return null
    return "${next.home.shortName} v ${next.away.shortName} · ${kickoffCountdown(next.kickoff, nowSec)}"
}
