package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.embertv.data.Movie
import app.embertv.data.Quality
import app.embertv.data.Series
import app.embertv.data.Versions

/** The qualities a title comes in, best first, e.g. [4K, FHD, HD]. */
fun Movie.qualities(): List<Quality> =
    (if (versions.isNotEmpty()) versions.map { it.quality } else listOf(quality)).filter { it != Quality.UNKNOWN }.distinct()

fun Series.qualities(): List<Quality> =
    (if (versions.isNotEmpty()) versions.map { it.quality } else listOf(quality)).filter { it != Quality.UNKNOWN }.distinct()

/** "4K · FHD" for the corner of a poster, or null when nothing's known. */
fun badgeText(qualities: List<Quality>): String? = qualities.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.badge }

/** Small pill on a poster's corner. */
@Composable
fun PosterBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            // Apple style: a small frosted tag.
            .glassSurface(RoundedCornerShape(6.dp), Color(0xA6101014))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
    }
}

/** "4K  FHD  HD" chips for a detail screen. */
@Composable
fun QualityChips(qualities: List<Quality>, modifier: Modifier = Modifier, plan: String = "Choose when you play") {
    if (qualities.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (q in qualities) {
            // Apple style: small frosted tags rather than outlined boxes.
            Box(
                Modifier
                    .glassSurface(RoundedCornerShape(7.dp), Color(0x1FFFFFFF))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(q.badge, style = Txt.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Ember.Text)
            }
        }
        if (qualities.size > 1) {
            Spacer(Modifier.width(2.dp))
            Text(plan, style = Txt.Caption, color = Ember.Dim)
        }
    }
}

/** The "4K" / "FHD" tag at the start of a quality choice. */
@Composable
private fun QualityTag(q: Quality, dark: Boolean) {
    // A frosted tag on the dark card, a soft grey one on the white highlight.
    Box(
        Modifier
            .width(58.dp)
            .height(34.dp)
            .then(
                if (dark) Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14000000))
                else Modifier.glassSurface(RoundedCornerShape(10.dp), Color(0x1FFFFFFF))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            q.badge.ifEmpty { "SD" },
            style = Txt.Label.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = if (dark) Color.Black else Ember.Text,
            textAlign = TextAlign.Center,
        )
    }
}

/** Which quality to play: one choice per version, best first, Apple TV style. */
@Composable
fun VersionPickerDialog(vm: AppViewModel, choice: VersionChoice) {
    val isTv = LocalIsTv.current
    val close = { vm.choosingVersion = null }
    fun pick(i: Int) {
        when (choice) {
            is VersionChoice.ForMovie -> vm.playMovieVersion(choice.versions[i], choice.startAt)
            is VersionChoice.ForEpisode -> vm.playEpisodeVersion(choice.versions[i], choice.from, choice.episodes, choice.index, choice.fromStart)
            is VersionChoice.ForChannel -> vm.playChannelCopy(choice, i)
        }
    }
    val episodeLine = (choice as? VersionChoice.ForEpisode)?.let { c ->
        val e = c.episodes[c.index]
        "Season ${e.season} · Episode ${e.number}"
    }
    Dialog(onDismissRequest = close) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .heightIn(max = if (isTv) 520.dp else 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (choice is VersionChoice.ForChannel) {
                ChannelChoiceHeader(vm, choice)
            } else {
                SheetTitle(choice.title, eyebrow = "Choose quality", subtitle = episodeLine)
            }
            Spacer(Modifier.height(22.dp))
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                itemsIndexed(choice.qualities) { i, q ->
                    val name = choice.names[i]
                    SheetOption(
                        title = (listOf(q.title) + Versions.extras(name)).joinToString(" · "),
                        // Channels: just the quality (their raw names repeat "FHD : BBC One"); films keep their name.
                        subtitle = if (i == 0) "Best available" else if (choice is VersionChoice.ForChannel) null else name,
                        focusKey = "version:$i",
                        autoFocus = isTv && i == 0,
                        leading = { dark -> QualityTag(q, dark) },
                    ) { pick(i) }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    SheetOption(
                        "Cancel",
                        focusKey = "version:cancel",
                        leading = { dark ->
                            Box(Modifier.width(58.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Icon(EmberIcons.Close, contentDescription = null, tint = if (dark) Color.Black else Ember.Dim, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = close,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "To always play one quality without asking, choose it in Settings under Quality to play.",
                style = Txt.Caption,
                color = Ember.Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** A white tick in a dark circle: already watched. */
@Composable
fun WatchedTick(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .glassSurface(androidx.compose.foundation.shape.CircleShape, Color(0xA6101014)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(EmberIcons.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** The top of the quality card for a channel: its logo and name, and what's on now. */
@Composable
private fun ChannelChoiceHeader(vm: AppViewModel, choice: VersionChoice.ForChannel) {
    val ch = choice.copies.first()
    val repo = vm.repo
    val now = LocalNow.current
    val onNow = if (repo != null) rememberProgrammes(repo, ch.streamId)?.firstOrNull { it.isLive(now) } else null
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CHOOSE QUALITY", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp), color = Ember.Dim)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(width = 96.dp, height = 60.dp)
                .glassSurface(RoundedCornerShape(14.dp), Color(0x14FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(ch.icon, choice.title, Modifier.size(width = 72.dp, height = 44.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(choice.title, style = Txt.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onNow != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveTag()
                Spacer(Modifier.width(8.dp))
                Text(onNow.title, style = Txt.Label, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x26FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(onNow.progress(now).coerceIn(0f, 1f)).background(Ember.Text))
            }
        }
    }
}
