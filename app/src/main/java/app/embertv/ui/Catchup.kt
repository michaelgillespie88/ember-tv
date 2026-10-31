package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.Channel
import app.embertv.data.Programme
import app.embertv.data.catchupAvailable

/** Everything on [channel] that can be watched again, newest first, a day at a time. */
@Composable
fun CatchupScreen(vm: AppViewModel, channel: Channel) {
    val repo = vm.repo ?: return
    val isTv = LocalIsTv.current
    val now = LocalNow.current
    val resolved by produceState(channel, repo, channel.streamId) { value = repo.resolve(channel) }
    val load by rememberLoad(repo, channel.streamId, vm.reloadToken) { repo.fullEpg(channel.streamId) }
    var day by remember { mutableStateOf<Long?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF17110E), Ember.Bg)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 56.dp else 16.dp, vertical = if (isTv) 32.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isTv) {
                    IconCircleButton(EmberIcons.Back, "Back", glass = true, onClick = { vm.pop() })
                    Spacer(Modifier.width(10.dp))
                }
                ChannelLogo(channel.icon, channel.name, Modifier.size(width = if (isTv) 72.dp else 56.dp, height = if (isTv) 48.dp else 38.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(channel.name, style = if (isTv) Txt.Title else Txt.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Catch-up TV", style = Txt.Label, color = Ember.Text)
                }
            }
            Spacer(Modifier.height(if (isTv) 20.dp else 14.dp))

            when (val r = load) {
                is LoadResult.Loading -> SkeletonRows(Modifier.weight(1f), underHeader = false)
                is LoadResult.Err -> ErrorView(r.message, Modifier.weight(1f)) { vm.reload() }
                is LoadResult.Ok -> {
                    val available = r.value.filter { catchupAvailable(resolved, it, now) }.sortedByDescending { it.start }
                    if (!resolved.hasArchive) {
                        EmptyView("${channel.name} doesn't have catch-up from your provider.", Modifier.weight(1f))
                    } else if (available.isEmpty()) {
                        EmptyView("Nothing to catch up on yet. Your provider's guide has no past programmes for this channel.", Modifier.weight(1f))
                    } else {
                        val days = available.map { startOfDay(it.start) }.distinct()
                        val selected = day?.takeIf { it in days } ?: days.first()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(days, key = { it }) { d ->
                                DayChip(formatDay(d, now), d == selected, "catchup:day:$d", autoFocus = false) { day = d }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val list = available.filter { startOfDay(it.start) == selected }
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 8.dp),
                        ) {
                            items(list, key = { it.start }) { p ->
                                CatchupRow(
                                    p = p,
                                    now = now,
                                    focusKey = "catchup:${channel.streamId}:${p.start}",
                                    autoFocus = isTv && p == list.first(),
                                ) { vm.playCatchup(resolved, p) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayChip(text: String, selected: Boolean, focusKey: String, autoFocus: Boolean, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.height(38.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(19.dp),
        focusedScale = 1.06f,
        color = if (selected) Color(0x40FFFFFF) else Color(0x14FFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = Txt.Label.copy(fontSize = 14.sp),
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

@Composable
private fun CatchupRow(p: Programme, now: Long, focusKey: String, autoFocus: Boolean, onClick: () -> Unit) {
    val live = p.isLive(now)
    FocusBox(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        color = Ember.Card,
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(64.dp)) {
                Text(formatClock(p.start), style = Txt.Label, color = if (focused) Color.Black else Ember.Text)
                Text(formatClock(p.end), style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Faint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.title,
                        style = Txt.Label,
                        color = if (focused) Color.Black else Ember.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (live) {
                        Spacer(Modifier.width(10.dp))
                        LiveTag()
                    }
                }
                if (p.description.isNotBlank()) {
                    Text(
                        p.description,
                        style = Txt.Caption,
                        color = if (focused) Color(0xFF55555C) else Ember.Faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (live) {
                    Text("Watch from the start", style = Txt.Caption, color = if (focused) Color(0xFF333338) else Ember.Text)
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(EmberIcons.Play, contentDescription = "Play", tint = if (focused) Color.Black else Ember.Dim, modifier = Modifier.size(20.dp))
        }
    }
}
