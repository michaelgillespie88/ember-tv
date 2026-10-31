package app.embertv.ui

import androidx.compose.foundation.background
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.unit.sp
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

/** How much of the day is on screen at once: two and a half hours, like TiviMate. */
internal const val WINDOW_MINUTES = 150
/** How far the guide goes back and forward, in half-hour steps: a week each way. */
internal const val MAX_STEPS = 7 * 48
internal val CHANNEL_COLUMN = 214.dp
/** The TV guide's channel column, narrower to make room for the groups down the left. */
private val GUIDE_CHANNEL_COLUMN = 178.dp
private val RAIL_WIDTH = 188.dp
private val INFO_HEIGHT = 140.dp
private val ROW_HEIGHT = 48.dp
/** Compact rows fit about ten channels on a TV instead of seven. */
internal val COMPACT_ROW_HEIGHT = 31.dp
internal val ROW_GAP = 3.dp

internal data class GuideFocus(val channel: Channel, val programme: Programme?)

/**
 * The TV guide, laid out like TiviMate's: full screen, the highlighted programme's details and a
 * live picture across the top, and a tight grid of channels underneath that scrolls through the
 * day as you move left and right.
 */
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
            // Half-hour steps away from now: negative is earlier (catch-up), positive is later.
            var step by remember { mutableIntStateOf(0) }
            GuideGrid(
                vm = vm,
                repo = repo,
                categories = categories,
                categoryId = selectedId,
                onCategory = { select(it, true) },
                onFocusCategory = { select(it, false) },
                step = step,
                onStep = { step = it.coerceIn(-MAX_STEPS, MAX_STEPS) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GuideGrid(
    vm: AppViewModel,
    repo: Repository,
    categories: List<Category>,
    categoryId: String,
    onCategory: (String) -> Unit,
    onFocusCategory: (String) -> Unit,
    step: Int,
    onStep: (Int) -> Unit,
    modifier: Modifier,
) {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.LIVE } else null
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.let { favs -> repo.resolveAll(favs.map { it.toChannel() }) } ?: repo.channels(categoryId)
    }
    var focus by remember { mutableStateOf<GuideFocus?>(null) }
    var details by remember { mutableStateOf<GuideFocus?>(null) }
    var pickingGroup by remember { mutableStateOf(false) }
    var pickingDay by remember { mutableStateOf(false) }
    val now = LocalNow.current
    val windowStart = (now / 1800) * 1800 + step * 1800L
    val windowEnd = windowStart + WINDOW_MINUTES * 60
    val groupName = categories.firstOrNull { it.id == categoryId }?.name?.removePrefix("★  ") ?: "Channels"

    // Leaving the guide (or a pop-up opening) brings the tab bar back.
    DisposableEffect(Unit) { onDispose { vm.guideFullScreen = false } }
    // Back from a later or earlier time returns to now before going up to the tabs.
    BackHandler(enabled = step != 0 && vm.guideFullScreen) { onStep(0) }

    Box(
        modifier
            .background(Ember.Bg)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C130E), Color.Transparent), endY = 520f))
    ) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = if (vm.guideFullScreen) 18.dp else 4.dp)) {
            // ---- What's highlighted, with the channel playing live beside it ----
            Row(Modifier.fillMaxWidth().height(INFO_HEIGHT), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f).fillMaxHeight()) { GuideInfo(focus, now) }
                Spacer(Modifier.width(24.dp))
                val ch = focus?.channel ?: (load as? LoadResult.Ok)?.value?.firstOrNull()
                if (vm.livePreview) {
                    LivePreview(vm, ch, Modifier.height(INFO_HEIGHT - 12.dp))
                } else if (ch != null) {
                    Box(
                        Modifier.height(INFO_HEIGHT - 12.dp).aspectRatio(16f / 9f).glassSurface(RoundedCornerShape(14.dp), Color(0x14FFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChannelLogo(ch.icon, ch.name, Modifier.size(width = 120.dp, height = 72.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (val r = load) {
                is LoadResult.Loading -> LoadingView(Modifier.weight(1f))
                is LoadResult.Err -> ErrorView(r.message, Modifier.weight(1f)) { vm.reload() }
                is LoadResult.Ok -> Row(Modifier.fillMaxWidth().weight(1f)) {
                    // Channel groups down the left, like TiviMate, in an Apple TV style frosted sidebar.
                    Column(
                        Modifier
                            .padding(top = 2.dp, bottom = 12.dp)
                            .width(RAIL_WIDTH)
                            .fillMaxHeight()
                            .glassSurface(RoundedCornerShape(20.dp), Color(0x12FFFFFF))
                            .padding(horizontal = 6.dp),
                    ) {
                        Text(
                            "GROUPS",
                            style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp),
                            color = Ember.Faint,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                        )
                        CategoryRail(
                            categories = categories,
                            selectedId = categoryId,
                            state = vm.guide.rail,
                            keyPrefix = "guide",
                            onFocusCategory = { c ->
                                vm.guideFullScreen = true
                                onFocusCategory(c.id)
                            },
                            onSelectCategory = { c -> onCategory(c.id) },
                            width = RAIL_WIDTH - 12.dp,
                            rowHeight = 38.dp,
                            sidebar = true,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val channels = r.value
                    val compact = vm.compactLists
                    val rowHeight = if (compact) COMPACT_ROW_HEIGHT else ROW_HEIGHT
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val gridWidth = maxWidth - GUIDE_CHANNEL_COLUMN
                        val dpPerMinute = gridWidth / WINDOW_MINUTES
                        Column(Modifier.fillMaxSize()) {
                            TimeHeader(
                                windowStart = windowStart,
                                dpPerMinute = dpPerMinute,
                                now = now,
                                groupName = groupName,
                                onGroup = { pickingGroup = true },
                                onDay = { pickingDay = true },
                                onFocusHeader = { vm.guideFullScreen = false },
                                showGroup = false,
                                channelColumn = GUIDE_CHANNEL_COLUMN,
                            )
                            if (channels.isEmpty()) {
                                EmptyView(
                                    if (categoryId == FAVOURITES) "${holdPrompt()} any channel to add it to your favourites."
                                    else "No channels in this group.",
                                    Modifier.weight(1f),
                                )
                            } else {
                                LazyColumn(
                                    state = vm.guide.list(categoryId),
                                    contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onPreviewKeyEvent { ev -> guideKey(ev, focus?.programme, windowStart, windowEnd, step, onStep, leftExits = true) },
                                ) {
                                    itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                                        GuideRow(
                                            repo = repo,
                                            channel = ch,
                                            rowHeight = rowHeight,
                                            compact = compact,
                                            wantFull = step != 0,
                                            windowStart = windowStart,
                                            windowEnd = windowEnd,
                                            dpPerMinute = dpPerMinute,
                                            gridWidth = gridWidth,
                                            now = now,
                                            channelColumn = GUIDE_CHANNEL_COLUMN,
                                            onFocus = { p ->
                                                focus = GuideFocus(ch, p)
                                                vm.guideFullScreen = true
                                            },
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
    }

    if (pickingGroup) {
        GroupPicker(categories, categoryId, onDismiss = { pickingGroup = false }) {
            pickingGroup = false
            onCategory(it)
        }
    }
    if (pickingDay) {
        DayPicker(now, windowStart, onDismiss = { pickingDay = false }) { days ->
            pickingDay = false
            onStep(days * 48)
        }
    }
}

/**
 * Left and right at the edge of the screen move the times along instead of the highlight;
 * fast-forward and rewind jump two hours. Shared by the guide and the mini guide in the player.
 */
internal fun guideKey(
    ev: androidx.compose.ui.input.key.KeyEvent,
    p: Programme?,
    windowStart: Long,
    windowEnd: Long,
    step: Int,
    onStep: (Int) -> Unit,
    leftExits: Boolean = false,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    return when (ev.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (p != null && p.end >= windowEnd && step < MAX_STEPS) {
            onStep(step + 1)
            true
        } else false
        // At "now", left goes to the channel groups beside the guide (when there are any); further on, back in time.
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (p != null && p.start <= windowStart && step > -MAX_STEPS && !(leftExits && step <= 0)) {
            onStep(step - 1)
            true
        } else false
        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            onStep((step + 4).coerceAtMost(MAX_STEPS))
            true
        }
        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
            onStep((step - 4).coerceAtLeast(-MAX_STEPS))
            true
        }
        else -> false
    }
}

/** The top of the guide: the highlighted programme, big, with its time and how far through it is. */
@Composable
internal fun GuideInfo(focus: GuideFocus?, now: Long) {
    Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
        if (focus == null) {
            Text("TV GUIDE", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp), color = Ember.Accent)
            Spacer(Modifier.height(4.dp))
            Text("What's on", style = Txt.LargeTitle)
            Spacer(Modifier.height(6.dp))
            Text(
                "OK on anything airing now starts the channel. Earlier programmes play from catch-up, later ones can remind you.",
                style = Txt.Body,
                color = Ember.Dim,
                maxLines = 2,
            )
            return
        }
        val p = focus.programme
        val ch = focus.channel
        Text(
            (if (ch.num > 0) "${ch.num}  ·  " else "") + ch.name.uppercase(),
            style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp),
            color = Ember.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p?.title ?: ch.name,
                style = Txt.LargeTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (p != null && p.isLive(now)) {
                Spacer(Modifier.width(12.dp))
                LiveTag()
            }
        }
        Spacer(Modifier.height(6.dp))
        if (p == null) {
            Text("No guide information for this channel", style = Txt.Label, color = Ember.Dim)
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val left = ((p.end - now) / 60).toInt()
            val timing = when {
                p.isLive(now) -> "${formatClock(p.start)} – ${formatClock(p.end)}  ·  $left min left"
                p.end <= now -> "${formatDay(p.start, now)}  ${formatClock(p.start)} – ${formatClock(p.end)}"
                else -> "${formatDay(p.start, now)}  ${formatClock(p.start)} – ${formatClock(p.end)}  ·  ${((p.end - p.start) / 60)} min"
            }
            Text(timing, style = Txt.Label, color = Ember.Dim, maxLines = 1)
            if (p.isLive(now)) {
                Spacer(Modifier.width(12.dp))
                ProgressBar(((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.width(120.dp))
            }
        }
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                p.description,
                style = Txt.Body.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = Ember.Faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

/** Times along the top, with the group and day you're looking at as two small buttons on the left. */
@Composable
internal fun TimeHeader(
    windowStart: Long,
    dpPerMinute: Dp,
    now: Long,
    groupName: String,
    onGroup: () -> Unit,
    onDay: () -> Unit,
    onFocusHeader: () -> Unit,
    showChips: Boolean = true,
    showGroup: Boolean = true,
    channelColumn: Dp = CHANNEL_COLUMN,
) {
    Box(Modifier.fillMaxWidth().height(34.dp)) {
        val today = startOfDay(windowStart) == startOfDay(now)
        if (showChips) Row(
            Modifier.width(channelColumn - 10.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showGroup) HeaderChip(groupName, "guide:group", Modifier.weight(1f, fill = false), onFocus = onFocusHeader, onClick = onGroup)
            HeaderChip(if (today) "Today" else formatDay(windowStart, now), "guide:day", accent = !today, onFocus = onFocusHeader, onClick = onDay)
        } else {
            Text(
                (if (today) "TODAY" else formatDay(windowStart, now).uppercase()),
                style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
                color = if (today) Ember.Dim else Ember.Accent,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            )
        }
        val nowOffset = dpPerMinute * ((now - windowStart) / 60f)
        val showNow = now in windowStart until windowStart + WINDOW_MINUTES * 60
        for (slot in 0 until WINDOW_MINUTES / 30) {
            val t = windowStart + slot * 1800L
            val x = dpPerMinute * (slot * 30).toFloat()
            // Leave room for the orange "now" time rather than printing over it.
            if (showNow && x > nowOffset - 56.dp && x < nowOffset + 30.dp) continue
            Row(
                Modifier.offset(x = channelColumn + dpPerMinute * (slot * 30).toFloat()).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(1.dp).height(12.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.width(6.dp))
                Text(formatClock(t), style = Txt.Caption.copy(fontSize = 13.sp), color = Ember.Dim)
            }
        }
        if (showNow) {
            Box(
                Modifier
                    .offset(x = channelColumn + nowOffset - 22.dp, y = 7.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ember.Accent)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(formatClock(now), style = Txt.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = Color.Black)
            }
        }
    }
}

@Composable
private fun HeaderChip(
    text: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    FocusBox(
        modifier = modifier.height(28.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.06f,
        color = Color(0x1FFFFFFF),
        focusedColor = Color.White,
        onFocusChange = { if (it) onFocus() },
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxHeight().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = Txt.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = if (focused) Color.Black else if (accent) Ember.Accent else Ember.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" ▾", style = Txt.Caption.copy(fontSize = 10.sp), color = if (focused) Color.Black else Ember.Dim)
        }
    }
}

@Composable
internal fun GuideRow(
    repo: Repository,
    channel: Channel,
    rowHeight: Dp,
    compact: Boolean,
    wantFull: Boolean,
    windowStart: Long,
    windowEnd: Long,
    dpPerMinute: Dp,
    gridWidth: Dp,
    now: Long,
    onFocus: (Programme?) -> Unit,
    onPick: (Programme?) -> Unit,
    onLongPick: (Programme?) -> Unit,
    autoFocusLive: Boolean = false,
    channelColumn: Dp = CHANNEL_COLUMN,
) {
    // Once a row has needed the whole guide it keeps it, so scrolling back doesn't reload it.
    var full by remember { mutableStateOf(false) }
    if (wantFull) full = true
    val short = rememberProgrammes(repo, channel.streamId)
    val programmes = if (full) rememberFullProgrammes(repo, channel.streamId) ?: short else short
    Row(Modifier.fillMaxWidth().height(rowHeight)) {
        Row(
            Modifier
                .width(channelColumn)
                .fillMaxHeight()
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x0FFFFFFF))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (channel.num > 0) channel.num.toString() else "",
                style = Txt.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = Ember.Faint,
                maxLines = 1,
                modifier = Modifier.width(32.dp),
            )
            ChannelLogo(
                channel.icon,
                channel.name,
                if (compact) Modifier.size(width = 38.dp, height = 24.dp) else Modifier.size(width = 50.dp, height = 32.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                channel.name,
                style = Txt.Label.copy(fontSize = if (compact) 13.sp else 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            val visible = programmes.orEmpty().filter { it.end > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                ProgrammeBlock(
                    title = if (programmes == null) "Loading…" else "No guide information",
                    time = "",
                    live = true,
                    compact = compact,
                    focusKey = "guide:${channel.streamId}:none",
                    autoFocus = autoFocusLive,
                    modifier = Modifier.fillMaxSize(),
                    onFocus = { onFocus(null) },
                    onClick = { onPick(null) },
                )
            } else {
                for (p in visible) {
                    // Keyed by start time, so the highlighted programme keeps its highlight as the times move along.
                    key(p.start) {
                        val start = maxOf(p.start, windowStart)
                        val end = minOf(p.end, windowEnd)
                        val x = dpPerMinute * ((start - windowStart) / 60f)
                        val w = dpPerMinute * ((end - start) / 60f)
                        ProgrammeBlock(
                            title = p.title,
                            time = if (compact) "" else "${formatClock(p.start)} – ${formatClock(p.end)}",
                            live = p.isLive(now),
                            compact = compact,
                            past = p.end <= now,
                            catchup = p.end <= now && catchupAvailable(channel, p, now),
                            progress = if (p.isLive(now)) ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)) else null,
                            focusKey = "guide:${channel.streamId}:${p.start}",
                            autoFocus = autoFocusLive && p.isLive(now),
                            modifier = Modifier.offset(x = x).width(w.coerceAtLeast(6.dp)).fillMaxHeight(),
                            onFocus = { onFocus(p) },
                            onClick = { onPick(p) },
                            onLongClick = { onLongPick(p) },
                        )
                    }
                }
            }
            if (now in windowStart until windowEnd) {
                val nowOffset = dpPerMinute * ((now - windowStart) / 60f)
                Box(Modifier.offset(x = nowOffset).width(1.5.dp).fillMaxHeight().background(Ember.Accent.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    time: String,
    live: Boolean,
    compact: Boolean,
    focusKey: String,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    past: Boolean = false,
    catchup: Boolean = false,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    Box(modifier.padding(end = 2.dp)) {
        FocusBox(
            modifier = Modifier.fillMaxSize(),
            focusKey = focusKey,
            shape = RoundedCornerShape(if (compact) 7.dp else 9.dp),
            focusedScale = 1f,
            color = when {
                live -> Color(0xFF2C2C35)
                past -> Color(0xFF141418)
                else -> Color(0xFF1D1D23)
            },
            focusedColor = Color.White,
            autoFocus = autoFocus,
            onFocusChange = { if (it) onFocus() },
            onClick = onClick,
            onLongClick = onLongClick,
        ) { focused ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (catchup) {
                        androidx.compose.material3.Icon(
                            EmberIcons.Catchup,
                            contentDescription = "On catch-up",
                            tint = if (focused) Color.Black else Ember.Accent,
                            modifier = Modifier.size(if (compact) 12.dp else 14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        title,
                        style = Txt.Label.copy(fontSize = if (compact) 13.sp else 15.sp, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium),
                        color = when {
                            focused -> Color.Black
                            past && !catchup -> Ember.Faint
                            live -> Ember.Text
                            else -> Ember.Dim
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
            // How far through the programme on now is, along the bottom edge.
            if (progress != null && !focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(Ember.Accent.copy(alpha = 0.85f))
                )
            }
        }
    }
}

/** Choosing which channel group the guide shows. */
@Composable
private fun GroupPicker(categories: List<Category>, selectedId: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 500.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("Channel group", eyebrow = "TV guide")
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                Modifier.width(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(categories, key = { it.id }) { c ->
                    SheetOption(
                        c.name.removePrefix("★  "),
                        focusKey = "guide:pick:${c.id}",
                        autoFocus = c.id == selectedId,
                        trailing = if (c.id == selectedId) { dark -> Text("✓", style = Txt.Label, color = if (dark) Color.Black else Ember.Accent) } else null,
                    ) { onPick(c.id) }
                }
            }
        }
    }
}

/** Jumping the guide to another day, at the same time of day. */
@Composable
internal fun DayPicker(now: Long, showing: Long, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val today = startOfDay(now)
    val current = ((startOfDay(showing) - today) / 86_400L).toInt()
    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 500.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle("Go to day", eyebrow = "TV guide", subtitle = "Earlier days play from catch-up where your provider keeps it")
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                Modifier.width(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items((-7..7).toList(), key = { it }) { d ->
                    val label = when (d) {
                        0 -> "Today"
                        -1 -> "Yesterday"
                        1 -> "Tomorrow"
                        else -> formatDay(today + d * 86_400L + 43_200L, now)
                    }
                    SheetOption(
                        label,
                        focusKey = "guide:day:$d",
                        autoFocus = d == current,
                        trailing = if (d == current) { dark -> Text("✓", style = Txt.Label, color = if (dark) Color.Black else Ember.Accent) } else null,
                    ) { onPick(d) }
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
        // From the mini guide in a player: switch rather than stacking a second player.
        if (vm.stack.last() is Screen.PlayLive) vm.pop()
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

/** Where the playing channel sits while the guide is open over it (top right, like TiviMate). */
internal val PLAYER_GUIDE_VIDEO_WIDTH = 236.dp
internal val PLAYER_GUIDE_VIDEO_HEIGHT = 133.dp
internal val PLAYER_GUIDE_TOP = 18.dp
internal val PLAYER_GUIDE_SIDE = 40.dp

/**
 * The full guide over live TV, like TiviMate's: the channel you're watching keeps playing in the
 * top-right corner (the player draws it there), the highlighted programme's details sit beside it,
 * and the grid of channels and times fills the rest. OK on something airing switches to it.
 */
@Composable
fun PlayerGuide(vm: AppViewModel, channels: List<Channel>, current: Int, onZap: (Int) -> Unit, onClose: () -> Unit) {
    val repo = vm.repo ?: return
    val now = LocalNow.current
    var step by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<GuideFocus?>(null) }
    var details by remember { mutableStateOf<GuideFocus?>(null) }
    val windowStart = (now / 1800) * 1800 + step * 1800L
    val windowEnd = windowStart + WINDOW_MINUTES * 60
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    BackHandler(enabled = details == null) { if (step != 0) step = 0 else onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ember.Bg)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C130E), Color.Transparent), endY = 520f))
    ) {
        Column(Modifier.fillMaxSize().padding(start = PLAYER_GUIDE_SIDE, end = PLAYER_GUIDE_SIDE, top = PLAYER_GUIDE_TOP)) {
            Row(Modifier.fillMaxWidth().height(PLAYER_GUIDE_VIDEO_HEIGHT + 8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { GuideInfo(focus, now) }
                // The live picture goes here.
                Spacer(Modifier.width(PLAYER_GUIDE_VIDEO_WIDTH + 24.dp))
            }
            Spacer(Modifier.height(6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val gridWidth = maxWidth - CHANNEL_COLUMN
                val dpPerMinute = gridWidth / WINDOW_MINUTES
                Column(Modifier.fillMaxSize()) {
                    TimeHeader(windowStart, dpPerMinute, now, "", {}, {}, {}, showChips = false)
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                        modifier = Modifier
                            .fillMaxSize()
                            .onPreviewKeyEvent { ev -> guideKey(ev, focus?.programme, windowStart, windowEnd, step, onStep = { step = it.coerceIn(-MAX_STEPS, MAX_STEPS) }) },
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.streamId }) { i, ch ->
                            GuideRow(
                                repo = repo,
                                channel = ch,
                                rowHeight = COMPACT_ROW_HEIGHT,
                                compact = true,
                                wantFull = step != 0,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                dpPerMinute = dpPerMinute,
                                gridWidth = gridWidth,
                                now = now,
                                onFocus = { prog -> focus = GuideFocus(ch, prog) },
                                onPick = { prog ->
                                    if (prog == null || prog.isLive(now)) onZap(i) else details = GuideFocus(ch, prog)
                                },
                                onLongPick = { prog -> if (prog != null) details = GuideFocus(ch, prog) },
                                autoFocusLive = i == current,
                            )
                        }
                    }
                }
            }
        }
    }
    val d = details
    if (d != null) ProgrammeDialog(vm, d, channels) { details = null }
}
