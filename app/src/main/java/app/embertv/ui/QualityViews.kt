package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC0B0B0E))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
    }
}

/** "4K  FHD  HD" chips for a detail screen. */
@Composable
fun QualityChips(qualities: List<Quality>, modifier: Modifier = Modifier) {
    if (qualities.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (q in qualities) {
            Box(
                Modifier
                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(q.badge, style = Txt.Caption.copy(fontWeight = FontWeight.Bold), color = Ember.Text)
            }
        }
        if (qualities.size > 1) Text("You'll choose when you press play", style = Txt.Caption, color = Ember.Faint)
    }
}

/** Which quality to play: one row per version, best first. */
@Composable
fun VersionPickerDialog(vm: AppViewModel, choice: VersionChoice) {
    val isTv = LocalIsTv.current
    val close = { vm.choosingVersion = null }
    fun pick(i: Int) {
        when (choice) {
            is VersionChoice.ForMovie -> vm.playMovieVersion(choice.versions[i], choice.startAt)
            is VersionChoice.ForEpisode -> vm.playEpisodeVersion(choice.versions[i], choice.from, choice.episodes, choice.index, choice.fromStart)
        }
    }
    Dialog(onDismissRequest = close) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .heightIn(max = if (isTv) 480.dp else 620.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 26.dp else 20.dp)
        ) {
            Text("Choose quality", style = Txt.Caption, color = Ember.Accent)
            Spacer(Modifier.height(4.dp))
            Text(choice.title, style = Txt.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (choice is VersionChoice.ForEpisode) {
                val e = choice.episodes[choice.index]
                Text("Season ${e.season} · Episode ${e.number}", style = Txt.Label, color = Ember.Dim)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(choice.qualities) { i, q ->
                    val name = choice.names[i]
                    val extras = Versions.extras(name)
                    FocusBox(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        focusKey = "version:$i",
                        shape = RoundedCornerShape(14.dp),
                        focusedScale = 1.03f,
                        color = Ember.Card,
                        focusedColor = Color.White,
                        autoFocus = isTv && i == 0,
                        onClick = { pick(i) },
                    ) { focused ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (focused) Color(0x1F000000) else Color(0x1FFFFFFF))
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    q.badge.ifEmpty { "–" },
                                    style = Txt.Label.copy(fontWeight = FontWeight.Bold),
                                    color = if (focused) Color.Black else Ember.Text,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (listOf(q.title) + extras).joinToString(" · "),
                                    style = Txt.Label,
                                    color = if (focused) Color.Black else Ember.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    name,
                                    style = Txt.Caption,
                                    color = if (focused) Color(0xFF55555C) else Ember.Faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("To skip this question, pick a quality in Settings → Quality to play.", style = Txt.Caption, color = Ember.Faint)
            Spacer(Modifier.height(14.dp))
            if (isTv) TvButton("Cancel", onClick = close) else PillButton("Cancel", modifier = Modifier.fillMaxWidth(), onClick = close)
        }
    }
}
