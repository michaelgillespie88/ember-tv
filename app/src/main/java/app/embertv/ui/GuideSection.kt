package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.FAVOURITES
import app.embertv.data.Programme
import app.embertv.data.Repository
import app.embertv.data.toChannel

private const val WINDOW_MINUTES = 120
private val CHANNEL_COLUMN = 170.dp
private val ROW_HEIGHT = 62.dp

internal data class GuideFocus(val channel: Channel, val programme: Programme?)

@Composable
fun GuideSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val section = vm.guide
    val cats by rememberLoad(repo, vm.reloadToken) { repo.liveCategories() }
    when (val c = cats) {
        is LoadResult.Loading -> LoadingView(text = "Loading guide…")
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) {
                listOf(Category(FAVOURITES, "★  Favourites"), Category(ALL, "All channels")) + c.value
            }
            val selectedId = section.categoryId ?: c.value.firstOrNull()?.id ?: ALL
            val select = rememberCategorySelector(section)
            Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 48.dp, top = 6.dp)) {
                CategoryRail(
                    categories = categories,
                    selectedId = selectedId,
                    state = section.rail,
                    keyPrefix = "guide",
                    onFocusCategory = { select(it.id, false) },
                    onSelectCategory = { select(it.id, true) },
                )
                Spacer(Modifier.width(20.dp))
                GuideGrid(vm, repo, selectedId, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideGrid(vm: AppViewModel, repo: Repository, categoryId: String, modifier: Modifier) {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.LIVE } else null
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toChannel() } ?: repo.channels(categoryId)
    }
    var focus by remember { mutableStateOf<GuideFocus?>(null) }
    var details by remember { mutableStateOf<GuideFocus?>(null) }

    when (val r = load) {
        is LoadResult.Loading -> LoadingView(modifier)
        is LoadResult.Err -> ErrorView(r.message, modifier) { vm.reload() }
        is LoadResult.Ok -> {
            val channels = r.value
            if (channels.isEmpty()) {
                EmptyView(
                    if (categoryId == FAVOURITES) "${holdPrompt()} any channel to add it to your favourites."
                    else "No channels in this category.",
                    modifier,
                )
                return
            }
            val now = LocalNow.current
            val windowStart = (now / 1800) * 1800
            val windowEnd = windowStart + WINDOW_MINUTES * 60

            Column(modifier.fillMaxHeight()) {
                FocusStrip(focus, now)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val gridWidth = maxWidth - CHANNEL_COLUMN
                    val dpPerMinute = gridWidth / WINDOW_MINUTES
                    Column(Modifier.fillMaxSize()) {
                        TimeHeader(windowStart, dpPerMinute, now)
                        LazyColumn(
                            state = vm.guide.list(categoryId),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                                GuideRow(
                                    repo = repo,
                                    channel = ch,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    dpPerMinute = dpPerMinute,
                                    gridWidth = gridWidth,
                                    now = now,
                                    onFocus = { p -> focus = GuideFocus(ch, p) },
                                    onPick = { p ->
                                        if (p == null || p.isLive(now) || p.end <= now) {
                                            vm.push(Screen.PlayLive(channels, index))
                                        } else {
                                            details = GuideFocus(ch, p)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val d = details
            if (d != null) {
                ProgrammeDialog(
                    focus = d,
                    onWatch = {
                        details = null
                        val i = channels.indexOfFirst { it.streamId == d.channel.streamId }
                        if (i >= 0) vm.push(Screen.PlayLive(channels, i))
                    },
                    onDismiss = { details = null },
                )
            }
        }
    }
}

@Composable
private fun FocusStrip(focus: GuideFocus?, now: Long) {
    Column(Modifier.fillMaxWidth().height(86.dp).padding(start = 6.dp, top = 4.dp)) {
        if (focus == null) {
            Text("TV guide", style = Txt.Title)
            Text("Pick a programme to see what's on. OK on anything airing now starts the channel.", style = Txt.Body, color = Ember.Dim)
        } else {
            val p = focus.programme
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p?.title ?: focus.channel.name,
                    style = Txt.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (p != null && p.isLive(now)) {
                    Spacer(Modifier.width(12.dp))
                    LiveTag()
                }
            }
            val sub = if (p == null) "No guide information" else "${focus.channel.name}  ·  ${formatClock(p.start)} – ${formatClock(p.end)}"
            Text(sub, style = Txt.Label, color = Ember.Dim, maxLines = 1)
            if (p != null && p.description.isNotBlank()) {
                Text(p.description, style = Txt.Caption, color = Ember.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LiveTag() {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Ember.Live).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("LIVE", style = Txt.Caption, color = Color.White)
    }
}

@Composable
private fun TimeHeader(windowStart: Long, dpPerMinute: Dp, now: Long) {
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        for (slot in 0 until WINDOW_MINUTES / 30) {
            val t = windowStart + slot * 1800L
            Text(
                formatClock(t),
                style = Txt.Caption,
                color = Ember.Dim,
                modifier = Modifier.offset(x = CHANNEL_COLUMN + dpPerMinute * (slot * 30).toFloat() + 6.dp, y = 6.dp),
            )
        }
        val nowOffset = dpPerMinute * ((now - windowStart) / 60f)
        Box(
            Modifier
                .offset(x = CHANNEL_COLUMN + nowOffset - 4.dp, y = 22.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Ember.Accent)
        )
    }
}

@Composable
private fun GuideRow(
    repo: Repository,
    channel: Channel,
    windowStart: Long,
    windowEnd: Long,
    dpPerMinute: Dp,
    gridWidth: Dp,
    now: Long,
    onFocus: (Programme?) -> Unit,
    onPick: (Programme?) -> Unit,
) {
    val programmes = rememberProgrammes(repo, channel.streamId)
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(
            Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.icon, channel.name, Modifier.size(width = 52.dp, height = 36.dp))
            Spacer(Modifier.width(10.dp))
            Text(channel.name, style = Txt.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            val visible = programmes.orEmpty().filter { it.end > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                ProgrammeBlock(
                    title = if (programmes == null) "Loading…" else channel.name,
                    time = if (programmes == null) "" else "No guide information",
                    live = true,
                    focusKey = "guide:${channel.streamId}:none",
                    modifier = Modifier.fillMaxSize(),
                    onFocus = { onFocus(null) },
                    onClick = { onPick(null) },
                )
            } else {
                for (p in visible) {
                    val start = maxOf(p.start, windowStart)
                    val end = minOf(p.end, windowEnd)
                    val x = dpPerMinute * ((start - windowStart) / 60f)
                    val w = dpPerMinute * ((end - start) / 60f)
                    ProgrammeBlock(
                        title = p.title,
                        time = "${formatClock(p.start)} – ${formatClock(p.end)}",
                        live = p.isLive(now),
                        focusKey = "guide:${channel.streamId}:${p.start}",
                        modifier = Modifier.offset(x = x).width(w.coerceAtLeast(6.dp)).fillMaxHeight(),
                        onFocus = { onFocus(p) },
                        onClick = { onPick(p) },
                    )
                }
            }
            val nowOffset = dpPerMinute * ((now - windowStart) / 60f)
            Box(Modifier.offset(x = nowOffset).width(2.dp).fillMaxHeight().background(Ember.Accent.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    time: String,
    live: Boolean,
    focusKey: String,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Box(modifier.padding(end = 3.dp)) {
        FocusBox(
            modifier = Modifier.fillMaxSize(),
            focusKey = focusKey,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1f,
            color = if (live) Ember.CardHi else Ember.Card,
            focusedColor = Color.White,
            onFocusChange = { if (it) onFocus() },
            onClick = onClick,
        ) { focused ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    style = Txt.Label,
                    color = if (focused) Color.Black else Ember.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (time.isNotEmpty()) {
                    Text(
                        time,
                        style = Txt.Caption,
                        color = if (focused) Color(0xFF444448) else Ember.Faint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProgrammeDialog(focus: GuideFocus, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val p = focus.programme ?: return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Ember.Panel)
                .padding(if (LocalIsTv.current) 32.dp else 22.dp)
        ) {
            Text(p.title, style = Txt.Title)
            Spacer(Modifier.height(6.dp))
            Text(
                "${focus.channel.name}  ·  ${formatClock(p.start)} – ${formatClock(p.end)}",
                style = Txt.Label,
                color = Ember.Dim,
            )
            if (p.description.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(p.description, style = Txt.Body, color = Ember.Dim, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(24.dp))
            if (LocalIsTv.current) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Watch channel now", icon = "▶", autoFocus = true, onClick = onWatch)
                    TvButton("Close", onClick = onDismiss)
                }
            } else {
                PillButton("Watch channel now", icon = EmberIcons.Play, primary = true, modifier = Modifier.fillMaxWidth(), onClick = onWatch)
                Spacer(Modifier.height(10.dp))
                PillButton("Close", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}
