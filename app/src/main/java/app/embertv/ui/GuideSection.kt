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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.embertv.data.catchupAvailable
import app.embertv.data.toChannel
import kotlinx.coroutines.delay

private const val WINDOW_MINUTES = 120
/** How far the guide goes back and forward, in two-hour steps: a week each way. */
private const val MAX_STEPS = 84
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
            // Two-hour steps away from now: negative is earlier (catch-up), positive is later.
            var step by remember { mutableIntStateOf(0) }
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
                GuideGrid(vm, repo, selectedId, step, { step = it.coerceIn(-MAX_STEPS, MAX_STEPS) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideGrid(
    vm: AppViewModel,
    repo: Repository,
    categoryId: String,
    step: Int,
    onStep: (Int) -> Unit,
    modifier: Modifier,
) {
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
            val windowStart = (now / 1800) * 1800 + step * WINDOW_MINUTES * 60L
            val windowEnd = windowStart + WINDOW_MINUTES * 60

            Column(modifier.fillMaxHeight()) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { FocusStrip(focus, now) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        TvButton("Earlier", icon = "◀", focusKey = "guide:earlier", onClick = { onStep(step - 1) })
                        TvButton("Now", focusKey = "guide:now", onClick = { onStep(0) })
                        TvButton("Later ▶", focusKey = "guide:later", onClick = { onStep(step + 1) })
                    }
                }
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
                                    full = step != 0,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    dpPerMinute = dpPerMinute,
                                    gridWidth = gridWidth,
                                    now = now,
                                    onFocus = { p -> focus = GuideFocus(ch, p) },
                                    onPick = { p ->
                                        if (p == null || p.isLive(now)) {
                                            vm.push(Screen.PlayLive(channels, index))
                                        } else {
                                            details = GuideFocus(ch, p)
                                        }
                                    },
                                    onLongPick = { p -> if (p != null) details = GuideFocus(ch, p) },
                                )
                            }
                        }
                    }
                }
            }

            val d = details
            if (d != null) {
                ProgrammeDialog(vm, d, channels) { details = null }
            }
        }
    }
}

@Composable
private fun FocusStrip(focus: GuideFocus?, now: Long) {
    Column(Modifier.fillMaxWidth().height(86.dp).padding(start = 6.dp, top = 4.dp)) {
        if (focus == null) {
            Text("TV guide", style = Txt.Title)
            Text(
                "OK on anything airing now starts the channel. Pick an earlier programme to catch up, or a later one for a reminder.",
                style = Txt.Body,
                color = Ember.Dim,
                maxLines = 2,
            )
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
            val sub = if (p == null) "No guide information" else "${focus.channel.name}  ·  ${formatDay(p.start, now)}  ${formatClock(p.start)} – ${formatClock(p.end)}"
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
        Text(
            formatDay(windowStart, now),
            style = Txt.Label,
            color = if (startOfDay(windowStart) == startOfDay(now)) Ember.Dim else Ember.Accent,
            modifier = Modifier.offset(y = 4.dp),
        )
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
        if (now in windowStart until windowStart + WINDOW_MINUTES * 60) Box(
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
    full: Boolean,
    windowStart: Long,
    windowEnd: Long,
    dpPerMinute: Dp,
    gridWidth: Dp,
    now: Long,
    onFocus: (Programme?) -> Unit,
    onPick: (Programme?) -> Unit,
    onLongPick: (Programme?) -> Unit,
) {
    val programmes = if (full) rememberFullProgrammes(repo, channel.streamId) else rememberProgrammes(repo, channel.streamId)
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
                        past = p.end <= now,
                        catchup = p.end <= now && catchupAvailable(channel, p, now),
                        focusKey = "guide:${channel.streamId}:${p.start}",
                        modifier = Modifier.offset(x = x).width(w.coerceAtLeast(6.dp)).fillMaxHeight(),
                        onFocus = { onFocus(p) },
                        onClick = { onPick(p) },
                        onLongClick = { onLongPick(p) },
                    )
                }
            }
            if (now in windowStart until windowEnd) {
                val nowOffset = dpPerMinute * ((now - windowStart) / 60f)
                Box(Modifier.offset(x = nowOffset).width(2.dp).fillMaxHeight().background(Ember.Accent.copy(alpha = 0.7f)))
            }
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
    past: Boolean = false,
    catchup: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Box(modifier.padding(end = 3.dp)) {
        FocusBox(
            modifier = Modifier.fillMaxSize(),
            focusKey = focusKey,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1f,
            color = when {
                live -> Ember.CardHi
                past -> Ember.Card.copy(alpha = 0.55f)
                else -> Ember.Card
            },
            focusedColor = Color.White,
            onFocusChange = { if (it) onFocus() },
            onClick = onClick,
            onLongClick = onLongClick,
        ) { focused ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (catchup) {
                        androidx.compose.material3.Icon(
                            EmberIcons.Catchup,
                            contentDescription = "On catch-up",
                            tint = if (focused) Color.Black else Ember.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        title,
                        style = Txt.Label,
                        color = when {
                            focused -> Color.Black
                            past && !catchup -> Ember.Dim
                            else -> Ember.Text
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/** The guide's full guide data for one channel (past and future), loaded when it scrolls into view. */
@Composable
fun rememberFullProgrammes(repo: Repository, streamId: Int): List<Programme>? {
    val programmes by produceState(repo.cachedFullEpg(streamId), repo, streamId) {
        if (value == null) {
            delay(300)
            value = repo.fullEpg(streamId)
        }
    }
    return programmes
}

private class DialogAction(
    val label: String,
    val tvIcon: String?,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val run: () -> Unit,
)

/**
 * Details for a programme in the guide, with what you can do with it: watch it live or from the
 * start, play it from catch-up, or set a reminder.
 */
@Composable
internal fun ProgrammeDialog(vm: AppViewModel, focus: GuideFocus, channels: List<Channel>, onDismiss: () -> Unit) {
    val p = focus.programme ?: return
    val repo = vm.repo ?: return
    val isTv = LocalIsTv.current
    val now = LocalNow.current
    val channel by produceState(focus.channel, repo, focus.channel.streamId) { value = repo.resolve(focus.channel) }
    val live = p.isLive(now)
    val past = p.end <= now
    val catchup = catchupAvailable(channel, p, now)
    val reminded = vm.hasReminder(channel.streamId, p.start)

    fun watchChannel() {
        onDismiss()
        val i = channels.indexOfFirst { it.streamId == channel.streamId }
        if (i >= 0) vm.push(Screen.PlayLive(channels, i)) else vm.push(Screen.PlayLive(listOf(channel), 0))
    }

    val actions = buildList {
        when {
            live -> {
                add(DialogAction("Watch live", "▶", EmberIcons.Play) { watchChannel() })
                if (catchup) add(DialogAction("Watch from the start", "⏮", EmberIcons.FromStart) { onDismiss(); vm.playCatchup(channel, p) })
            }
            past -> {
                if (catchup) add(DialogAction("Play from catch-up", "▶", EmberIcons.Play) { onDismiss(); vm.playCatchup(channel, p) })
                add(DialogAction("Watch channel now", null, null) { watchChannel() })
            }
            else -> {
                add(DialogAction(if (reminded) "Cancel reminder" else "Remind me", null, EmberIcons.Bell) { vm.toggleReminder(channel, p); onDismiss() })
                add(DialogAction("Watch channel now", null, null) { watchChannel() })
            }
        }
    }

    val eyebrow = "${channel.name}  ·  ${formatDay(p.start, now)}  ${formatClock(p.start)} – ${formatClock(p.end)}" + if (live) "  ·  LIVE" else ""
    val note = when {
        past && !catchup -> "This programme isn't available on catch-up."
        !past && !live && reminded -> "You'll get a reminder when it starts, as long as Streammania is open."
        else -> null
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 600.dp else 520.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(p.title, eyebrow = eyebrow, subtitle = p.description.takeIf { it.isNotBlank() }, subtitleLines = if (isTv) 4 else 6)
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note, style = Txt.Caption, color = if (reminded) Ember.Accent else Ember.Faint, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.width(if (isTv) 380.dp else 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, a ->
                    SheetOption(a.label, focusKey = "prog:$i", autoFocus = isTv && i == 0, primary = i == 0, onClick = a.run)
                }
                SheetOption("Close", focusKey = "prog:close", onClick = onDismiss)
            }
        }
    }
}
