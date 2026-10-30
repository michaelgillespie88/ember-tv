package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.embertv.data.nowSeconds
import kotlinx.coroutines.delay

@Composable
fun EmberApp(vm: AppViewModel, isTv: Boolean) {
    EmberTheme {
        val now by produceState(nowSeconds()) {
            while (true) {
                delay(20_000)
                value = nowSeconds()
            }
        }
        CompositionLocalProvider(LocalAppVm provides vm, LocalNow provides now, LocalIsTv provides isTv) {
            Box(Modifier.fillMaxSize().background(Ember.Bg)) {
                // Registered first so every screen's own back handling takes priority.
                BackHandler(enabled = vm.stack.size > 1) { vm.pop() }

                val restoring = vm.restoreFocusKey
                LaunchedEffect(restoring) {
                    if (restoring != null) {
                        delay(1500)
                        if (vm.restoreFocusKey == restoring) vm.restoreFocusKey = null
                    }
                }

                if (vm.repo == null) {
                    if (isTv) LoginScreen(vm) else PhoneLoginScreen(vm)
                } else {
                    when (val screen = vm.stack.last()) {
                        Screen.Home -> if (isTv) HomeScreen(vm) else PhoneHome(vm)
                        is Screen.MovieDetail -> if (isTv) MovieDetailScreen(vm, screen.movie) else PhoneMovieDetail(vm, screen.movie)
                        is Screen.SeriesDetail -> if (isTv) SeriesDetailScreen(vm, screen.series) else PhoneSeriesDetail(vm, screen.series)
                        is Screen.PlayLive -> LivePlayerScreen(vm, screen.channels, screen.index)
                        is Screen.PlayVod -> VodPlayerScreen(vm, screen.items, screen.index, screen.startMs)
                    }
                }

                NoticeOverlay(vm)
            }
        }
    }
}

@Composable
private fun BoxScope.NoticeOverlay(vm: AppViewModel) {
    val notice = vm.notice
    // Keeps the text on screen while the pill fades out.
    val lastText = remember { arrayOf("") }
    if (notice != null) lastText[0] = notice.text
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2200)
            vm.clearNotice(notice)
        }
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 28.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xEE24242B))
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Text(lastText[0], style = Txt.Label, color = Ember.Text)
        }
    }
}
