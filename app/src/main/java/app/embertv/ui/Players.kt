package app.embertv.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.KeyEvent as AndroidKeyEvent
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.text.style.TextAlign
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
internal fun PhoneFullscreen() {
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

/** What's actually on screen: picture height and the measured frame rate. */
private data class VideoStats(val width: Int, val height: Int, val fps: Float?, val declared: Boolean = false) {
    val label: String
        get() {
            val res = when {
                height >= 2000 -> "4K"
                height > 0 -> "${height}p"
                else -> ""
            }
            val rate = fps?.let { f ->
                // Show the familiar broadcast rates as people know them.
                val known = listOf(23.976f to "23.98", 29.97f to "29.97", 59.94f to "59.94")
                    .firstOrNull { (v, _) -> kotlin.math.abs(f - v) < 0.05f }?.second
                (known ?: Math.round(f).toString()) + " fps"
            }
            return listOf(res, rate ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        }
}

/**
 * Measures the frame rate by counting decoded frames each second (rendered, skipped and dropped,
 * so it's the station's rate rather than what this device managed), averaged over a few seconds.
 * Falls back to the rate the stream declares until there's a measurement.
 */
@Composable
private fun rememberVideoStats(exo: ExoPlayer, key: Any): VideoStats? {
    val stats by produceState<VideoStats?>(null, exo, key) {
        value = null
        var lastFrames = -1L
        var lastTime = 0L
        val samples = ArrayDeque<Float>()
        while (true) {
            delay(1_000)
            val format = exo.videoFormat
            val counters = exo.videoDecoderCounters
            val now = android.os.SystemClock.elapsedRealtime()
            var measured: Float? = null
            if (counters != null && exo.isPlaying) {
                counters.ensureUpdated()
                val frames = counters.renderedOutputBufferCount.toLong() + counters.skippedOutputBufferCount + counters.droppedBufferCount
                if (lastFrames in 0..frames && now > lastTime) {
                    samples.addLast((frames - lastFrames) * 1000f / (now - lastTime))
                    while (samples.size > 4) samples.removeFirst()
                    // The first second after starting or a stall is unreliable.
                    if (samples.size >= 2) measured = samples.drop(1).average().toFloat()
                } else {
                    samples.clear()
                }
                lastFrames = frames
                lastTime = now
            } else {
                lastFrames = -1L
                samples.clear()
            }
            val declared = format?.frameRate?.takeIf { it > 0f }
            val size = exo.videoSize
            val w = if (size.width > 0) size.width else format?.width ?: 0
            val h = if (size.height > 0) size.height else format?.height ?: 0
            if (h > 0 || measured != null || declared != null) {
                val m = measured?.takeIf { it > 1f }
                value = when {
                    // A rate the stream states outright is exact; prefer it once it matches what's measured.
                    declared != null && (m == null || kotlin.math.abs(m - declared) < 1.5f) -> VideoStats(w, h, declared, declared = true)
                    m != null -> VideoStats(w, h, m)
                    else -> VideoStats(w, h, value?.fps, value?.declared ?: false)
                }
            }
        }
    }
    return stats
}

/** While a player is open on a phone, going home shrinks it into picture-in-picture (Android 12+). */
@Composable
private fun AutoPip() {
    if (LocalIsTv.current) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val ok = app.embertv.Pip.supported(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (ok) runCatching { activity.setPictureInPictureParams(app.embertv.Pip.params(autoEnter = true)) }
        onDispose {
            if (ok) runCatching { activity.setPictureInPictureParams(app.embertv.Pip.params(autoEnter = false)) }
        }
    }
}

/**
 * While a player is open on a TV, switches the display's refresh rate to suit [stats]
 * (50Hz for UK channels, 24Hz for films). Hands control back to the system when the player closes.
 */
@Composable
private fun MatchFrameRate(stats: VideoStats?) {
    val vm = LocalAppVm.current
    if (!LocalIsTv.current || !vm.matchFrameRate) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        onDispose { runCatching { app.embertv.FrameRate.request(activity, 0) } }
    }
    val fps = stats?.fps
    val target = fps?.let { app.embertv.FrameRate.standard(it, stats.declared) }
    LaunchedEffect(target) {
        // A dip while the TV switches (or buffers) gives no target; keep the current mode then.
        if (target == null) return@LaunchedEffect
        delay(400)
        runCatching {
            val mode = app.embertv.FrameRate.modeFor(activity, target) ?: return@runCatching
            if (mode.modeId != app.embertv.FrameRate.currentModeId(activity)) app.embertv.FrameRate.request(activity, mode.modeId)
        }
    }
}

@Composable
private fun rememberPipAction(): (() -> Unit)? {
    val activity = LocalContext.current.findActivity() ?: return null
    if (LocalIsTv.current || !remember(activity) { app.embertv.Pip.supported(activity) }) return null
    return { app.embertv.Pip.enter(activity) }
}

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
private fun VideoSurface(exo: ExoPlayer, modifier: Modifier = Modifier.fillMaxSize(), pictureMode: Int = 0) {
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
        modifier = modifier,
        update = { it.resizeMode = resizeModeFor(pictureMode) },
    )
}

private fun resizeModeFor(pictureMode: Int): Int = when (pictureMode) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Phones: pinch out to zoom the picture to fill the screen, pinch in to fit it again. */
private fun Modifier.pinchToZoom(onPinch: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var startDistance = 0f
        var done = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size < 2) continue
            val dx = down[0].position.x - down[1].position.x
            val dy = down[0].position.y - down[1].position.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (startDistance == 0f) {
                startDistance = distance
            } else if (!done && startDistance > 0f) {
                val ratio = distance / startDistance
                if (ratio > 1.2f) {
                    onPinch(true)
                    done = true
                } else if (ratio < 0.83f) {
                    onPinch(false)
                    done = true
                }
            }
            // Two fingers are a pinch: don't let them change channel or toggle the controls.
            event.changes.forEach { it.consume() }
        }
    }
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
private fun BoxScope.ErrorCard(message: String, hint: String, keys: List<Hint> = emptyList()) {
    // Apple style: a frosted card with a quiet icon, a short title and the reason in grey.
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(24.dp)
            .widthIn(max = 480.dp)
            .glassSurface(RoundedCornerShape(26.dp), Color(0xD9141418))
            .padding(horizontal = 28.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(EmberIcons.Alert, contentDescription = null, tint = Ember.Dim, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text("Can't play this", style = Txt.Headline, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = Txt.Body, color = Ember.Dim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        // TVs: the remote buttons that help, drawn like the remote. Phones: a sentence.
        if (LocalIsTv.current && keys.isNotEmpty()) {
            RemoteHints(keys, always = true)
        } else {
            Text(hint, style = Txt.Caption, color = Ember.Faint, textAlign = TextAlign.Center)
        }
    }
}

// ---------------- Live TV ----------------

@Composable
fun LivePlayerScreen(vm: AppViewModel, channelsIn: List<Channel>, startIndex: Int) {
    // A copy of the list, so a channel can be swapped for another quality of itself while watching.
    val channels = remember(channelsIn) { androidx.compose.runtime.mutableStateListOf<Channel>().apply { addAll(channelsIn) } }
    AutoPip()
    val enterPip = rememberPipAction()
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
    var guideOpen by remember { mutableStateOf(false) }
    // TVs: OK brings up a row of playback controls (pause, skip, guide…).
    var controlsOpen by remember { mutableStateOf(false) }
    var controlsToken by remember { mutableIntStateOf(0) }
    val controlsFocus = remember { FocusRequester() }
    var digits by remember { mutableStateOf("") }
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }

    // Pause and rewind, using the provider's catch-up. [shiftStart] is when the rewound stream
    // starts (seconds, whole minutes); null while watching live.
    var shiftStart by remember { mutableStateOf<Long?>(null) }
    var paused by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableStateOf(0L) }
    var pendingSeek by remember { mutableStateOf(0L) }
    var restart by remember { mutableIntStateOf(0) }
    var tsTick by remember { mutableIntStateOf(0) }

    val channel = channels[index]
    // The same channel in other qualities (FHD, HD, SD), for the ••• panel.
    val copies by produceState(emptyList<Channel>(), channel.streamId) { value = vm.channelCopies(channel) }
    // Live TV keeps the screen's own refresh rate (switching blanked the picture on every channel change).
    val videoStats = rememberVideoStats(exo, channel.streamId)
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

    // Favourites and recent channels don't remember whether they have catch-up: look it up.
    val repoForResolve = vm.repo
    val resolved by produceState(channel, channel.streamId) {
        value = repoForResolve?.resolve(channel) ?: channel
    }
    val canRewind = vm.timeshiftOn && resolved.hasArchive

    /** The moment on screen, as wall-clock milliseconds. */
    fun viewingWall(): Long {
        val start = shiftStart
        return when {
            start != null -> start * 1000 + exo.currentPosition
            paused -> pausedAt
            else -> System.currentTimeMillis()
        }
    }

    fun cantRewind() {
        vm.notify(
            when {
                !vm.timeshiftOn -> "Turn on Pause & rewind live TV in Settings"
                else -> "This channel doesn't have catch-up, so it can't rewind"
            }
        )
    }

    fun goLive() {
        shiftStart = null
        paused = false
        pendingSeek = 0
        restart++
        showOverlay()
    }

    /** Plays the provider's rewind from [targetMs]: from that minute, then on to the second if the stream allows. */
    fun startShift(targetMs: Long, roundUp: Boolean = false) {
        val ch = channels[index]
        val nowSec = System.currentTimeMillis() / 1000
        val targetSec = targetMs / 1000
        var startSec = targetSec / 60 * 60
        if (roundUp && startSec < targetSec) startSec += 60
        if (roundUp && startSec > nowSec - 60) return goLive()
        // The provider's rewind needs a start at least a minute ago; start earlier and move on to the moment.
        while (startSec > nowSec - 60) startSec -= 60
        val minutes = ((nowSec - startSec) / 60 + 2).toInt()
        val url = vm.timeshiftUrl(ch, startSec, minutes) ?: return
        shiftStart = startSec
        pendingSeek = (targetMs - startSec * 1000).coerceAtLeast(0L)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId("shift:${ch.streamId}:$startSec")
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = !paused
    }

    fun seekBy(ms: Long) {
        if (!canRewind) return cantRewind()
        // Already live: nothing further forward to go.
        if (ms > 0 && shiftStart == null && !paused) {
            vm.notify("You're watching live")
            return
        }
        val nowMs = System.currentTimeMillis()
        val days = if (resolved.archiveDays > 0) resolved.archiveDays else 7
        val target = (viewingWall() + ms).coerceAtLeast(nowMs - days * 86_400_000L)
        val start = shiftStart
        when {
            target >= nowMs - 60_000 && ms > 0 -> goLive()
            // Within the stream already playing, and the provider lets us move around in it.
            start != null && exo.isCurrentMediaItemSeekable && target >= start * 1000 &&
                (exo.duration <= 0 || target - start * 1000 < exo.duration) -> exo.seekTo(target - start * 1000)
            else -> startShift(target, roundUp = ms > 0)
        }
        showOverlay()
    }

    fun togglePause() {
        if (paused) {
            paused = false
            when {
                // Already rewound: just carry on.
                shiftStart != null -> exo.play()
                // Paused live on a catch-up channel: carry on from where it stopped.
                canRewind && System.currentTimeMillis() - pausedAt > 3_000 -> startShift(pausedAt)
                else -> {
                    if (!canRewind && System.currentTimeMillis() - pausedAt > 3_000) vm.notify("Back to live: this channel can't rewind")
                    goLive()
                }
            }
        } else {
            pausedAt = viewingWall()
            paused = true
            // Live: stop (freeing the provider connection), and pick up from the provider's rewind later.
            // (Not playing when ready, so coming back to the app doesn't start it again behind "Paused".)
            exo.playWhenReady = false
            if (shiftStart == null) exo.stop()
        }
        showOverlay()
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
    LaunchedEffect(index, alternate, restart) {
        if (!firstLoad) delay(350)
        firstLoad = false
        shiftStart = null
        paused = false
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
                if (shiftStart != null) {
                    // The provider's rewind didn't play: go back to live rather than show an error.
                    vm.notify("Your provider's rewind didn't work here, so back to live")
                    goLive()
                    return
                }
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
                if (playbackState == Player.STATE_READY) {
                    error = null
                    // Playing again: later dropouts get their own fresh retries.
                    retries = 0
                    // Rewound to a moment inside a minute: move on to it if the stream allows.
                    val seek = pendingSeek
                    if (shiftStart != null && seek > 1_000 && exo.isCurrentMediaItemSeekable) exo.seekTo(seek)
                    pendingSeek = 0
                }
                // The rewound stream reached its end: carry on from there, or back to live.
                if (playbackState == Player.STATE_ENDED && shiftStart != null) {
                    val at = viewingWall()
                    when {
                        System.currentTimeMillis() - at < 90_000 -> goLive()
                        // Ended almost at once: the provider has nothing there, so don't keep asking.
                        exo.currentPosition < 5_000 -> {
                            vm.notify("Your provider's rewind has nothing there, so back to live")
                            goLive()
                        }
                        else -> startShift(at)
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // Keeps "behind live" up to date while rewound or paused.
    LaunchedEffect(shiftStart, paused) {
        while (shiftStart != null || paused) {
            delay(1_000)
            tsTick++
        }
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

    BackHandler(enabled = controlsOpen) { controlsOpen = false }
    LaunchedEffect(controlsOpen, controlsToken) {
        if (!controlsOpen) return@LaunchedEffect
        if (controlsToken == 0) {
            delay(50)
            runCatching { controlsFocus.requestFocus() }
        }
        delay(8_000)
        controlsOpen = false
    }
    LaunchedEffect(listOpen, scoresOpen, guideOpen, controlsOpen, optionsOpen) {
        if (!listOpen && !scoresOpen && !guideOpen && !controlsOpen && !optionsOpen) {
            delay(60)
            runCatching { focus.requestFocus() }
        }
    }

    fun onKey(ev: KeyEvent): Boolean {
        if (listOpen || scoresOpen || guideOpen || controlsOpen || optionsOpen) return false
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        if (ev.type == KeyEventType.KeyUp) {
            if (isSelectKey(code)) {
                if (okDown && !okLong) {
                    // TVs: OK brings up the playback controls (or carries on, when paused).
                    if (paused) {
                        togglePause()
                    } else if (isTv) {
                        controlsToken = 0
                        controlsOpen = true
                        showOverlay()
                    } else if (overlayVisible) overlayVisible = false else showOverlay()
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
            // While paused, left and right skip back and forward like a recording.
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (paused) seekBy(-10_000) else listOpen = true
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (paused) seekBy(30_000) else scoresOpen = true
            // The ☰ button (or Guide, on remotes that have one) opens the guide over the picture.
            AndroidKeyEvent.KEYCODE_MENU -> {
                overlayVisible = false
                guideOpen = true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> togglePause()
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePause()
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePause()
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            AndroidKeyEvent.KEYCODE_INFO -> showOverlay()
            AndroidKeyEvent.KEYCODE_GUIDE -> {
                overlayVisible = false
                guideOpen = true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> if (canRewind) seekBy(-10_000) else previousChannel()
            AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> previousChannel()
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
            // Touch: tap shows or hides the info, swiping up or down changes channel, pinching zooms.
            .pinchToZoom { zoomIn -> vm.setPicture(if (zoomIn) 1 else 0) }
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
        // The channel keeps playing full screen, even with the guide open over it.
        VideoSurface(exo, Modifier.fillMaxSize(), pictureMode = if (vm.inPip) 0 else vm.pictureMode)
        // In picture-in-picture the window is tiny: just the picture.
        if (vm.inPip) return@Box
        // The guide floats over the lower part of the picture in a frosted panel.
        if (guideOpen) {
            PlayerGuide(
                vm = vm,
                channels = channels,
                current = index,
                onZap = { i ->
                    guideOpen = false
                    zapTo(i)
                },
                onClose = { guideOpen = false },
            )
            return@Box
        }

        val err = error
        if (err != null) {
            ErrorCard(
                err,
                "Swipe up or down to try another channel, or tap ⋯ to open it in another player like VLC.",
                keys = listOf(Hint("▲▼", "Another channel"), Hint("OK", "Then ••• for VLC")),
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

        // Bottom of the screen: the controls row (TVs, after OK), then the paused bar or the channel info.
        val nowMs = System.currentTimeMillis()
        @Suppress("UNUSED_EXPRESSION") tsTick
        val behind = if (paused || shiftStart != null) (nowMs - viewingWall()).coerceAtLeast(0L) else 0L
        // Bottom of the screen: the channel info (or the paused bar), then the buttons after OK on TVs.
        // One smooth shade behind all of it, so there's no hard line where one part meets the next.
        val bottomShown = !listOpen && (paused || overlayVisible || controlsOpen)
        val shade by androidx.compose.animation.core.animateFloatAsState(if (bottomShown) 1f else 0f, label = "shade")
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color(0x8C000000),
                        1f to Color(0xF2000000),
                    ),
                    alpha = shade,
                )
        ) {
            if (paused && !listOpen) {
                TimeshiftPanel(
                    behindMs = behind,
                    // The bar covers the last half hour, or further when rewound further.
                    keptMs = maxOf(30 * 60_000L, behind + 5 * 60_000L),
                    canRewind = canRewind,
                    isTv = isTv,
                    onBack = { seekBy(-10_000) },
                    onPlay = { togglePause() },
                    onForward = { seekBy(30_000) },
                    onLive = { goLive() },
                    modifier = Modifier,
                )
            } else {
                AnimatedVisibility(
                    visible = (overlayVisible || controlsOpen) && !listOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    LiveInfoBar(
                        vm = vm,
                        channel = channel,
                        stats = videoStats,
                        isFavourite = vm.isFavourite(ContentType.LIVE, channel.streamId),
                        format = if (alternate) (if (vm.streamFormat == "m3u8") "TS" else "HLS") else (if (vm.streamFormat == "m3u8") "HLS" else "TS"),
                        isTv = isTv,
                        // The remote hints make way for the buttons.
                        showHints = !controlsOpen,
                        tight = isTv && controlsOpen,
                    )
                }
            }
            if (isTv && controlsOpen && !listOpen) {
                LiveControls(
                    paused = paused,
                    canRewind = canRewind,
                    behindLive = behind > 10_000,
                    requester = controlsFocus,
                    onActivity = { controlsToken++ },
                    onPause = { togglePause() },
                    onBack = { seekBy(-10_000) },
                    onForward = { seekBy(30_000) },
                    onLive = { goLive() },
                    onGuide = {
                        controlsOpen = false
                        overlayVisible = false
                        guideOpen = true
                    },
                    onChannels = {
                        controlsOpen = false
                        listOpen = true
                    },
                    onScores = {
                        controlsOpen = false
                        scoresOpen = true
                    },
                    onMore = {
                        controlsOpen = false
                        optionsOpen = true
                    },
                )
            }
        }
        // Watching behind live after pausing or rewinding.
        if (!listOpen && !paused && behind > 10_000) {
            BehindLivePill(behind, isTv, Modifier.align(Alignment.TopEnd).padding(top = if (isTv) 26.dp else 76.dp, end = if (isTv) 40.dp else 16.dp)) { goLive() }
        }

        val sleepLeft = vm.sleepMinutesLeft(LocalNow.current * 1000)
        if (sleepLeft != null && overlayVisible) {
            SleepBadge(sleepLeft, Modifier.align(Alignment.TopEnd).padding(top = if (isTv) 30.dp else 76.dp, end = if (isTv) 40.dp else 24.dp))
        }

        if (!isTv) {
            // Phones: Apple TV app layout. Back and a few tools along the top, play controls in the
            // middle, a channel rocker on the right; everything else is in the ⋯ sheet.
            AnimatedVisibility(
                visible = (overlayVisible || paused) && !listOpen && !scoresOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
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
                        if (enterPip != null) IconCircleButton(EmberIcons.Pip, "Picture in picture", background = ControlBg, glass = true) { enterPip() }
                        IconCircleButton(EmberIcons.ChannelList, "Channel list", background = ControlBg, glass = true) { listOpen = true }
                        IconCircleButton(EmberIcons.More, "More options", background = ControlBg, glass = true) { optionsOpen = true }
                    }
                    if (vm.timeshiftOn) {
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(40.dp),
                        ) {
                            if (canRewind) SkipButton(forward = false, seconds = 10) { seekBy(-10_000L) }
                            IconCircleButton(
                                if (paused) EmberIcons.Play else EmberIcons.Pause,
                                if (paused) "Play" else "Pause",
                                size = 76.dp,
                                background = Color(0x59000000),
                                glass = true,
                            ) { togglePause() }
                            if (canRewind) SkipButton(forward = true, seconds = 30) { seekBy(30_000L) }
                        }
                    }
                    // Channel up / down, like a remote's rocker.
                    Column(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(end = 16.dp)
                            .glassSurface(RoundedCornerShape(28.dp), ControlBg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconCircleButton(EmberIcons.ChevronUp, "Channel up", size = 52.dp) { zap(upStep) }
                        Text("CH", style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Ember.Faint)
                        IconCircleButton(EmberIcons.ChevronDown, "Channel down", size = 52.dp) { zap(-upStep) }
                    }
                }
            }
        }

        if (listOpen) {
            if (!isTv) {
                // Tapping beside the list closes it.
                Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { listOpen = false })
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

        if (scoresOpen && !isTv) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scoresOpen = false })
        AnimatedVisibility(
            visible = scoresOpen,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 3 },
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxHeight()) {
                val now = LocalNow.current
                val title = rememberProgrammes(vm.repo!!, channel.streamId)?.firstOrNull { it.isLive(now) }?.title
                ScoresPanel(programmeTitle = listOfNotNull(title, channel.name).joinToString(" "), onClose = { scoresOpen = false })
            }
        }

        AnimatedVisibility(
            visible = optionsOpen,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 3 },
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally { it / 3 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                LiveOptionsPanel(
                    vm,
                    channel,
                    active = optionsOpen,
                    copies = copies,
                    onCopy = { c ->
                        optionsOpen = false
                        if (c.streamId != channel.streamId) {
                            channels[index] = c
                            restart++
                            vm.notify("Watching in ${channelParts(c.name).second ?: "another quality"}")
                        }
                    },
                    onOpenElsewhere = { vm.openElsewhere(Screen.PlayLive(channels, index)) },
                    onMultiView = { vm.push(Screen.MultiView(listOf(channels[index]), channels)) },
                    onPrevious = if (previousIndex != null) ({ previousChannel() }) else null,
                ) { optionsOpen = false }
            }
        }
    }

}

private fun clockLength(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Paused live TV: how far back you are in what's been kept, with skip and Live controls. */
@Composable
private fun TimeshiftPanel(
    behindMs: Long,
    keptMs: Long,
    canRewind: Boolean,
    isTv: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onForward: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = if (isTv) 56.dp else 20.dp, end = if (isTv) 56.dp else 20.dp, top = if (isTv) 70.dp else 40.dp, bottom = if (isTv) 34.dp else 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(EmberIcons.Pause, null, tint = Ember.Text, modifier = Modifier.size(if (isTv) 30.dp else 26.dp))
            Spacer(Modifier.width(12.dp))
            Text("Paused", style = Txt.Title.copy(fontSize = if (isTv) 26.sp else 22.sp))
            Spacer(Modifier.width(14.dp))
            Text(if (behindMs < 10_000) "at live" else "${clockLength(behindMs)} behind live", style = Txt.Label, color = Ember.Dim)
        }
        Spacer(Modifier.height(14.dp))
        // The kept recording, oldest on the left, live on the right; the dot is where you are.
        val fraction = (1f - behindMs.toFloat() / keptMs).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)))
            Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ember.Text))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = (maxWidth * fraction - 7.dp).coerceAtLeast(0.dp))
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("−${clockLength(keptMs)}", style = Txt.Caption, color = Ember.Faint)
            Spacer(Modifier.weight(1f))
            Text("LIVE", style = Txt.Caption.copy(fontWeight = FontWeight.Bold), color = Ember.Live)
        }
        Spacer(Modifier.height(12.dp))
        if (!canRewind) {
            Text(
                "This channel doesn't have catch-up, so Play goes back to live.",
                style = Txt.Caption,
                color = Ember.Faint,
            )
        } else if (isTv) {
            RemoteHints(
                listOf(
                    Hint("OK", "Play"),
                    Hint("◀", "Back 10s"),
                    Hint("▶", "Forward 30s"),
                    Hint("▲▼", "Change channel"),
                )
            )
        } else {
            // The big play and skip buttons are in the middle of the screen; just the way back to live here.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Back to live", onClick = onLive)
            }
        }
    }
}

/** TVs: the row of controls OK brings up over live TV, Apple TV style. */
@Composable
private fun LiveControls(
    paused: Boolean,
    canRewind: Boolean,
    behindLive: Boolean,
    requester: FocusRequester,
    onActivity: () -> Unit,
    onPause: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onLive: () -> Unit,
    onGuide: () -> Unit,
    onChannels: () -> Unit,
    onScores: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 56.dp, top = 22.dp, bottom = 34.dp)
            .onPreviewKeyEvent {
                onActivity()
                false
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRewind) PlayerButton(EmberIcons.SkipBack, null, "live:ctl:back", badge = "10", onClick = onBack)
        PlayerButton(if (paused) EmberIcons.Play else EmberIcons.Pause, null, "live:ctl:pause", requester = requester, onClick = onPause)
        if (canRewind) PlayerButton(EmberIcons.SkipForward, null, "live:ctl:fwd", badge = "30", onClick = onForward)
        if (behindLive) PlayerButton(EmberIcons.Live, "Live", "live:ctl:live", onClick = onLive)
        Spacer(Modifier.width(12.dp))
        PlayerButton(EmberIcons.Guide, "TV guide", "live:ctl:guide", onClick = onGuide)
        PlayerButton(EmberIcons.ChannelList, "Channels", "live:ctl:list", onClick = onChannels)
        PlayerButton(EmberIcons.Football, "Scores", "live:ctl:scores", onClick = onScores)
        // Favourites, catch-up, multi-view, picture zoom, subtitles and the rest.
        PlayerButton(EmberIcons.More, null, "live:ctl:more", onClick = onMore)
    }
}

/** Shown while watching behind live after pausing or rewinding. */
@Composable
private fun BehindLivePill(behindMs: Long, isTv: Boolean, modifier: Modifier, onLive: () -> Unit) {
    Row(
        modifier
            .glassSurface(RoundedCornerShape(16.dp), Color(0xB3101014))
            .then(if (isTv) Modifier else Modifier.pressable(onClick = onLive))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−${clockLength(behindMs)}", style = Txt.Label.copy(fontWeight = FontWeight.SemiBold), color = Ember.Text)
        Spacer(Modifier.width(8.dp))
        if (isTv) {
            Text("behind live", style = Txt.Caption, color = Ember.Dim)
            Spacer(Modifier.width(10.dp))
            RemoteHints(listOf(Hint("▶▶", "Catch up")))
        } else {
            Text("behind live  ·  tap for live", style = Txt.Caption, color = Ember.Dim)
        }
    }
}

@Composable
private fun LiveInfoBar(
    vm: AppViewModel,
    channel: Channel,
    stats: VideoStats?,
    isFavourite: Boolean,
    format: String,
    isTv: Boolean,
    showHints: Boolean = true,
    tight: Boolean = false,
) {
    val repo = vm.repo ?: return
    val now = LocalNow.current
    val programmes = rememberProgrammes(repo, channel.streamId)
    val current = programmes?.firstOrNull { it.isLive(now) }
    val next = programmes?.firstOrNull { it.start >= (current?.end ?: now) }

    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(
                start = if (isTv) 56.dp else 24.dp,
                end = if (isTv) 56.dp else 24.dp,
                top = if (isTv) 70.dp else 36.dp,
                bottom = if (tight) 0.dp else if (isTv) 36.dp else 16.dp,
            )
    ) {
        Column {
            if (isTv && !showHints) {
                // Nothing: the buttons below say what OK does now.
            } else if (isTv) {
                RemoteHints(
                    listOf(
                        Hint("OK", "Controls"),
                        Hint("☰", "Guide"),
                        Hint("▲▼", "Channels"),
                        Hint("◀", "Channel list"),
                        Hint("▶", "Live scores"),
                        Hint("HOLD OK", "Favourite"),
                    )
                )
            } else {
                Text("Swipe up or down to change channel", style = Txt.Caption, color = Ember.Faint)
            }
            if (showHints || !isTv) Spacer(Modifier.height(if (isTv) 14.dp else 10.dp))
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
                        Text(channelParts(channel.name).first, style = Txt.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isFavourite) {
                            Spacer(Modifier.width(10.dp))
                            StarMark(20.dp)
                        }
                        val activity = LocalContext.current.findActivity()
                        val tvRate = if (isTv && vm.matchFrameRate && activity != null) {
                            runCatching { app.embertv.FrameRate.label(app.embertv.FrameRate.currentRefresh(activity)) }.getOrNull()
                        } else {
                            null
                        }
                        val label = listOfNotNull(stats?.label?.takeIf { it.isNotEmpty() }, tvRate?.let { "TV $it" }).joinToString(" · ")
                        if (label.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier
                                    .glassSurface(RoundedCornerShape(7.dp), Color(0x1FFFFFFF))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(label, style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = Ember.Text, maxLines = 1)
                            }
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
                        if (programmes == null) {
                            SkeletonLines(Modifier.width(if (isTv) 420.dp else 260.dp), widths = listOf(0.8f, 0.55f))
                        } else {
                            Text("No guide information", style = Txt.Label, color = Ember.Dim)
                        }
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
    val isTv = LocalIsTv.current
    // The same floating frosted panel as scores and options, on the left, over the picture.
    Column(
        Modifier
            .align(Alignment.CenterStart)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .fillMaxHeight()
            .padding(if (isTv) 28.dp else 12.dp)
            .width(if (isTv) 420.dp else 340.dp)
            .glassSurface(RoundedCornerShape(26.dp), Color(0xE0141418))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(top = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Channels", style = Txt.Headline.copy(fontWeight = FontWeight.Bold))
                Text("${channels.size} in this list", style = Txt.Caption, color = Ember.Faint)
            }
            if (!isTv) IconCircleButton(EmberIcons.Close, "Close", size = 38.dp, background = Color(0x1AFFFFFF), glass = true, onClick = onClose)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (hasPrevious) {
                item(key = "zap-previous") {
                    Box(Modifier.padding(bottom = 6.dp)) {
                        ZapAction(EmberIcons.Restart, "Previous channel", "", onClick = onPrevious)
                    }
                }
            }
            itemsIndexed(channels, key = { i, c -> "$i-${c.streamId}" }) { i, c ->
                ZapRow(vm, c, playing = i == current, onClick = { onPick(i) })
            }
        }
        if (isTv) {
            RemoteHints(
                listOf(Hint("OK", "Watch"), Hint("BACK", "Close")),
                Modifier.padding(start = 20.dp, top = 6.dp, bottom = 18.dp),
            )
        }
    }
}

/** A channel in the list over live TV: number, logo, name and what's on now, white when selected. */
@Composable
private fun ZapRow(vm: AppViewModel, c: Channel, playing: Boolean, onClick: () -> Unit) {
    val repo = vm.repo
    val now = LocalNow.current
    val onNow = if (repo != null) rememberProgrammes(repo, c.streamId)?.firstOrNull { it.isLive(now) } else null
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.03f,
        color = if (playing) Color(0x26FFFFFF) else Color.Transparent,
        focusedColor = Color.White,
        autoFocus = playing,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.num > 0) "${c.num}" else "",
                style = Txt.Caption,
                color = if (focused) Color(0xFF55555C) else Ember.Faint,
                maxLines = 1,
                modifier = Modifier.width(44.dp),
            )
            ChannelLogo(c.icon, c.name, Modifier.size(width = 54.dp, height = 36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(channelParts(c.name).first, style = Txt.Label, color = if (focused) Color.Black else Ember.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onNow != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(onNow.title, style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(0.85f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (focused) Color(0x22000000) else Color(0x26FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(onNow.progress(now).coerceIn(0f, 1f)).background(if (focused) Color.Black else Ember.Text))
                    }
                }
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                // The channel you're watching.
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (focused) Color.Black else Ember.Live))
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
private fun ZapAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, autoFocus: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).height(50.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.03f,
        color = Color(0x14FFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
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
/** Live TV options in the Apple TV side panel: the channel keeps playing while you choose. */
@Composable
private fun BoxScope.LiveOptionsPanel(
    vm: AppViewModel,
    channel: Channel,
    active: Boolean,
    copies: List<Channel> = emptyList(),
    onCopy: (Channel) -> Unit = {},
    onOpenElsewhere: () -> Unit,
    onMultiView: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = active, onBack = onDismiss)
    SidePanel(onDismiss) {
        Text(channelParts(channel.name).first, style = Txt.Headline, color = Ember.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
        // Other qualities of this channel, to switch to.
        if (copies.size > 1) {
            SheetSection("Quality")
            copies.forEach { c ->
                val tag = channelParts(c.name).second ?: "Standard"
                TrackRow(tag, c.streamId == channel.streamId, autoFocus = false) { onCopy(c) }
            }
            Spacer(Modifier.height(14.dp))
        }
        SheetSection("Channel")
        val fav = vm.isFavourite(ContentType.LIVE, channel.streamId)
        ZapAction(if (fav) EmberIcons.StarFilled else EmberIcons.Star, if (fav) "Remove from favourites" else "Add to favourites", "", autoFocus = true) {
            vm.toggleFavourite(vm.favouriteOf(channel))
        }
        if (onPrevious != null) {
            ZapAction(EmberIcons.Restart, "Previous channel", "") {
                onDismiss()
                onPrevious()
            }
        }
        CatchupActions(vm, channel, onDismiss)
        ZapAction(EmberIcons.MultiView, "Multi-view", "") {
            onDismiss()
            onMultiView()
        }
        if (vm.pinnedMatch != null) ZapAction(EmberIcons.Football, "Remove pinned score", "") { vm.unpinMatch() }
        Spacer(Modifier.height(14.dp))
        SheetSection("Picture")
        PICTURE_MODES.forEachIndexed { i, name ->
            TrackRow(name, vm.pictureMode == i, autoFocus = false) { vm.setPicture(i, announce = false) }
        }
        Spacer(Modifier.height(14.dp))
        SheetSection("Sound & subtitles")
        ZapAction(EmberIcons.Subtitles, "Subtitles", if (vm.subtitleMode == "off") "Off" else "English") { vm.cycleSubtitles() }
        ZapAction(EmberIcons.Language, "Preferred audio", if (vm.audioLanguage.isEmpty()) "Default" else "English") { vm.cycleAudioLanguage() }
        PanelNote("Subtitle and audio changes apply from the next channel.")
        Spacer(Modifier.height(14.dp))
        SheetSection("More")
        val left = vm.sleepMinutesLeft()
        ZapAction(EmberIcons.Moon, "Sleep timer", if (left == null) "Off" else "$left min") { vm.cycleSleepTimer() }
        ZapAction(EmberIcons.OpenExternal, "Open in another player", "") {
            onDismiss()
            onOpenElsewhere()
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
    AutoPip()
    val enterPip = rememberPipAction()
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
    MatchFrameRate(rememberVideoStats(exo, current))
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retried by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    // The side panel that's open: "subs", "audio", "more", or "all" (phones). Null when closed.
    var panel by remember { mutableStateOf<String?>(null) }
    val showTracks = panel != null
    // TVs: the row of buttons (play, skip, next episode, live scores) that pausing brings up.
    var controlsOpen by remember { mutableStateOf(false) }
    var controlsToken by remember { mutableIntStateOf(0) }
    var scoresOpen by remember { mutableStateOf(false) }
    val controlsFocus = remember { FocusRequester() }
    // What this film or episode offers, so the pause buttons only show what's there.
    var subtitleTracks by remember { mutableIntStateOf(0) }
    var audioTracks by remember { mutableIntStateOf(0) }

    fun showOverlay() {
        overlayVisible = true
        overlayToken++
    }

    fun openControls() {
        controlsToken = 0
        controlsOpen = true
        showOverlay()
    }

    LaunchedEffect(Unit) {
        val media = items.map { MediaItem.Builder().setUri(it.url).setMediaId(it.key).build() }
        exo.setMediaItems(media, startIndex.coerceIn(0, items.lastIndex), startMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
        delay(50)
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(overlayToken, playing, scrub, controlsOpen) {
        // The pause buttons keep the overlay up; they tuck it away themselves.
        if (!playing || scrub != null || controlsOpen) return@LaunchedEffect
        delay(4_000)
        overlayVisible = false
    }

    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            position = exo.currentPosition
            duration = exo.duration.coerceAtLeast(0L)
            if (isTv && ticks % 4 == 0) {
                subtitleTracks = trackOptions(exo, C.TRACK_TYPE_TEXT).size
                audioTracks = trackOptions(exo, C.TRACK_TYPE_AUDIO).size
            }
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
                    items.getOrNull(exo.currentMediaItemIndex)?.let {
                        vm.saveProgress(it, 0L, 0L)
                        vm.markFinished(it)
                    }
                    vm.pop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Same film or episode in another quality: nothing has finished or started.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                val previous = current
                current = exo.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // The previous episode finished, so drop its resume point and tick it off.
                    items.getOrNull(previous)?.let {
                        vm.saveProgress(it, 0L, 0L)
                        vm.markFinished(it)
                    }
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

    BackHandler(enabled = scoresOpen) { scoresOpen = false }
    BackHandler(enabled = controlsOpen && !scoresOpen) { controlsOpen = false }
    LaunchedEffect(controlsOpen, controlsToken, playing) {
        if (!controlsOpen) return@LaunchedEffect
        if (controlsToken == 0) {
            delay(50)
            runCatching { controlsFocus.requestFocus() }
        }
        // Stays up while paused; tucks itself away a few seconds after playing again.
        if (!playing) return@LaunchedEffect
        delay(5_000)
        controlsOpen = false
        overlayVisible = false
    }
    LaunchedEffect(showTracks, controlsOpen, scoresOpen) {
        if (showTracks || scoresOpen) return@LaunchedEffect
        delay(60)
        // Back from a side panel lands on the pause buttons when they're up.
        runCatching { if (controlsOpen) controlsFocus.requestFocus() else focus.requestFocus() }
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
        if (exo.isPlaying) {
            exo.pause()
            // TVs: pausing brings up the buttons.
            if (isTv && !controlsOpen) openControls()
        } else {
            exo.play()
        }
        showOverlay()
    }

    fun nextEpisode(finished: Boolean = false) {
        if (exo.hasNextMediaItem()) {
            // From Up Next at the end: this one counts as watched, as if it had played out.
            if (finished) items.getOrNull(exo.currentMediaItemIndex)?.let {
                vm.saveProgress(it, 0L, 0L)
                vm.markFinished(it)
            }
            exo.seekToNextMediaItem()
            exo.play()
        }
    }

    // ---- Up Next: the next episode, with a countdown (Settings → Play next episode automatically) ----
    var upNextCancelled by remember(current) { mutableStateOf(false) }
    // With autoplay off (or cancelled), stop at the end of each episode instead of rolling on.
    LaunchedEffect(vm.autoplayNext, upNextCancelled) { exo.pauseAtEndOfMediaItems = !vm.autoplayNext || upNextCancelled }
    val hasNextItem = current < items.lastIndex
    val remainingMs = duration - position
    val atEnd = duration > 0 && remainingMs <= 1_200 && !playing
    val counting = vm.autoplayNext && !upNextCancelled && playing && remainingMs in 1..UP_NEXT_MS
    val upNext = hasNextItem && duration > 120_000 && error == null && !vm.inPip && (counting || atEnd)
    BackHandler(enabled = upNext && counting) { upNextCancelled = true }

    // ---- Quality: switch to another copy (4K, FHD, HD) and carry on from the same moment ----
    val qualityChoices by produceState(emptyList<AppViewModel.QualityChoice>(), current) { value = vm.qualityChoicesFor(items[current]) }
    // Where each item plays from now (changes when a different quality is picked).
    val urls = remember { items.map { it.url }.toMutableList() }
    var playingVersion by remember(current) { mutableStateOf<Int?>(null) }
    val activeVersion = playingVersion ?: (items[current].seriesId ?: items[current].contentId)

    fun switchQuality(c: AppViewModel.QualityChoice) {
        if (c.versionId == activeVersion) return
        scope.launch {
            val found = vm.urlsFor(c, items, current)
            if (found == null) {
                vm.notify("Couldn't find this one in ${c.quality.title}")
                return@launch
            }
            for ((i, u) in found) urls[i] = u
            val at = exo.currentPosition
            exo.setMediaItems(items.mapIndexed { i, it -> MediaItem.Builder().setUri(urls[i]).setMediaId(it.key).build() }, current, at)
            exo.prepare()
            // Carry on watching in the new quality.
            exo.playWhenReady = true
            playingVersion = c.versionId
            vm.notify("Playing in ${c.quality.title}")
        }
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
        if (showTracks || scoresOpen) return false
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        // Up Next showing: OK plays the next episode now.
        if (upNext && !controlsOpen && isSelectKey(code)) {
            if (ev.type == KeyEventType.KeyDown && native.repeatCount == 0) nextEpisode(finished = true)
            return true
        }
        if (controlsOpen) {
            // The buttons have the remote; only the media keys still work directly.
            if (ev.type != KeyEventType.KeyDown) return false
            when (code) {
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> skipBy(-10_000L)
                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skipBy(30_000L)
                AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> nextEpisode()
                else -> return false
            }
            controlsToken++
            return true
        }
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
                if (overlayVisible) panel = "more" else showOverlay()
            }
            code == AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> nextEpisode()
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
            // Touch: tap shows or hides the controls; double-tap the left or right side to skip 10 seconds;
            // pinch to zoom the picture.
            .pinchToZoom { zoomIn -> vm.setPicture(if (zoomIn) 1 else 0) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (overlayVisible) overlayVisible = false else showOverlay() },
                    onDoubleTap = { offset -> skipBy(if (offset.x < size.width / 2f) -10_000L else 10_000L) },
                )
            }
    ) {
        VideoSurface(exo, pictureMode = if (vm.inPip) 0 else vm.pictureMode)
        if (vm.inPip) return@Box

        val err = error
        if (err != null) {
            ErrorCard(
                err,
                "Tap the screen, then the subtitles button, to open it in another player like VLC.",
                keys = listOf(Hint("▼", "Options, like VLC"), Hint("BACK", "Leave")),
            )
        } else if (buffering) {
            Spinner()
        }

        // Phones: a small paused sign when the controls are tucked away. TVs show the button row instead.
        if (!isTv && !playing && !buffering && err == null && !overlayVisible) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .glassSurface(RoundedCornerShape(48.dp), Color(0x59000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EmberIcons.Pause, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(34.dp))
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
                    controlsOpen = controlsOpen && !scoresOpen,
                    requester = controlsFocus,
                    onActivity = { controlsToken++ },
                    onTogglePlay = { togglePlay() },
                    onSkip = { skipBy(it) },
                    onNext = { nextEpisode() },
                    onSubtitles = { panel = "subs" },
                    onLanguage = { panel = "audio" },
                    onMore = { panel = "more" },
                    // The button shows what's playing (4K, FHD…); if that can't be told, just shows the icon.
                    hasQualities = qualityChoices.size > 1,
                    qualityBadge = qualityChoices.firstOrNull { it.versionId == activeVersion }?.quality?.badge?.ifEmpty { "SD" },
                    onQuality = { panel = "quality" },
                    hasSubtitles = subtitleTracks > 0,
                    hasLanguages = audioTracks > 1,
                    onScores = {
                        controlsOpen = false
                        scoresOpen = true
                    },
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
                    onTracks = { panel = "all" },
                    onNext = { nextEpisode() },
                    onScores = { scoresOpen = true },
                    onPip = enterPip,
                )
            }
        }

        if (scoresOpen && !isTv) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scoresOpen = false })
        AnimatedVisibility(
            visible = scoresOpen,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 3 },
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxHeight()) {
                ScoresPanel(programmeTitle = null, onClose = { scoresOpen = false })
            }
        }

        AnimatedVisibility(
            visible = upNext && panel == null && !scoresOpen,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 3 },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = if (isTv) 48.dp else 16.dp, bottom = if (isTv) 150.dp else 110.dp),
        ) {
            val next = items.getOrNull(current + 1)
            if (next != null) {
                UpNextCard(
                    next = next,
                    secondsLeft = if (counting) ((remainingMs + 999) / 1000).toInt() else null,
                    onPlay = { nextEpisode(finished = true) },
                    onCancel = { upNextCancelled = true },
                )
            }
        }

        val sleepLeft = vm.sleepMinutesLeft(LocalNow.current * 1000)
        if (sleepLeft != null && overlayVisible && panel == null) {
            SleepBadge(sleepLeft, Modifier.align(Alignment.TopEnd).padding(top = if (isTv) 34.dp else 76.dp, end = if (isTv) 56.dp else 24.dp))
        }

        val open = panel
        AnimatedVisibility(
            visible = open != null,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 3 },
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally { it / 3 },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Keeps showing the last panel while it slides away.
            var shown by remember { mutableStateOf("more") }
            if (open != null) shown = open
            Box(Modifier.fillMaxSize()) {
                TrackPanel(
                    vm = vm,
                    exo = exo,
                    kind = shown,
                    active = open != null,
                    qualities = qualityChoices,
                    activeQuality = activeVersion,
                    onQuality = { c ->
                        panel = null
                        switchQuality(c)
                    },
                    onOpenElsewhere = { vm.openElsewhere(Screen.PlayVod(items, current, exo.currentPosition)) },
                    onDismiss = { panel = null },
                )
            }
        }
    }
}

/**
 * Apple TV style player button: frosted glass, an icon, and a label when there's room. Turns white
 * with a black icon when selected. With no label it's a circle, like the play and skip buttons.
 */
@Composable
private fun PlayerButton(
    icon: ImageVector,
    label: String?,
    focusKey: String,
    requester: FocusRequester? = null,
    badge: String? = null,
    text: String? = null,
    onClick: () -> Unit,
) {
    FocusBox(
        modifier = if (label == null) Modifier.size(52.dp) else Modifier.height(52.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(26.dp),
        focusedScale = 1.08f,
        // Translucent, so FocusBox gives it the glass edge and highlight.
        color = Color(0x2EFFFFFF),
        focusedColor = Color.White,
        requester = requester,
        onClick = onClick,
    ) { focused ->
        val tint = if (focused) Color.Black else Ember.Text
        Row(
            Modifier.fillMaxHeight().then(if (label == null) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 18.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (text != null) {
                // A word instead of an icon, like "4K" for the quality button.
                Text(text, style = Txt.Label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp), color = tint, maxLines = 1)
            } else Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (badge != null) 28.dp else 22.dp))
                // The seconds inside the skip arrows, as on Apple TV.
                if (badge != null) Text(badge, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.padding(top = 3.dp))
            }
            if (label != null) {
                Spacer(Modifier.width(9.dp))
                Text(label, style = Txt.Label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = tint, maxLines = 1)
            }
        }
    }
}

/** Apple TV style progress: a rounded bar, with a playhead when paused or skipping. */
@Composable
private fun PlayerScrubber(fraction: Float, showHead: Boolean, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x40FFFFFF))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color(0xF2FFFFFF)))
        }
        if (showHead) {
            Box(
                Modifier
                    .padding(start = (maxWidth - 16.dp) * f)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/** TVs: Apple TV style. Title, progress and times at the bottom, and a row of glass buttons when paused. */
@Composable
private fun VodOverlay(
    item: VodItem,
    position: Long,
    duration: Long,
    scrubbing: Boolean,
    playing: Boolean,
    hasNext: Boolean,
    controlsOpen: Boolean,
    requester: FocusRequester,
    onActivity: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkip: (Long) -> Unit,
    onNext: () -> Unit,
    onSubtitles: () -> Unit,
    onLanguage: () -> Unit,
    onMore: () -> Unit,
    hasQualities: Boolean,
    qualityBadge: String?,
    onQuality: () -> Unit,
    hasSubtitles: Boolean,
    hasLanguages: Boolean,
    onScores: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000))))
                .padding(start = 40.dp, end = 40.dp, top = 90.dp, bottom = 30.dp)
        ) {
            Text(item.title, style = Txt.Headline.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.subtitle, style = Txt.Label, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            PlayerScrubber(
                fraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                showHead = scrubbing || !playing,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(position), style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = Ember.Text)
                Spacer(Modifier.weight(1f))
                if (!controlsOpen) {
                    RemoteHints(
                        if (playing) listOf(Hint("OK", "Pause"), Hint("◀▶", "Skip"))
                        else listOf(Hint("OK", "Play"), Hint("▼", "More"))
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "−" + formatMs(duration - position) else "", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = Ember.Dim)
            }
            AnimatedVisibility(visible = controlsOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .onPreviewKeyEvent {
                            onActivity()
                            false
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerButton(EmberIcons.SkipBack, null, "vod:ctl:back", badge = "10") { onSkip(-10_000L) }
                    PlayerButton(if (playing) EmberIcons.Pause else EmberIcons.Play, null, "vod:ctl:play", requester = requester, onClick = onTogglePlay)
                    PlayerButton(EmberIcons.SkipForward, null, "vod:ctl:fwd", badge = "30") { onSkip(30_000L) }
                    Spacer(Modifier.width(12.dp))
                    if (hasNext) PlayerButton(EmberIcons.Next, "Next episode", "vod:ctl:next", onClick = onNext)
                    // Subtitles, other languages and other qualities, when this film or episode has them:
                    // round buttons, as on Apple TV, so everything fits.
                    if (hasSubtitles) PlayerButton(EmberIcons.Subtitles, null, "vod:ctl:subs", onClick = onSubtitles)
                    if (hasLanguages) PlayerButton(EmberIcons.Language, null, "vod:ctl:audio", onClick = onLanguage)
                    if (hasQualities) PlayerButton(EmberIcons.Sliders, null, "vod:ctl:quality", text = qualityBadge, onClick = onQuality)
                    PlayerButton(EmberIcons.Football, "Scores", "vod:ctl:scores", onClick = onScores)
                    // Picture zoom, sleep timer and the rest.
                    PlayerButton(EmberIcons.More, null, "vod:ctl:more", onClick = onMore)
                }
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
    onScores: () -> Unit,
    onPip: (() -> Unit)? = null,
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
            IconCircleButton(EmberIcons.Football, "Live scores", background = ControlBg, glass = true, onClick = onScores)
            Spacer(Modifier.width(8.dp))
            if (onPip != null) {
                IconCircleButton(EmberIcons.Pip, "Picture in picture", background = ControlBg, glass = true, onClick = onPip)
                Spacer(Modifier.width(8.dp))
            }
            IconCircleButton(EmberIcons.Subtitles, "Subtitles, language and more", background = ControlBg, glass = true, onClick = onTracks)
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            SkipButton(forward = false, seconds = 10) { onSkip(-10_000L) }
            IconCircleButton(
                if (playing) EmberIcons.Pause else EmberIcons.Play,
                if (playing) "Pause" else "Play",
                size = 76.dp,
                background = Color(0x59000000),
                glass = true,
                onClick = onTogglePlay,
            )
            SkipButton(forward = true, seconds = 10) { onSkip(10_000L) }
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
                    // Apple style: a white bar on a faint track.
                    activeTrackColor = Color(0xF2FFFFFF),
                    inactiveTrackColor = Color(0x40FFFFFF),
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(position), style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = Ember.Text)
                Spacer(Modifier.weight(1f))
                if (hasNext) {
                    PillButton("Next episode", icon = EmberIcons.Next, onClick = onNext)
                    Spacer(Modifier.width(14.dp))
                }
                Text(if (duration > 0) "−" + formatMs(duration - position) else "", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold), color = Ember.Dim)
            }
        }
    }
}

/** Phones: Apple-style skip button, a circular arrow with the seconds inside, on frosted glass. */
@Composable
private fun SkipButton(forward: Boolean, seconds: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .glassSurface(RoundedCornerShape(28.dp), ControlBg)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (forward) EmberIcons.SkipForward else EmberIcons.SkipBack,
            contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
        Text("$seconds", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 3.dp))
    }
}

/** How long before the end the Up Next countdown starts. */
private const val UP_NEXT_MS = 15_000L

/**
 * Apple TV's Up Next: a frosted card with the next episode's picture and name. Counting down, it
 * starts by itself; with autoplay off (or cancelled), it waits for you at the end.
 */
@Composable
private fun UpNextCard(next: VodItem, secondsLeft: Int?, onPlay: () -> Unit, onCancel: () -> Unit) {
    val isTv = LocalIsTv.current
    Row(
        Modifier
            .width(if (isTv) 440.dp else 340.dp)
            .glassSurface(RoundedCornerShape(22.dp), Color(0xE0141418))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (isTv) 150.dp else 112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x1FFFFFFF))
        ) {
            NetImage(next.image.ifBlank { next.seriesCover }, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).size(34.dp).glassSurface(CircleShape, Color(0x59000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EmberIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (secondsLeft != null) "UP NEXT IN $secondsLeft" else "UP NEXT",
                style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
                color = Ember.Dim,
            )
            Spacer(Modifier.height(3.dp))
            Text(next.subtitle.substringBefore("  ").ifBlank { next.title }, style = Txt.Label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val name = next.subtitle.substringAfter("  ", "").trim()
            if (name.isNotEmpty()) Text(name, style = Txt.Caption, color = Ember.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (isTv) {
                RemoteHints(
                    if (secondsLeft != null) listOf(Hint("OK", "Play now"), Hint("BACK", "Cancel"))
                    else listOf(Hint("OK", "Play next episode")),
                    always = true,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play", icon = EmberIcons.Play, primary = true, onClick = onPlay)
                    if (secondsLeft != null) PillButton("Cancel", onClick = onCancel)
                }
            }
        }
    }
}

/**
 * Apple TV style side panel over the picture: the film keeps playing while you choose.
 * [kind] is "subs" (subtitles), "audio" (language), "more" (picture, sleep timer, other player)
 * or "all" (phones: everything in one scrolling panel).
 */
@Composable
private fun BoxScope.TrackPanel(
    vm: AppViewModel,
    exo: ExoPlayer,
    kind: String,
    active: Boolean,
    qualities: List<AppViewModel.QualityChoice> = emptyList(),
    activeQuality: Int? = null,
    onQuality: (AppViewModel.QualityChoice) -> Unit = {},
    onOpenElsewhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTv = LocalIsTv.current
    // Read fresh each time it opens, so the ticks match what's playing now.
    val audio = remember(kind) { trackOptions(exo, C.TRACK_TYPE_AUDIO) }
    val subs = remember(kind) { trackOptions(exo, C.TRACK_TYPE_TEXT) }
    val subtitlesOff = remember(kind) { subs.none { it.selected } }
    BackHandler(enabled = active, onBack = onDismiss)

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

    val all = kind == "all"
    SidePanel(onDismiss) {
        if (kind == "quality" || (all && qualities.size > 1)) {
            SheetSection("Quality")
            qualities.forEach { c ->
                TrackRow(c.label, c.versionId == activeQuality, autoFocus = kind == "quality" && c.versionId == activeQuality) { onQuality(c) }
            }
            PanelNote("Carries on from the same moment.")
            Spacer(Modifier.height(18.dp))
        }
        if (kind == "subs" || (all && subs.isNotEmpty())) {
            SheetSection("Subtitles")
            TrackRow("Off", subtitlesOff, autoFocus = subtitlesOff) { selectSubtitle(null) }
            subs.forEach { o ->
                TrackRow(o.label, o.selected && !subtitlesOff, autoFocus = o.selected && !subtitlesOff) { selectSubtitle(o) }
            }
            if (subs.isEmpty()) PanelNote("This one doesn't come with subtitles.")
            if (all) Spacer(Modifier.height(18.dp))
        }
        if (kind == "audio" || (all && audio.size > 1)) {
            SheetSection("Language")
            audio.forEachIndexed { i, o ->
                TrackRow(o.label, o.selected, autoFocus = kind == "audio" && (o.selected || (i == 0 && audio.none { it.selected }))) { selectAudio(o) }
            }
            if (audio.size <= 1) PanelNote("This one only comes in one language.")
            if (all) Spacer(Modifier.height(18.dp))
        }
        if (kind == "more" || all) {
            SheetSection("Picture")
            PICTURE_MODES.forEachIndexed { i, name ->
                TrackRow(name, vm.pictureMode == i, autoFocus = kind == "more" && vm.pictureMode == i) { vm.setPicture(i, announce = false) }
            }
            PanelNote(if (isTv) "Zoom fills the screen when there are black bars." else "Zoom fills the screen when there are black bars. You can also pinch the picture.")
            Spacer(Modifier.height(18.dp))
            SheetSection("Sleep timer")
            val left = vm.sleepMinutesLeft()
            ZapAction(EmberIcons.Moon, if (left == null) "Off" else "Stops in $left min", "Change") { vm.cycleSleepTimer() }
            Spacer(Modifier.height(18.dp))
            SheetSection("Won't play properly?")
            ZapAction(EmberIcons.OpenExternal, "Open in another player", "") {
                onDismiss()
                onOpenElsewhere()
            }
        }
    }
}

/**
 * The frosted panel that slides in from the right over the picture, Apple TV style. Phones can
 * tap outside it to close it; Back closes it everywhere (the caller's BackHandler).
 */
@Composable
private fun BoxScope.SidePanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val isTv = LocalIsTv.current
    if (!isTv) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(if (isTv) 28.dp else 12.dp)
            .width(if (isTv) 340.dp else 300.dp)
            .glassSurface(RoundedCornerShape(26.dp), Color(0xE0141418))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        content = content,
    )
}

@Composable
private fun PanelNote(text: String) {
    Text(text, style = Txt.Caption, color = Ember.Faint, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
}

@Composable
private fun TrackRow(label: String, selected: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).height(48.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.04f,
        color = if (selected) Color(0x26FFFFFF) else Color.Transparent,
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp)) {
                if (selected) Icon(EmberIcons.Check, contentDescription = "Chosen", tint = if (focused) Color.Black else Ember.Text, modifier = Modifier.size(16.dp))
            }
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
