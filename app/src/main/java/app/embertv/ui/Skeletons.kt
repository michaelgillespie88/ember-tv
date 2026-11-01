package app.embertv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Apple-style loading: faint placeholder shapes that gently pulse, in the layout the page is about
// to have, instead of a spinner. Pages feel quicker and don't jump about when they fill in.

private val Fill = Color(0x17FFFFFF)

/** A slow breathe between dim and less dim. */
@Composable
private fun rememberPulse(): Float {
    var bright by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            bright = !bright
            delay(900)
        }
    }
    val alpha by animateFloatAsState(if (bright) 1f else 0.45f, label = "skeleton")
    return alpha
}

/**
 * Phones: keep clear of the frosted header at the top and the tab bar at the bottom, and leave the
 * same side margins as the real lists, so the placeholders sit exactly where the content will.
 */
@Composable
private fun Modifier.phoneInsets(): Modifier {
    if (LocalIsTv.current) return this
    val pad = LocalContentPadding.current
    return this.padding(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 10.dp, bottom = pad.calculateBottomPadding())
}

@Composable
private fun Block(modifier: Modifier, radius: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Fill))
}

/** A grid of poster shapes, for films and series. */
@Composable
fun SkeletonPosters(modifier: Modifier = Modifier, minSize: Dp = 104.dp, gap: Dp = 14.dp) {
    val pulse = rememberPulse()
    BoxWithConstraints(modifier.fillMaxSize().phoneInsets().graphicsLayer { alpha = pulse }) {
        val columns = ((maxWidth.value + gap.value) / (minSize.value + gap.value)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            repeat(3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(columns) {
                        Column(Modifier.weight(1f)) {
                            Block(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Spacer(Modifier.height(8.dp))
                            Block(Modifier.fillMaxWidth(0.7f).height(10.dp), radius = 5.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A list of channel rows: a logo square and two lines of text. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, rows: Int = 10, underHeader: Boolean = true) {
    val pulse = rememberPulse()
    Column(
        modifier.fillMaxSize().then(if (underHeader) Modifier.phoneInsets() else Modifier).graphicsLayer { alpha = pulse }.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) { i ->
            Row(Modifier.fillMaxWidth().height(52.dp)) {
                Block(Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    // Slightly different widths so it looks like real names.
                    Block(Modifier.fillMaxWidth(listOf(0.42f, 0.55f, 0.36f, 0.48f)[i % 4]).height(12.dp), radius = 6.dp)
                    Spacer(Modifier.height(8.dp))
                    Block(Modifier.fillMaxWidth(listOf(0.7f, 0.6f, 0.75f, 0.5f)[i % 4]).height(10.dp), radius = 5.dp)
                }
            }
        }
    }
}

/** Home: the big feature card, then a couple of shelves of wide cards. */
@Composable
fun SkeletonHome(modifier: Modifier = Modifier) {
    val pulse = rememberPulse()
    val isTv = LocalIsTv.current
    val side = if (isTv) 48.dp else 20.dp
    val top = if (isTv) 12.dp else LocalContentPadding.current.calculateTopPadding() + 12.dp
    Column(
        modifier.fillMaxSize().graphicsLayer { alpha = pulse }.padding(start = side, end = side, top = top),
    ) {
        Block(Modifier.fillMaxWidth().height(if (isTv) 200.dp else 170.dp), radius = 20.dp)
        repeat(2) {
            Spacer(Modifier.height(26.dp))
            Block(Modifier.width(160.dp).height(14.dp), radius = 7.dp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(if (isTv) 5 else 3) {
                    Block(Modifier.width(if (isTv) 200.dp else 150.dp).aspectRatio(16f / 9f), radius = 14.dp)
                }
            }
        }
    }
}

/**
 * A whole TV page still finding its categories: the groups down the left and the content shape
 * ([posters] for films and series, otherwise channel rows) on the right.
 */
@Composable
fun SkeletonPage(posters: Boolean, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    if (!isTv) {
        SkeletonPhonePage(posters, modifier)
        return
    }
    val pulse = rememberPulse()
    Row(modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 8.dp)) {
        Column(Modifier.width(188.dp).graphicsLayer { alpha = pulse }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(9) { i ->
                Block(Modifier.fillMaxWidth(listOf(0.8f, 0.6f, 0.7f, 0.55f)[i % 4]).height(30.dp), radius = 15.dp)
            }
        }
        Spacer(Modifier.width(24.dp))
        if (posters) SkeletonPosters(Modifier.weight(1f), minSize = 120.dp) else SkeletonRows(Modifier.weight(1f))
    }
}

/** Phones: the category chips live in the frosted header, so this is just the content shape. */
@Composable
fun SkeletonPhonePage(posters: Boolean, modifier: Modifier = Modifier) {
    if (posters) SkeletonPosters(modifier) else SkeletonRows(modifier)
}

/** A few faint, pulsing lines where text (a description, what's on) is still loading. */
@Composable
fun SkeletonLines(modifier: Modifier = Modifier, widths: List<Float> = listOf(0.9f, 0.75f, 0.5f), lineHeight: Dp = 12.dp) {
    val pulse = rememberPulse()
    Column(modifier.graphicsLayer { alpha = pulse }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (w in widths) Block(Modifier.fillMaxWidth(w).height(lineHeight), radius = lineHeight / 2)
    }
}
