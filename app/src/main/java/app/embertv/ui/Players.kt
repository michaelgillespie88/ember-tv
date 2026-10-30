package app.embertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.embertv.data.Channel
import androidx.compose.runtime.produceState
import app.embertv.data.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------- Shared player plumbing ----------------

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** On phones, play sideways and full screen; put things back when playback ends. */
@Composable
private fun PhoneFullscreen() {
    if (LocalIsTv.current) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private val ControlBg = Color(0x47000000)

@Composable
private fun rememberExoPlayer(
    userAgent: String,
    audioLanguage: String = "",
    subtitleMode: String = "off",
    onStop: (ExoPlayer) -> Unit,
): ExoPlayer {
    val context = LocalContext.current
    val exo = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                // The profile's preferred audio language and subtitle choice.
                val params = player.trackSelectionParameters.buildUpon()
                if (audioLanguage.isNotEmpty()) params.setPreferredAudioLanguage(audioLanguage)
                if (subtitleMode == "off") {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(subtitleMode)
                }
                player.trackSelectionParameters = params.build()
            }
    }
    val latestOnStop by rememberUpdatedState(onStop)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Stop (not just pause) in the background so we don't hold one of the provider's connection slots.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                latestOnStop(exo)
                exo.stop()
            } else if (event == Lifecycle.Event.ON_START) {
                if (exo.mediaItemCount > 0 && exo.playbackState == Player.STATE_IDLE) exo.prepare()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    return exo
}

@Composable
private fun VideoSurface(exo: ExoPlayer) {
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
}

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "The provider refused the stream. It may be offline, or all your connections are in use."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Couldn't reach the stream. Check the TV's internet connection."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
        "This stream's format isn't supported. Try switching the stream format in Settings."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "This TV can't decode this stream's video or audio."
    else -> "Playback failed (${e.errorCodeName})."
}

private fun isSelectKey(code: Int) =
    code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

@Composable
private fun BoxScope.Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center).size(44.dp),
        color = Color.White,
        strokeWidth = 3.dp,
    )
}

@Composable
private fun BoxScope.ErrorCard(message: String, hint: String) {
    Column(
        Modifier
            .align(Alignment.Center)
            .width(520.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6141418))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't play this", style = Txt.Headline)
        Spacer(Modifier.height(8.dp))
        Text(message, style = Txt.Body, color = Ember.Dim)
        Spacer(Modifier.height(12.dp))
        Text(hint, style = Txt.Caption, color = Ember.Faint)
    }
}

// ---------------- Live TV ----------------

@Composable
fun LivePlayerScreen(vm: AppViewModel, channels: List<Channel>, startIndex: Int) {
    if (vm.repo == null || channels.isEmpty()) return
    val isTv = LocalIsTv.current
    PhoneFullscreen()
    val exo = rememberExoPlayer(vm.userAgent, vm.audioLanguage, vm.subtitleMode) { }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var scoresOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var alternate by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var firstLoad by remember { mutableStateOf(true) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    var listOpen by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }

    val channel = channels[index]
    // So coming back from catch-up returns to this channel, not the one the player opened on.
    LaunchedEffect(index) { vm.noteLiveIndex(index) }

    fun showOverlay() {
        overlayVisible = true
        overlayToken++
    }

    fun zapTo(i: Int) {
        if (i == index) return
        previousIndex = index
        index = i
        alternate = false
        retries = 0
        error = null
        showOverlay()
    }

    fun zap(delta: Int) {
        val n = channels.size
        zapTo(((index + delta) % n + n) % n)
    }

    fun previousChannel() {
        val p = previousIndex
        if (p == null || p !in channels.indices) vm.notify("No previous channel yet") else zapTo(p)
    }

    // Count a channel as watched once it has been on for a few seconds.
    LaunchedEffect(index) {
        delay(5_000)
        vm.recordChannel(channels[index])
    }

    LaunchedEffect(overlayToken) {
        delay(5_000)
        overlayVisible = false
    }

    // Debounced so holding up/down flicks through channels without starting every stream.
    LaunchedEffect(index, alternate) {
        if (!firstLoad) delay(350)
        firstLoad = false
        val url = vm.liveUrl(channel, alternate)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId("live:${channel.streamId}")
            .setMimeType(if (url.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                when {
                    retries < 2 -> {
                        retries++
                        scope.launch {
                            delay(1_500)
                            exo.prepare()
                        }
                    }
                    !alternate -> {
                        // Try the other container (TS <-> HLS) before giving up.
                        alternate = true
                        retries = 0
                    }
                    else -> error = describe(e)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) error = null
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_600)
        val wanted = digits.toIntOrNull()
        digits = ""
        val i = channels.indexOfFirst { it.num == wanted }
        if (i >= 0) zapTo(i) else vm.notify("Channel $wanted isn't in this list")
    }

    BackHandler(enabled = listOpen) { listOpen = false }
    BackHandler(enabled = scoresOpen) { scoresOpen = false }

    LaunchedEffect(listOpen, scoresOpen) {
        if (!listOpen && !scoresOpen) {
            delay(60)
            runCatching { focus.requestFocus() }
        }
    }

    fun onKey(ev: KeyEvent): Boolean {
        if (listOpen || scoresOpen) return false
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        if (ev.type == KeyEventType.KeyUp) {
            if (isSelectKey(code)) {
                if (okDown && !okLong) {
                    if (overlayVisible) overlayVisible = false else showOverlay()
                }
                okDown = false
                okLong = false
                return true
            }
            return false
        }
        if (ev.type != KeyEventType.KeyDown) return false
        val up = if (vm.invertZap) -1 else 1
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> zap(up)
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-up)
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> listOpen = true
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MENU -> scoresOpen = true
            AndroidKeyEvent.KEYCODE_INFO -> showOverlay()
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> previousChannel()
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (native.repeatCount == 0) {
                    okDown = true
                    okLong = false
                } else if (okDown && !okLong) {
                    okLong = true
                    vm.toggleFavourite(vm.favouriteOf(channel))
                    showOverlay()
                }
            }
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                digits = (digits + (code - AndroidKeyEvent.KEYCODE_0).toString()).takeLast(4)
            }
            else -> return false
        }
        return true
    }

    val upStep = if (vm.invertZap) -1 else 1
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { onKey(it) }
            .focusable()
            // Touch: tap shows or hides the info, swiping up or down changes channel.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (overlayVisible) overlayVisible = false else showOverlay() })
            }
            .pointerInput(Unit) {
                var travelled = 0f
                val threshold = 64.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        val step = if (vm.invertZap) -1 else 1
                        if (travelled < -threshold) zap(step) else if (travelled > threshold) zap(-step)
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        travelled += dy
                    },
                )
            }
    ) {
        VideoSurface(exo)

        val err = error
        if (err != null) {
            ErrorCard(
                err,
                if (isTv) "Press ▲ or ▼ to try another channel, or Back to leave." else "Swipe up or down to try another channel.",
            )
        } else if (buffering) {
            Spinner()
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible && !listOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LiveInfoBar(
                vm = vm,
                channel = channel,
                isFavourite = vm.isFavourite(ContentType.LIVE, channel.streamId),
                format = if (alternate) (if (vm.streamFormat == "m3u8") "TS" else "HLS") else (if (vm.streamFormat == "m3u8") "HLS" else "TS"),
                isTv = isTv,
            )
        }

        val sleepLeft = vm.sleepMinutesLeft(LocalNow.current * 1000)
        if (sleepLeft != null && overlayVisible) {
            SleepBadge(sleepLeft, Modifier.align(Alignment.TopEnd).padding(top = if (isTv) 30.dp else 76.dp, end = if (isTv) 40.dp else 24.dp))
        }

        if (!isTv) {
            AnimatedVisibility(
                visible = overlayVisible && !listOpen && !scoresOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                val fav = vm.isFavourite(ContentType.LIVE, channel.streamId)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconCircleButton(EmberIcons.Back, "Back", background = ControlBg, glass = true) { vm.pop() }
                    Spacer(Modifier.weight(1f))
                    IconCircleButton(EmberIcons.Football, "Live scores", background = ControlBg, glass = true) { scoresOpen = true }
                    IconCircleButton(EmberIcons.Restart, "Previous channel", background = ControlBg, glass = true) { previousChannel() }
                    IconCircleButton(EmberIcons.ChevronUp, "Channel up", background = ControlBg, glass = true) { zap(upStep) }
                    IconCircleButton(EmberIcons.ChevronDown, "Channel down", background = ControlBg, glass = true) { zap(-upStep) }
                    IconCircleButton(EmberIcons.ChannelList, "Channel list", background = ControlBg, glass = true) { listOpen = true }
                    IconCircleButton(
                        if (fav) EmberIcons.StarFilled else EmberIcons.Star,
                        "Favourite",
                        background = ControlBg, glass = true,
                        tint = if (fav) Ember.Accent else Ember.Text,
                    ) {
                        vm.toggleFavourite(vm.favouriteOf(channel))
                        showOverlay()
                    }
                    IconCircleButton(EmberIcons.More, "More options", background = ControlBg, glass = true) { optionsOpen = true }
                }
            }
        }

        if (listOpen) {
            if (!isTv) {
                // Tapping beside the list closes it.
                Box(Modifier.fillMaxSize().clickable { listOpen = false })
            }
            ZapList(
                vm = vm,
                channels = channels,
                current = index,
                hasPrevious = previousIndex != null,
                onClose = { listOpen = false },
                onPrevious = {
                    listOpen = false
                    previousChannel()
                },
                onPick = { i ->
                    listOpen = false
                    zapTo(i)
                },
            )
        }

        if (scoresOpen) {
            if (!isTv) Box(Modifier.fillMaxSize().clickable { scoresOpen = false })
            val now = LocalNow.current
            val title = rememberProgrammes(vm.repo!!, channel.streamId)?.firstOrNull { it.isLive(now) }?.title
            ScoresPanel(programmeTitle = listOfNotNull(title, channel.name).joinToString(" "), onClose = { scoresOpen = false })
        }
    }

    if (optionsOpen) {
        LiveOptionsDialog(vm, channel) { optionsOpen = false }
    }
}

@Composable
private fun LiveInfoBar(vm: AppViewModel, channel: Channel, isFavourite: Boolean, format: String, isTv: Boolean) {
    val repo = vm.repo ?: return
    val now = LocalNow.current
    val programmes = rememberProgrammes(repo, channel.streamId)
    val current = programmes?.firstOrNull { it.isLive(now) }
    val next = programmes?.firstOrNull { it.start >= (current?.end ?: now) }

    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(
                start = if (isTv) 56.dp else 24.dp,
                end = if (isTv) 56.dp else 24.dp,
                top = if (isTv) 60.dp else 36.dp,
                bottom = if (isTv) 36.dp else 16.dp,
            )
    ) {
        Column {
            Text(
                if (isTv) "▲▼ Channels    ◀ Channel list    ▶ Live scores    Hold OK Favourite" else "Swipe up or down to change channel",
                style = Txt.Caption,
                color = Ember.Faint,
            )
            Spacer(Modifier.height(if (isTv) 14.dp else 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    channel.icon,
                    channel.name,
                    if (isTv) Modifier.size(width = 120.dp, height = 80.dp) else Modifier.size(width = 84.dp, height = 56.dp),
                )
                Spacer(Modifier.width(if (isTv) 24.dp else 16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (channel.num > 0) {
                            Text("${channel.num}", style = Txt.Headline, color = Ember.Dim)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(channel.name, style = Txt.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isFavourite) {
                            Spacer(Modifier.width(10.dp))
                            Text("★", color = Ember.Accent, fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (current != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveTag()
                            Spacer(Modifier.width(10.dp))
                            Text(current.title, style = Txt.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                            Text("${formatClock(current.start)} – ${formatClock(current.end)}", style = Txt.Label, color = Ember.Dim)
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(current.progress(now), Modifier.width(if (isTv) 420.dp else 280.dp))
                    } else {
                        Text(
                            if (programmes == null) "Loading guide…" else "No guide information",
                            style = Txt.Label,
                            color = Ember.Dim,
                        )
                    }
                    if (next != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Next  ${formatClock(next.start)}  ${next.title}", style = Txt.Label, color = Ember.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatClock(now), style = Txt.Title)
                    Spacer(Modifier.height(6.dp))
                    Text(format, style = Txt.Caption, color = Ember.Faint)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ZapList(
    vm: AppViewModel,
    channels: List<Channel>,
    current: Int,
    hasPrevious: Boolean,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .width(430.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF20B0B0E), Color(0xCC0B0B0E))))
    ) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(vertical = 32.dp, horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "zap-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    if (hasPrevious) ZapAction(EmberIcons.Restart, "Previous channel", "", onPrevious)
                    CatchupActions(vm, channels[current], onClose)
                    val left = vm.sleepMinutesLeft()
                    ZapAction(EmberIcons.Moon, "Sleep timer", if (left == null) "Off" else "$left min") { vm.cycleSleepTimer() }
                    ZapAction(EmberIcons.Subtitles, "Subtitles", if (vm.subtitleMode == "off") "Off" else "English") { vm.cycleSubtitles() }
                }
            }
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                FocusBox(
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.03f,
                    color = if (i == current) Color(0x26FFFFFF) else Color.Transparent,
                    focusedColor = Color.White,
                    autoFocus = i == current,
                    onClick = { onPick(i) },
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (c.num > 0) "${c.num}" else "",
                            style = Txt.Caption,
                            color = if (focused) Color(0xFF55555C) else Ember.Faint,
                            modifier = Modifier.width(40.dp),
                        )
                        ChannelLogo(c.icon, c.name, Modifier.size(width = 54.dp, height = 36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            c.name,
                            style = Txt.Label,
                            color = if (focused) Color.Black else Ember.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** "Watch from the start" and "Catch-up TV", for channels whose provider records them. */
@Composable
private fun CatchupActions(vm: AppViewModel, channel: Channel, onDone: () -> Unit) {
    val repo = vm.repo ?: return
    val resolved by produceState(channel, repo, channel.streamId) { value = repo.resolve(channel) }
    if (!resolved.hasArchive) return
    val now = LocalNow.current
    val current = rememberProgrammes(repo, channel.streamId)?.firstOrNull { it.isLive(now) }
    if (current != null) {
        ZapAction(EmberIcons.FromStart, "Watch from the start", "") {
            onDone()
            vm.playCatchup(resolved, current)
        }
    }
    ZapAction(EmberIcons.Catchup, "Catch-up TV", "") {
        onDone()
        vm.push(Screen.Catchup(resolved))
    }
}

@Composable
private fun ZapAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = Color(0x14FFFFFF),
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (focused) Color.Black else Ember.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = Txt.Label, color = if (focused) Color.Black else Ember.Text, modifier = Modifier.weight(1f))
            if (value.isNotEmpty()) Text(value, style = Txt.Label, color = if (focused) Color(0xFF444448) else Ember.Dim)
        }
    }
}

@Composable
private fun SleepBadge(minutes: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.glassSurface(RoundedCornerShape(16.dp), Color(0x59000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(EmberIcons.Moon, contentDescription = null, tint = Ember.Text, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Sleep in $minutes min", style = Txt.Caption, color = Ember.Text)
    }
}

/** Phone "more" sheet for the live player: sleep timer, audio and subtitles. */
@Composable
private fun LiveOptionsDialog(vm: AppViewModel, channel: Channel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(dialogPanelColor())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Options", style = Txt.Headline, modifier = Modifier.padding(bottom = 6.dp))
            CatchupActions(vm, channel, onDismiss)
            val left = vm.sleepMinutesLeft()
            ZapAction(EmberIcons.Moon, "Sleep timer", if (left == null) "Off" else "$left min") { vm.cycleSleepTimer() }
            ZapAction(EmberIcons.Subtitles, "Subtitles", if (vm.subtitleMode == "off") "Off" else "English") { vm.cycleSubtitles() }
            ZapAction(EmberIcons.Series, "Preferred audio", if (vm.audioLanguage.isEmpty()) "Default" else "English") { vm.cycleAudioLanguage() }
            Text(
                "Subtitle and audio changes apply from the next channel or video.",
                style = Txt.Caption,
                color = Ember.Faint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------- Movies & episodes ----------------

private class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

private fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.UK)
    return if (name.isNullOrBlank() || name == code) code.uppercase() else name
}

private fun trackOptions(exo: ExoPlayer, type: Int): List<TrackOption> {
    val out = ArrayList<TrackOption>()
    for (group in exo.currentTracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val f = group.getTrackFormat(i)
            val parts = listOfNotNull(
                f.label?.takeIf { it.isNotBlank() } ?: languageName(f.language),
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
                    when (f.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${f.channelCount} ch"
                    }
                } else null,
            )
            val label = parts.joinToString(" · ").ifEmpty { "Track ${out.size + 1}" }
            out.add(TrackOption(label, group, i, group.isTrackSelected(i)))
        }
    }
    return out
}

@Composable
fun VodPlayerScreen(vm: AppViewModel, items: List<VodItem>, startIndex: Int, startMs: Long) {
    if (items.isEmpty()) return
    val isTv = LocalIsTv.current
    PhoneFullscreen()
    val scope = rememberCoroutineScope()

    fun save(p: Player) {
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        val duration = p.duration
        vm.saveProgress(item, p.currentPosition, if (duration > 0) duration else 0L)
    }

    val exo = rememberExoPlayer(vm.userAgent, vm.audioLanguage, vm.subtitleMode) { p -> save(p) }
    val focus = remember { FocusRequester() }

    var current by remember { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retried by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }

    fun showOverlay() {
        overlayVisible = true
        overlayToken++
    }

    LaunchedEffect(Unit) {
        val media = items.map { MediaItem.Builder().setUri(it.url).setMediaId(it.key).build() }
        exo.setMediaItems(media, startIndex.coerceIn(0, items.lastIndex), startMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
        delay(50)
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(overlayToken, playing, scrub) {
        if (!playing || scrub != null) return@LaunchedEffect
        delay(4_000)
        overlayVisible = false
    }

    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            position = exo.currentPosition
            duration = exo.duration.coerceAtLeast(0L)
            delay(500)
            ticks++
            if (ticks % 20 == 0 && exo.isPlaying) save(exo)
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying || exo.playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) error = null
                if (playbackState == Player.STATE_ENDED) {
                    items.getOrNull(exo.currentMediaItemIndex)?.let { vm.saveProgress(it, 0L, 0L) }
                    vm.pop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = current
                current = exo.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // The previous episode finished, so drop its resume point.
                    items.getOrNull(previous)?.let { vm.saveProgress(it, 0L, 0L) }
                }
                items.getOrNull(current)?.let { vm.saveProgress(it, 0L, 0L) }
                showOverlay()
            }

            override fun onPlayerError(e: PlaybackException) {
                if (!retried && e.errorCode in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    )
                ) {
                    retried = true
                    scope.launch {
                        delay(1_500)
                        exo.prepare()
                    }
                    return
                }
                error = describe(e)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // Save where we got to when leaving. Runs before the player is released.
    DisposableEffect(Unit) {
        onDispose { save(exo) }
    }

    LaunchedEffect(showTracks) {
        if (!showTracks) {
            delay(60)
            runCatching { focus.requestFocus() }
        }
    }

    fun scrubBy(deltaMs: Long) {
        val base = scrub ?: exo.currentPosition
        val max = if (duration > 0) duration - 2_000 else Long.MAX_VALUE
        scrub = (base + deltaMs).coerceIn(0L, max.coerceAtLeast(0L))
        showOverlay()
    }

    fun commitScrub() {
        val target = scrub ?: return
        exo.seekTo(target)
        scrub = null
        showOverlay()
    }

    fun togglePlay() {
        if (exo.isPlaying) exo.pause() else exo.play()
        showOverlay()
    }

    fun skipBy(deltaMs: Long) {
        val d = exo.duration
        val max = if (d > 0) d - 1_000 else Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, max.coerceAtLeast(0L)))
        showOverlay()
    }

    fun stepFor(repeat: Int): Long = when {
        repeat < 6 -> 10_000L
        repeat < 20 -> 30_000L
        else -> 60_000L
    }

    fun onKey(ev: KeyEvent): Boolean {
        if (showTracks) return false
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        val back = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
        val forward = code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        if (ev.type == KeyEventType.KeyUp) {
            if (back || forward) {
                commitScrub()
                return true
            }
            return isSelectKey(code)
        }
        if (ev.type != KeyEventType.KeyDown) return false
        when {
            back -> scrubBy(-stepFor(native.repeatCount))
            forward -> scrubBy(stepFor(native.repeatCount))
            isSelectKey(code) || code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == AndroidKeyEvent.KEYCODE_SPACE -> {
                if (native.repeatCount == 0) togglePlay()
            }
            code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                exo.play()
                showOverlay()
            }
            code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exo.pause()
                showOverlay()
            }
            code == AndroidKeyEvent.KEYCODE_DPAD_UP -> showOverlay()
            code == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (overlayVisible) showTracks = true else showOverlay()
            }
            code == AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
            }
            code == AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem()
            }
            else -> return false
        }
        return true
    }

    val item = items[current.coerceIn(0, items.lastIndex)]

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { onKey(it) }
            .focusable()
            // Touch: tap shows or hides the controls; double-tap the left or right side to skip 10 seconds.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (overlayVisible) overlayVisible = false else showOverlay() },
                    onDoubleTap = { offset -> skipBy(if (offset.x < size.width / 2f) -10_000L else 10_000L) },
                )
            }
    ) {
        VideoSurface(exo)

        val err = error
        if (err != null) {
            ErrorCard(err, if (isTv) "Press Back to leave." else "Go back to leave.")
        } else if (buffering) {
            Spinner()
        }

        if (!playing && !buffering && err == null && (isTv || !overlayVisible)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("❚❚", fontSize = 30.sp, color = Color.White)
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isTv) {
                VodOverlay(
                    item = item,
                    position = scrub ?: position,
                    duration = duration,
                    scrubbing = scrub != null,
                    playing = playing,
                    hasNext = current < items.lastIndex,
                )
            } else {
                VodTouchOverlay(
                    item = item,
                    position = scrub ?: position,
                    duration = duration,
                    playing = playing,
                    hasNext = current < items.lastIndex,
                    onBack = { vm.pop() },
                    onTogglePlay = { togglePlay() },
                    onSkip = { skipBy(it) },
                    onScrub = { ms ->
                        scrub = ms
                        showOverlay()
                    },
                    onScrubDone = { commitScrub() },
                    onTracks = { showTracks = true },
                    onNext = { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() },
                )
            }
        }

        val sleepLeft = vm.sleepMinutesLeft(LocalNow.current * 1000)
        if (sleepLeft != null && overlayVisible) {
            SleepBadge(sleepLeft, Modifier.align(Alignment.TopEnd).padding(top = if (isTv) 34.dp else 76.dp, end = if (isTv) 56.dp else 24.dp))
        }
    }

    if (showTracks) {
        TracksDialog(vm = vm, exo = exo, onDismiss = { showTracks = false })
    }
}

@Composable
private fun VodOverlay(item: VodItem, position: Long, duration: Long, scrubbing: Boolean, playing: Boolean, hasNext: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 60.dp)
        ) {
            Text(item.title, style = Txt.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.subtitle, style = Txt.Label, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 56.dp, end = 56.dp, top = 70.dp, bottom = 36.dp)
        ) {
            val fraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (scrubbing) 8.dp else 5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x40FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(if (scrubbing) Ember.Accent else Color.White)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(position), style = Txt.Label)
                Spacer(Modifier.weight(1f))
                Text(
                    buildString {
                        append(if (playing) "OK Pause" else "OK Play")
                        append("    ◀▶ Skip    ▼ Audio & subtitles")
                        if (hasNext) append("    ⏭ Next episode")
                    },
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "-" + formatMs(duration - position) else "", style = Txt.Label, color = Ember.Dim)
            }
        }
    }
}

@Composable
private fun VodTouchOverlay(
    item: VodItem,
    position: Long,
    duration: Long,
    playing: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    onScrubDone: () -> Unit,
    onTracks: () -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(EmberIcons.Back, "Back", background = ControlBg, glass = true, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = Txt.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, style = Txt.Caption, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            IconCircleButton(EmberIcons.Subtitles, "Audio and subtitles", background = ControlBg, glass = true, onClick = onTracks)
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            SkipButton("−10") { onSkip(-10_000L) }
            IconCircleButton(
                if (playing) EmberIcons.Pause else EmberIcons.Play,
                if (playing) "Pause" else "Play",
                size = 76.dp,
                background = Color(0x59000000),
                glass = true,
                onClick = onTogglePlay,
            )
            SkipButton("+10") { onSkip(10_000L) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { f -> if (duration > 0) onScrub((f * duration).toLong()) },
                onValueChangeFinished = onScrubDone,
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Ember.Accent,
                    inactiveTrackColor = Color(0x40FFFFFF),
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(position), style = Txt.Label)
                Spacer(Modifier.weight(1f))
                if (hasNext) {
                    PillButton("Next episode", icon = EmberIcons.Next, onClick = onNext)
                    Spacer(Modifier.width(14.dp))
                }
                Text(if (duration > 0) "-" + formatMs(duration - position) else "", style = Txt.Label, color = Ember.Dim)
            }
        }
    }
}

@Composable
private fun SkipButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .glassSurface(RoundedCornerShape(28.dp), ControlBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Txt.Label, color = Color.White)
    }
}

@Composable
private fun TracksDialog(vm: AppViewModel, exo: ExoPlayer, onDismiss: () -> Unit) {
    val audio = remember { trackOptions(exo, C.TRACK_TYPE_AUDIO) }
    val subs = remember { trackOptions(exo, C.TRACK_TYPE_TEXT) }
    val subtitlesOff = remember { subs.none { it.selected } }

    fun selectAudio(o: TrackOption) {
        val g = o.group ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index))
            .build()
        onDismiss()
    }

    fun selectSubtitle(o: TrackOption?) {
        val builder = exo.trackSelectionParameters.buildUpon()
        val g = o?.group
        if (o == null || g == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index))
        }
        exo.trackSelectionParameters = builder.build()
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(dialogPanelColor())
                .padding(28.dp)
        ) {
            Column(Modifier.width(300.dp)) {
                Text("Audio", style = Txt.Headline)
                Spacer(Modifier.height(14.dp))
                if (audio.isEmpty()) {
                    Text("Only one audio track", style = Txt.Body, color = Ember.Faint)
                }
                audio.forEachIndexed { i, o ->
                    TrackRow(o.label, o.selected, autoFocus = o.selected || (i == 0 && audio.none { it.selected })) { selectAudio(o) }
                }
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.width(300.dp)) {
                Text("Subtitles", style = Txt.Headline)
                Spacer(Modifier.height(14.dp))
                TrackRow("Off", subtitlesOff, autoFocus = audio.isEmpty()) { selectSubtitle(null) }
                subs.forEach { o ->
                    TrackRow(o.label, o.selected && !subtitlesOff, autoFocus = false) { selectSubtitle(o) }
                }
                Spacer(Modifier.height(18.dp))
                Text("Sleep timer", style = Txt.Headline)
                Spacer(Modifier.height(10.dp))
                val left = vm.sleepMinutesLeft()
                ZapAction(EmberIcons.Moon, if (left == null) "Off" else "Stops in $left min", "Change") { vm.cycleSleepTimer() }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).height(48.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.03f,
        color = if (selected) Color(0x26FFFFFF) else Color.Transparent,
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "✓" else "",
                color = if (focused) Color.Black else Ember.Accent,
                modifier = Modifier.width(24.dp),
            )
            Text(
                label,
                style = Txt.Label,
                color = if (focused) Color.Black else Ember.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
