package app.embertv.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.delay

/**
 * A small, muted, live picture of [channel], like TiviMate's preview window. It waits until the
 * channel has been highlighted for a moment so scrolling past doesn't open a stream for each one.
 * Shows the channel's logo until the picture arrives, or if it can't play.
 */
@Composable
fun LivePreview(vm: AppViewModel, channel: Channel?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Color.Black)
            .border(0.6.dp, Color(0x29FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (channel == null) return@Box
        if (!vm.livePreview) {
            ChannelLogo(channel.icon, channel.name, Modifier.fillMaxSize().padding(14.dp))
            return@Box
        }
        val context = LocalContext.current
        val userAgent = vm.userAgent
        val exo = remember(userAgent) {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 500, 1_000).build())
                .build()
                .also { it.volume = 0f }
        }
        var showing by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }

        DisposableEffect(exo) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    showing = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    failed = true
                    showing = false
                }
            }
            exo.addListener(listener)
            onDispose {
                exo.removeListener(listener)
                exo.release()
            }
        }
        // Stop in the background so it never holds one of the provider's connections.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, exo) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) exo.stop()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(channel.streamId, exo) {
            exo.stop()
            showing = false
            failed = false
            delay(900)
            exo.setMediaItem(MediaItem.fromUri(vm.liveUrl(channel, false)))
            exo.prepare()
            exo.playWhenReady = true
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exo
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(visible = !showing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF111116)), contentAlignment = Alignment.Center) {
                ChannelLogo(channel.icon, channel.name, Modifier.fillMaxSize().padding(16.dp))
            }
        }
        if (showing && !failed) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Ember.Live)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("LIVE", style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White)
            }
        }
    }
}
