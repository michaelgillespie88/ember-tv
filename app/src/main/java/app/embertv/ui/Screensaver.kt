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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One picture in the slideshow. [backdrop] is wide artwork; [poster] is used when there isn't any. */
private data class Artwork(val id: Int, val title: String, val kind: String, val backdrop: String, val poster: String)

private class Pick(val title: String, val kind: String, val poster: String, val details: suspend (Repository) -> String)

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
            val movies = feed.movieRows.flatMap { it.second }.filter { it.poster.isNotBlank() }.distinctBy { it.streamId }.map { m ->
                Pick(m.name, "Movie", m.poster) { r -> r.movieInfo(m.streamId).backdrop }
            }
            val series = feed.seriesRows.flatMap { it.second }.filter { it.cover.isNotBlank() }.distinctBy { it.seriesId }.map { s ->
                Pick(s.name, "Series", s.cover) { r -> r.seriesInfo(s.seriesId).backdrop }
            }
            value = (movies + series).shuffled().take(40)
        }
    }

    var art by remember { mutableStateOf<Artwork?>(null) }
    LaunchedEffect(picks) {
        if (picks.isEmpty()) return@LaunchedEffect
        var i = 0
        while (true) {
            val p = picks[i % picks.size]
            val backdrop = try {
                p.details(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            art = Artwork(i, p.title, p.kind, backdrop, p.poster)
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
        Clock(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp))
        val a = art
        if (a != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp).width(620.dp)) {
                Text(a.kind.uppercase(), style = Txt.Caption.copy(letterSpacing = 1.5.sp), color = Ember.Dim)
                Spacer(Modifier.height(4.dp))
                Text(a.title, style = Txt.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
