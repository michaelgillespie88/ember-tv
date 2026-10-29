package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.ALL
import app.embertv.data.Category
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.FAVOURITES
import app.embertv.data.Programme
import app.embertv.data.Repository
import app.embertv.data.favouriteKey
import app.embertv.data.toChannel
import kotlinx.coroutines.delay

@Composable
fun LiveSection(vm: AppViewModel) {
    val repo = vm.repo ?: return
    val section = vm.live
    val cats by rememberLoad(repo, vm.reloadToken) { repo.liveCategories() }
    when (val c = cats) {
        is LoadResult.Loading -> LoadingView(text = "Loading channels…")
        is LoadResult.Err -> ErrorView(c.message) { vm.reload() }
        is LoadResult.Ok -> {
            val categories = remember(c.value) {
                listOf(Category(FAVOURITES, "★  Favourites"), Category(ALL, "All channels")) + c.value
            }
            val selectedId = section.categoryId ?: c.value.firstOrNull()?.id ?: ALL
            val select = rememberCategorySelector(section)
            var focusedChannel by remember { mutableStateOf<Channel?>(null) }
            Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 48.dp, top = 6.dp)) {
                CategoryRail(
                    categories = categories,
                    selectedId = selectedId,
                    state = section.rail,
                    keyPrefix = "live",
                    onFocusCategory = { select(it.id, false) },
                    onSelectCategory = { select(it.id, true) },
                )
                Spacer(Modifier.width(20.dp))
                ChannelList(
                    vm = vm,
                    repo = repo,
                    categoryId = selectedId,
                    modifier = Modifier.weight(1f),
                    onFocusChannel = { focusedChannel = it },
                )
                Spacer(Modifier.width(24.dp))
                ChannelPreview(repo, focusedChannel, Modifier.width(300.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ChannelList(
    vm: AppViewModel,
    repo: Repository,
    categoryId: String,
    modifier: Modifier,
    onFocusChannel: (Channel) -> Unit,
) {
    val favSnapshot = if (categoryId == FAVOURITES) vm.favourites.filter { it.type == ContentType.LIVE } else null
    val load by rememberLoad(repo, categoryId, vm.reloadToken, favSnapshot) {
        favSnapshot?.map { it.toChannel() } ?: repo.channels(categoryId)
    }
    when (val r = load) {
        is LoadResult.Loading -> LoadingView(modifier)
        is LoadResult.Err -> ErrorView(r.message, modifier) { vm.reload() }
        is LoadResult.Ok -> {
            val channels = r.value
            if (channels.isEmpty()) {
                EmptyView(
                    if (categoryId == FAVOURITES) "Hold OK on any channel to add it to your favourites."
                    else "No channels in this category.",
                    modifier,
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxHeight(),
                    state = vm.live.list(categoryId),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = 6.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                        ChannelRow(
                            repo = repo,
                            channel = ch,
                            focusKey = "live:$categoryId:${ch.streamId}",
                            isFavourite = vm.favKeys.contains(favouriteKey(ContentType.LIVE, ch.streamId)),
                            onFocused = { onFocusChannel(ch) },
                            onClick = { vm.push(Screen.PlayLive(channels, index)) },
                            onLongClick = { vm.toggleFavourite(vm.favouriteOf(ch)) },
                        )
                    }
                }
            }
        }
    }
}

/** Loads now/next for a channel, after a short pause so fast scrolling doesn't flood the server. */
@Composable
fun rememberProgrammes(repo: Repository, streamId: Int): List<Programme>? {
    val programmes by produceState(repo.cachedEpg(streamId), repo, streamId) {
        if (value == null) {
            delay(250)
            value = repo.epg(streamId)
        }
    }
    return programmes
}

@Composable
fun ChannelRow(
    repo: Repository,
    channel: Channel,
    focusKey: String,
    isFavourite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val now = LocalNow.current
    val programmes = rememberProgrammes(repo, channel.streamId)
    val current = programmes?.firstOrNull { it.isLive(now) }
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(70.dp),
        focusKey = focusKey,
        focusedScale = 1.03f,
        color = Color(0x0DFFFFFF),
        onFocusChange = { if (it) onFocused() },
        onClick = onClick,
        onLongClick = onLongClick,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (channel.num > 0) channel.num.toString() else "",
                style = Txt.Caption,
                color = Ember.Faint,
                modifier = Modifier.width(38.dp),
            )
            ChannelLogo(channel.icon, channel.name, Modifier.size(width = 62.dp, height = 42.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = Txt.Headline.copy(fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(current.title, style = Txt.Caption, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    ProgressBar(current.progress(now), Modifier.width(140.dp))
                }
            }
            if (isFavourite) {
                Spacer(Modifier.width(8.dp))
                Text("★", color = Ember.Accent, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ChannelPreview(repo: Repository, channel: Channel?, modifier: Modifier) {
    Column(
        modifier
            .padding(top = 8.dp, bottom = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Ember.Panel)
            .padding(22.dp)
    ) {
        if (channel == null) {
            Text("Pick a channel", style = Txt.Headline)
            Spacer(Modifier.height(8.dp))
            Text("Press OK to watch. Hold OK to add it to your favourites.", style = Txt.Body, color = Ember.Dim)
        } else {
            PreviewDetails(repo, channel)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth()) {
            Text("OK  Watch     Hold OK  Favourite", style = Txt.Caption, color = Ember.Faint)
        }
    }
}

@Composable
private fun PreviewDetails(repo: Repository, channel: Channel) {
    val now = LocalNow.current
    val programmes = rememberProgrammes(repo, channel.streamId)
    ChannelLogo(channel.icon, channel.name, Modifier.fillMaxWidth().height(110.dp))
    Spacer(Modifier.height(16.dp))
    Text(channel.name, style = Txt.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(16.dp))
    when {
        programmes == null -> Text("Loading guide…", style = Txt.Body, color = Ember.Faint)
        programmes.none { it.end > now } -> Text("No guide information for this channel.", style = Txt.Body, color = Ember.Faint)
        else -> {
            val upcoming = programmes.filter { it.end > now }.take(3)
            upcoming.forEachIndexed { i, p ->
                val live = p.isLive(now)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (live) "NOW" else formatClock(p.start),
                        style = Txt.Caption,
                        color = if (live) Ember.Accent else Ember.Faint,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        p.title,
                        style = if (live) Txt.Label else Txt.Body,
                        color = if (live) Ember.Text else Ember.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (live) {
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(p.progress(now), Modifier.fillMaxWidth().padding(start = 48.dp))
                    if (p.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            p.description,
                            style = Txt.Caption.copy(fontSize = 13.sp),
                            color = Ember.Dim,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                }
                if (i < upcoming.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}
