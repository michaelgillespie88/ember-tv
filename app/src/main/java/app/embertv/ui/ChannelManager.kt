package app.embertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.Channel
import app.embertv.data.ContentType
import kotlinx.coroutines.launch

/**
 * Settings → Channels: put favourite channels in your own order, and hide channels you never
 * watch from the lists, the guide and channel up/down. Everything is per profile.
 */
@Composable
fun ChannelManager(vm: AppViewModel, onDone: () -> Unit) {
    val repo = vm.repo ?: return
    val isTv = LocalIsTv.current
    var hideMode by remember { mutableStateOf(false) }

    fun close() {
        vm.channelsEdited()
        onDone()
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF1E1410), Ember.Bg), radius = 1600f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 28.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTv) {
                        IconCircleButton(EmberIcons.Back, "Done", glass = true, onClick = { close() })
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "CHANNELS",
                            style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp),
                            color = Ember.Text,
                        )
                        Text(if (hideMode) "Hide channels" else "Favourites order", style = if (isTv) Txt.Title else Txt.Headline)
                        Text(
                            if (hideMode) {
                                "Untick channels you never watch. They leave the channel list, the guide and channel up/down on this profile."
                            } else if (isTv) {
                                "Select a channel to pick it up, move it with up and down, then select again to put it down. Hold to remove it."
                            } else {
                                "Use the arrows to move channels. This is the order of Favourites in Live TV, the guide and channel up/down."
                            },
                            style = Txt.Caption,
                            color = Ember.Dim,
                            maxLines = 2,
                        )
                    }
                    if (isTv) {
                        Spacer(Modifier.width(20.dp))
                        TvButton("Done", icon = "✓", onClick = { close() })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManagerChip("Favourites order", !hideMode, "chan:mode:fav", autoFocus = isTv) { hideMode = false }
                    ManagerChip("Hide channels", hideMode, "chan:mode:hide") { hideMode = true }
                }
                Spacer(Modifier.height(14.dp))
                if (hideMode) HideChannels(vm, repo) else FavouriteOrder(vm)
            }
        }
    }
}

@Composable
private fun FavouriteOrder(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    val favs = vm.favourites.filter { it.type == ContentType.LIVE }
    if (favs.isEmpty()) {
        EmptyView("${holdPrompt()} any channel in Live TV to add it to your favourites, then come back here to put them in order.")
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<String?>(null) }

    fun move(key: String, by: Int) {
        if (!vm.moveFavouriteChannel(key, by)) return
        val idx = vm.favourites.filter { it.type == ContentType.LIVE }.indexOfFirst { it.key == key }
        // Keep the channel being moved on screen.
        val first = state.firstVisibleItemIndex
        val shown = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        if (idx <= first || idx >= first + shown - 1) {
            scope.launch { state.animateScrollToItem((idx - shown / 2).coerceAtLeast(0)) }
        }
    }

    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 6.dp),
    ) {
        itemsIndexed(favs, key = { _, f -> f.key }) { i, f ->
            val isMoving = moving == f.key
            val shape = RoundedCornerShape(14.dp)
            FocusBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTv) 56.dp else 60.dp)
                    .then(if (isMoving) Modifier.border(2.dp, Ember.Text, shape) else Modifier)
                    .onPreviewKeyEvent { ev ->
                        if (!isMoving || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                move(f.key, -1)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                move(f.key, 1)
                                true
                            }
                            // Stay on the row while carrying it.
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                            else -> false
                        }
                    },
                focusKey = "chan:fav:${f.key}",
                shape = shape,
                focusedScale = if (isMoving) 1.05f else 1.03f,
                color = if (isMoving) Color(0x40FF7A3D) else Color(0x14FFFFFF),
                focusedColor = Color.White,
                onFocusChange = { if (!it && moving == f.key) moving = null },
                onClick = { if (isTv) moving = if (isMoving) null else f.key },
                onLongClick = {
                    moving = null
                    vm.removeFavourite(f.key)
                    vm.notify("Removed ${f.name} from favourites")
                },
            ) { focused ->
                val fg = if (focused) Color.Black else Ember.Text
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}",
                        style = Txt.Label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (focused) Color(0xFF555559) else Ember.Faint,
                        modifier = Modifier.width(32.dp),
                    )
                    ChannelLogo(f.image, f.name, Modifier.size(width = 52.dp, height = 32.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(f.name, style = Txt.Label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (isTv) {
                        if (isMoving) {
                            val tint = if (focused) Color.Black else Ember.Text
                            androidx.compose.material3.Icon(EmberIcons.ChevronUp, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                            androidx.compose.material3.Icon(EmberIcons.ChevronDown, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Moving", style = Txt.Caption.copy(fontWeight = FontWeight.Bold), color = tint)
                        }
                    } else {
                        // Phones: arrows to move, and a star to remove.
                        IconCircleButton(EmberIcons.ChevronUp, "Move up", size = 38.dp) { move(f.key, -1) }
                        IconCircleButton(EmberIcons.ChevronDown, "Move down", size = 38.dp) { move(f.key, 1) }
                        IconCircleButton(EmberIcons.Close, "Remove", size = 38.dp, tint = Ember.Dim) {
                            vm.removeFavourite(f.key)
                            vm.notify("Removed ${f.name} from favourites")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HideChannels(vm: AppViewModel, repo: app.embertv.data.Repository) {
    val isTv = LocalIsTv.current
    val cats by rememberLoad(repo, vm.reloadToken) { repo.liveCategories() }
    val groups = (cats as? LoadResult.Ok)?.value.orEmpty()
    var group by remember { mutableStateOf<String?>(null) }
    val groupId = group ?: groups.firstOrNull()?.id ?: ALL
    val load by rememberLoad(repo, groupId) { repo.channelsIncludingHidden(groupId) }
    val channels: List<Channel> = (load as? LoadResult.Ok)?.value.orEmpty()
    val hidden = vm.hiddenChannels

    Column(Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(listOf(Category(ALL, "All channels")) + groups, key = { it.id }) { c ->
                ManagerChip(c.name, c.id == groupId, "chan:group:${c.id}", small = true) { group = c.id }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = channels.count { it.streamId !in hidden }
            Text(
                if (load is LoadResult.Ok) "$shown of ${channels.size} shown  ·  ${hidden.size} hidden in total" else " ",
                style = Txt.Caption,
                color = Ember.Faint,
                modifier = Modifier.weight(1f),
            )
            if (channels.isNotEmpty()) {
                ManagerChip("Show all", false, "chan:showall", small = true) { vm.setChannelsHidden(channels.map { it.streamId }, false) }
                Spacer(Modifier.width(8.dp))
                ManagerChip("Hide all", false, "chan:hideall", small = true) { vm.setChannelsHidden(channels.map { it.streamId }, true) }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (val r = load) {
            is LoadResult.Loading -> LoadingView(Modifier.weight(1f))
            is LoadResult.Err -> ErrorView(r.message, Modifier.weight(1f)) { vm.reload() }
            is LoadResult.Ok -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (isTv) 3 else 1),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 8.dp),
            ) {
                items(r.value, key = { it.streamId }) { ch ->
                    val on = ch.streamId !in hidden
                    ChannelToggle(ch, on) { vm.setChannelsHidden(listOf(ch.streamId), on) }
                }
            }
        }
    }
}

@Composable
private fun ChannelToggle(ch: Channel, on: Boolean, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        focusKey = "chan:hide:${ch.streamId}",
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = Color(0x14FFFFFF),
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.icon, ch.name, Modifier.size(width = 44.dp, height = 28.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                ch.name,
                style = Txt.Label.copy(fontSize = 14.sp),
                color = when {
                    focused -> Color.Black
                    on -> Ember.Text
                    else -> Ember.Faint
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (on) Ember.Text else Color.Transparent)
                    .then(if (on) Modifier else Modifier.border(1.5.dp, if (focused) Color(0xFF88888F) else Ember.Faint, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(EmberIcons.Check, contentDescription = "Shown", tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ManagerChip(text: String, selected: Boolean, focusKey: String, autoFocus: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.height(if (small) 34.dp else 38.dp),
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
                style = Txt.Label.copy(fontSize = if (small) 13.sp else 14.sp),
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
