package app.embertv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.embertv.data.Channel

/**
 * Two or four channels at once. Sound comes from the highlighted one. OK (or a tap) opens it full
 * screen; holding OK (or a long press) swaps it for another channel. Each picture uses one of the
 * provider's connections and one of the device's video decoders.
 */
@Composable
fun MultiViewScreen(vm: AppViewModel, start: List<Channel>, pool: List<Channel>) {
    PhoneFullscreen()
    val isTv = LocalIsTv.current
    var four by remember { mutableStateOf(start.size > 2) }
    val slots = remember {
        mutableStateListOf<Channel?>().apply {
            addAll(start.take(4))
            while (size < 4) add(null)
        }
    }
    var audio by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val count = if (four) 4 else 2

    fun fullScreen(i: Int) {
        val chosen = slots[i] ?: return
        val list = slots.take(count).filterNotNull()
        vm.pop()
        vm.push(Screen.PlayLive(list, list.indexOf(chosen).coerceAtLeast(0)))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(if (isTv) 24.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            val rows = if (four) listOf(0 to 1, 2 to 3) else listOf(0 to 1)
            for ((a, b) in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in listOf(a, b)) {
                        Box(Modifier.weight(1f).aspectRatio(16f / 9f)) {
                            MultiTile(
                                vm = vm,
                                channel = slots[i],
                                index = i,
                                hasAudio = audio == i,
                                autoFocus = isTv && i == 0,
                                onFocus = { audio = i },
                                onOpen = { if (slots[i] == null) picking = i else fullScreen(i) },
                                onChange = { picking = i },
                            )
                        }
                    }
                }
            }
            Text(
                if (isTv) "OK  Full screen   ·   Hold OK  Change channel   ·   Back  Leave multi-view"
                else "Tap a picture for full screen · press and hold to change it",
                style = Txt.Caption,
                color = Ember.Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    picking?.let { slot ->
        SlotPicker(
            vm = vm,
            pool = pool,
            current = slots[slot],
            four = four,
            onPick = { c ->
                slots[slot] = c
                audio = slot
                picking = null
            },
            onRemove = {
                slots[slot] = null
                picking = null
            },
            onToggleLayout = {
                four = !four
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun MultiTile(
    vm: AppViewModel,
    channel: Channel?,
    index: Int,
    hasAudio: Boolean,
    autoFocus: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onChange: () -> Unit,
) {
    FocusBox(
        modifier = Modifier.fillMaxSize(),
        focusKey = "multi:$index",
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1f,
        color = Color(0xFF111116),
        focusedColor = Color(0xFF111116),
        autoFocus = autoFocus,
        onFocusChange = { if (it) onFocus() },
        onClick = onOpen,
        onLongClick = onChange,
    ) { _ ->
        if (channel == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("+", style = Txt.Hero, color = Ember.Dim)
                Text("Add a channel", style = Txt.Label, color = Ember.Dim)
            }
        } else {
            TilePlayer(vm, channel, hasAudio)
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .glassSurface(RoundedCornerShape(10.dp), Color(0x8C000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasAudio) {
                    Icon(EmberIcons.Live, contentDescription = "Sound on", tint = Ember.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(channel.name, style = Txt.Caption, color = Ember.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TilePlayer(vm: AppViewModel, channel: Channel, hasAudio: Boolean) {
    val context = LocalContext.current
    val userAgent = vm.userAgent
    val exo = remember(channel.streamId, userAgent) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_000, 2_000).build())
            .build()
    }
    var problem by remember(channel.streamId) { mutableStateOf<String?>(null) }
    LaunchedEffect(hasAudio, exo) { exo.volume = if (hasAudio) 1f else 0f }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                problem = if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    "This device can't show another picture at once"
                } else {
                    "Couldn't play this channel here"
                }
            }
        }
        exo.addListener(listener)
        exo.setMediaItem(MediaItem.fromUri(vm.liveUrl(channel, false)))
        exo.prepare()
        exo.playWhenReady = true
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, exo) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exo.stop()
                Lifecycle.Event.ON_START -> if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = exo
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    val p = problem
    if (p != null) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Text(p, style = Txt.Label, color = Ember.Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Choosing what goes in one of the pictures, Apple TV style. */
@Composable
private fun SlotPicker(
    vm: AppViewModel,
    pool: List<Channel>,
    current: Channel?,
    four: Boolean,
    onPick: (Channel) -> Unit,
    onRemove: () -> Unit,
    onToggleLayout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTv = LocalIsTv.current
    Dialog(onDismissRequest = onDismiss) {
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
            SheetTitle("Choose a channel", eyebrow = "Multi-view")
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                item(key = "layout") {
                    SheetOption(if (four) "Show 2 at once" else "Show 4 at once", focusKey = "multi:layout", onClick = onToggleLayout)
                }
                if (current != null) {
                    item(key = "remove") { SheetOption("Remove this picture", focusKey = "multi:remove", onClick = onRemove) }
                }
                itemsIndexed(pool.distinctBy { it.streamId }.take(300), key = { _, c -> c.streamId }) { i, c ->
                    SheetOption(
                        title = c.name,
                        focusKey = "multi:pick:${c.streamId}",
                        autoFocus = isTv && i == 0,
                        leading = { ChannelLogo(c.icon, c.name, Modifier.size(width = 48.dp, height = 32.dp)) },
                        trailing = if (c.streamId == current?.streamId) { dark -> Text("✓", style = Txt.Label, color = if (dark) Color.Black else Ember.Accent) } else null,
                    ) { onPick(c) }
                }
            }
        }
    }
}
